package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Keeps ownership of processes whose first bounded termination attempt did not complete.
 *
 * <p>A failed stop never authorizes callers to release credentials or run capacity. There is one
 * retry item per live run and the pending set cannot exceed the global live-run budget. Scheduling
 * uses one timer thread while the bounded set of blocking termination attempts runs on virtual
 * threads, so one stubborn process cannot delay unrelated cleanup.</p>
 */
final class ProcessTerminationSupervisor {
    private static final Logger LOG=LoggerFactory.getLogger(ProcessTerminationSupervisor.class);
    static final int MAX_PENDING=128;
    private static final long INITIAL_DELAY_MILLIS=100;
    private static final long MAX_DELAY_MILLIS=5_000;

    private final ConcurrentHashMap<String,PendingTermination> pending=new ConcurrentHashMap<>();
    private final Semaphore pendingCapacity=new Semaphore(MAX_PENDING);
    private final ScheduledThreadPoolExecutor timer;
    private final ExecutorService workers;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicBoolean shutdown=new AtomicBoolean();

    ProcessTerminationSupervisor(){
        timer=new ScheduledThreadPoolExecutor(1,runnable->{
            Thread thread=new Thread(runnable,"termestra-process-termination-timer");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        workers=Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("termestra-process-termination-",0).factory());
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
                key,stopAndConfirm,onTerminated,INITIAL_DELAY_MILLIS,1);
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
        RuntimeException failure=attempt(candidate);
        if(failure!=null)schedule(candidate);
        return failure;
    }

    private RuntimeException attempt(PendingTermination item){
        if(pending.get(item.key())!=item)return item.failureOrPending();
        RuntimeException failure=null;
        boolean terminated=false;
        try{terminated=item.stopAndConfirm().getAsBoolean();}
        catch(RuntimeException stopFailure){failure=new ExecutionConflict(
                "Could not confirm process-tree termination for run: "+item.key(),stopFailure);}
        if(!terminated&&failure==null){
            failure=new ExecutionConflict(
                    "Process termination is not yet confirmed after the bounded stop deadline: "+item.key());
        }
        if(failure!=null){item.failed(failure);return failure;}
        if(pending.remove(item.key(),item)){
            pendingCapacity.release();
            try{item.onTerminated().run();}
            catch(RuntimeException callbackFailure){
                LOG.error("Post-termination transition failed for run {}",item.key(),callbackFailure);
            }
            shutdownWhenIdle();
        }
        return null;
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
        try{workers.execute(()->retry(item));}
        catch(RejectedExecutionException rejected){
            if(!shutdown.get())LOG.error("Could not dispatch process termination retry for run {}",
                    item.key(),rejected);
        }
    }

    private void retry(PendingTermination item){
        if(pending.get(item.key())!=item)return;
        RuntimeException failure=attempt(item);
        if(failure==null)return;
        if(shouldLog(item.attempt())){
            LOG.warn("Process tree for run {} is still alive after {} bounded termination attempts",
                    item.key(),item.attempt(),failure);
        }
        long nextDelay=Math.min(MAX_DELAY_MILLIS,item.delayMillis()*2);
        PendingTermination next=item.next(nextDelay);
        if(pending.replace(item.key(),item,next))schedule(next);
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

    private static final class PendingTermination{
        private final String key;private final BooleanSupplier stopAndConfirm;
        private final Runnable onTerminated;private final long delayMillis;private final int attempt;
        private volatile RuntimeException failure;
        private PendingTermination(String key,BooleanSupplier stopAndConfirm,Runnable onTerminated,
                                   long delayMillis,int attempt){
            this.key=key;this.stopAndConfirm=stopAndConfirm;this.onTerminated=onTerminated;
            this.delayMillis=delayMillis;this.attempt=attempt;
        }
        String key(){return key;}BooleanSupplier stopAndConfirm(){return stopAndConfirm;}
        Runnable onTerminated(){return onTerminated;}long delayMillis(){return delayMillis;}
        int attempt(){return attempt;}
        void failed(RuntimeException value){failure=value;}
        RuntimeException failureOrPending(){
            RuntimeException value=failure;
            return value==null?new ExecutionConflict("Process-tree termination is already pending: "+key):value;
        }
        PendingTermination next(long nextDelay){
            PendingTermination next=new PendingTermination(
                    key,stopAndConfirm,onTerminated,nextDelay,attempt+1);
            next.failure=failure;
            return next;
        }
    }
}
