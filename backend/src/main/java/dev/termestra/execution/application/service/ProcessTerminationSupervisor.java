package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Keeps ownership of processes whose first bounded termination attempt did not complete.
 *
 * <p>A failed stop never authorizes callers to release credentials or run capacity. There is one
 * retry item per live run and the pending set cannot exceed the global live-run budget. Scheduling
 * uses one timer thread while a bounded set of daemon platform threads performs native process
 * operations. Callers wait only for one fixed attempt deadline, so a stuck native call retains
 * ownership without pinning a lifecycle or request thread forever. A native attempt that has not
 * returned is never duplicated; retry begins only if that attempt returns without confirmation.</p>
 */
final class ProcessTerminationSupervisor {
    private static final Logger LOG=LoggerFactory.getLogger(ProcessTerminationSupervisor.class);
    static final int MAX_PENDING=128;
    private static final long INITIAL_DELAY_MILLIS=100;
    private static final long MAX_DELAY_MILLIS=5_000;
    static final int MAX_CONCURRENT_ATTEMPTS=8;
    // Unix tree, process-group, and output-drain phases can legitimately take about ten seconds.
    // Leave scheduler headroom so a normal bounded teardown is not mistaken for a stuck native call.
    private static final Duration DEFAULT_ATTEMPT_TIMEOUT=Duration.ofSeconds(12);

    private final ConcurrentHashMap<String,PendingTermination> pending=new ConcurrentHashMap<>();
    private final Semaphore pendingCapacity=new Semaphore(MAX_PENDING);
    private final ScheduledThreadPoolExecutor timer;
    private final ThreadPoolExecutor workers;
    private final Duration attemptTimeout;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicBoolean shutdown=new AtomicBoolean();

    ProcessTerminationSupervisor(){
        this(DEFAULT_ATTEMPT_TIMEOUT);
    }

    ProcessTerminationSupervisor(Duration attemptTimeout){
        this.attemptTimeout=requirePositive(attemptTimeout,"attemptTimeout");
        timer=new ScheduledThreadPoolExecutor(1,runnable->{
            Thread thread=new Thread(runnable,"termestra-process-termination-timer");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        workers=new ThreadPoolExecutor(MAX_CONCURRENT_ATTEMPTS,MAX_CONCURRENT_ATTEMPTS,
                30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(MAX_PENDING),
                Thread.ofPlatform().daemon(true).name("termestra-process-termination-",0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    RuntimeException terminate(String key,BooleanSupplier stopAndConfirm,Runnable onTerminated){
        Objects.requireNonNull(key,"key");
        Objects.requireNonNull(stopAndConfirm,"stopAndConfirm");
        Objects.requireNonNull(onTerminated,"onTerminated");
        PendingTermination existing=pending.get(key);
        if(existing!=null)return existing.failureOrPending();
        if(closing.get())return new ExecutionConflict("Process termination supervisor is closed: "+key);
        if(!pendingCapacity.tryAcquire()){
            return new ExecutionConflict("Process termination retry capacity exhausted: "+key);
        }
        PendingTermination candidate=new PendingTermination(
                key,stopAndConfirm,onTerminated,INITIAL_DELAY_MILLIS,0);
        existing=pending.putIfAbsent(key,candidate);
        if(existing!=null){
            pendingCapacity.release();
            return existing.failureOrPending();
        }
        if(closing.get()){
            if(pending.remove(key,candidate))pendingCapacity.release();
            shutdownWhenIdle();
            return new ExecutionConflict("Process termination supervisor is closed: "+key);
        }
        Attempt attempt=beginAttempt(candidate);
        if(attempt==null)return candidate.failureOrPending();
        return awaitInitialAttempt(candidate,attempt);
    }

    private Attempt beginAttempt(PendingTermination item){
        if(pending.get(item.key())!=item)return null;
        Attempt attempt;
        synchronized(item){
            if(item.inFlight()!=null)return item.inFlight();
            attempt=new Attempt();
            item.begin(attempt);
        }
        try{
            workers.execute(()->attempt(item,attempt));
            return attempt;
        }catch(RejectedExecutionException rejected){
            RuntimeException failure=new ExecutionConflict(
                    "Could not schedule bounded process termination attempt for run: "+item.key(),rejected);
            item.clear(attempt);
            item.failed(failure);
            schedule(item);
            return null;
        }
    }

    private RuntimeException awaitInitialAttempt(PendingTermination item,Attempt attempt){
        try{
            RuntimeException failure=attempt.result().get(
                    attemptTimeout.toMillis(),TimeUnit.MILLISECONDS);
            return failure;
        }catch(java.util.concurrent.TimeoutException timeout){
            RuntimeException failure=new ExecutionConflict(
                    "Process termination attempt did not finish within the bounded deadline for run: "
                            +item.key(),timeout);
            item.failed(failure);
            return failure;
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            RuntimeException failure=new ExecutionConflict(
                    "Interrupted while waiting for bounded process termination attempt: "+item.key(),
                    interrupted);
            item.failed(failure);
            return failure;
        }catch(java.util.concurrent.ExecutionException unexpected){
            RuntimeException failure=new ExecutionConflict(
                    "Could not complete bounded process termination attempt for run: "+item.key(),
                    unexpected.getCause());
            item.failed(failure);
            return failure;
        }
    }

    private void attempt(PendingTermination item,Attempt attempt){
        if(pending.get(item.key())!=item){
            item.clear(attempt);
            attempt.complete(item.failureOrPending());
            return;
        }
        RuntimeException failure=null;
        boolean terminated=false;
        try{terminated=item.stopAndConfirm().getAsBoolean();}
        catch(RuntimeException|LinkageError stopFailure){failure=new ExecutionConflict(
                "Could not confirm process-tree termination for run: "+item.key(),stopFailure);}
        if(!terminated&&failure==null){
            failure=new ExecutionConflict(
                    "Process termination is not yet confirmed after the bounded stop deadline: "+item.key());
        }
        item.clear(attempt);
        if(failure!=null){
            item.failed(failure);
            if(shouldLog(item.attempt())){
                LOG.warn("Process tree for run {} is still alive after {} bounded termination attempts",
                        item.key(),item.attempt(),failure);
            }
            attempt.complete(failure);
            schedule(item);
            return;
        }
        if(pending.remove(item.key(),item)){
            pendingCapacity.release();
            try{item.onTerminated().run();}
            catch(RuntimeException callbackFailure){
                LOG.error("Post-termination transition failed for run {}",item.key(),callbackFailure);
            }
            shutdownWhenIdle();
        }
        attempt.complete(null);
    }

    private void schedule(PendingTermination item){
        if(pending.get(item.key())!=item)return;
        try{
            timer.schedule(()->dispatch(item),item.delayMillis(),TimeUnit.MILLISECONDS);
        }catch(RejectedExecutionException rejected){
            if(!shutdown.get())LOG.error("Could not schedule process termination retry for run {}",
                    item.key(),rejected);
        }
    }

    private void dispatch(PendingTermination item){
        if(pending.get(item.key())!=item)return;
        Attempt attempt=beginAttempt(item);
        if(attempt==null&&!shutdown.get())LOG.error(
                "Could not dispatch process termination retry for run {}",item.key());
    }

    private static boolean shouldLog(int attempt){
        return attempt>=4&&(attempt&(attempt-1))==0;
    }

    /** Stops accepting new work but lets the bounded pending set finish in the background. */
    void closeWhenIdle(){
        closing.set(true);
        shutdownWhenIdle();
    }

    private void shutdownWhenIdle(){
        if(!closing.get()||!pending.isEmpty()||!shutdown.compareAndSet(false,true))return;
        timer.shutdownNow();
        workers.shutdown();
    }

    int pendingCount(){return pending.size();}

    private static Duration requirePositive(Duration value,String name){
        Objects.requireNonNull(value,name);
        if(value.isZero()||value.isNegative())throw new IllegalArgumentException(name+" must be positive");
        try{value.toNanos();}
        catch(ArithmeticException overflow){throw new IllegalArgumentException(name+" is too large",overflow);}
        return value;
    }

    private static final class Attempt{
        private final java.util.concurrent.CompletableFuture<RuntimeException> result=
                new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<RuntimeException> result(){return result;}
        void complete(RuntimeException failure){result.complete(failure);}
    }

    private static final class PendingTermination{
        private final String key;private final BooleanSupplier stopAndConfirm;
        private final Runnable onTerminated;private long delayMillis;private int attempt;
        private volatile RuntimeException failure;
        private Attempt inFlight;
        private PendingTermination(String key,BooleanSupplier stopAndConfirm,Runnable onTerminated,
                                   long delayMillis,int attempt){
            this.key=key;this.stopAndConfirm=stopAndConfirm;this.onTerminated=onTerminated;
            this.delayMillis=delayMillis;this.attempt=attempt;
        }
        String key(){return key;}BooleanSupplier stopAndConfirm(){return stopAndConfirm;}
        Runnable onTerminated(){return onTerminated;}
        synchronized long delayMillis(){return delayMillis;}
        synchronized int attempt(){return attempt;}
        void failed(RuntimeException value){failure=value;}
        RuntimeException failureOrPending(){
            RuntimeException value=failure;
            return value==null?new ExecutionConflict("Process-tree termination is already pending: "+key):value;
        }
        synchronized Attempt inFlight(){return inFlight;}
        synchronized void begin(Attempt value){
            if(inFlight!=null)throw new IllegalStateException("Process termination attempt already in flight");
            inFlight=value;attempt++;
        }
        synchronized void clear(Attempt value){
            if(inFlight==value)inFlight=null;
            delayMillis=Math.min(MAX_DELAY_MILLIS,delayMillis*2);
        }
    }
}
