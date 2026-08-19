package dev.termestra.execution.adapter.out.pty;

import com.pty4j.*;
import dev.termestra.execution.application.port.out.*;
import dev.termestra.platform.process.ProcessTreeTerminator;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Pty4jProcessLauncher implements PseudoTerminalLauncher {
    private static final Logger LOG=LoggerFactory.getLogger(Pty4jProcessLauncher.class);
    private static final Duration TERMINATION_GRACE=Duration.ofMillis(500);
    private static final Duration FORCED_TERMINATION_GRACE=Duration.ofSeconds(2);
    private static final Duration OUTPUT_DRAIN_TIMEOUT=Duration.ofSeconds(5);
    private static final int MAX_DESCENDANTS=1_024;
    @Override public PseudoTerminalHandle start(ProcessLaunchRequest request){
        Map<String,String> environment=launchEnvironment(System.getenv(),request.environment());
        Optional<WindowsPtyProcessJob> windowsJob=Optional.empty();
        PtyProcess process=null;
        try{
            windowsJob=WindowsPtyProcessJob.prepareForCurrentPlatform();
            PtyProcessBuilder builder=new PtyProcessBuilder(request.command().toArray(String[]::new))
                    .setDirectory(request.directory()).setEnvironment(environment)
                    .setConsole(false).setRedirectErrorStream(true)
                    .setInitialColumns(request.columns()).setInitialRows(request.rows());
            if(windowsJob.isPresent()){
                WindowsPtyProcessJob preparedJob=windowsJob.get();
                builder.setUseWinConPty(true)
                        .setWindowsSuspendedProcessCallback(preparedJob::claimSuspended);
            }
            process=builder.start();
            if(windowsJob.isPresent())windowsJob.get().requireClaimed(process.pid());
            return new Handle(process,windowsJob);
        }catch(IOException error){
            cleanupFailedStart(process,windowsJob);
            throw new IllegalStateException(
                    "Failed to start PTY command: "+request.command().getFirst(),error);
        }catch(RuntimeException|LinkageError error){
            cleanupFailedStart(process,windowsJob);
            throw new IllegalStateException(
                    "Failed to establish PTY process ownership: "+request.command().getFirst(),error);
        }
    }

    static Map<String,String> launchEnvironment(Map<String,String> inherited,Map<String,String> requested){
        Map<String,String> environment=new HashMap<>(inherited);environment.putAll(requested);
        return environment;
    }

    private static void cleanupFailedStart(PtyProcess process,
                                           Optional<WindowsPtyProcessJob> windowsJob){
        if(process!=null)ProcessTreeTerminator.track(process).terminate(
                TERMINATION_GRACE,FORCED_TERMINATION_GRACE,MAX_DESCENDANTS);
        windowsJob.ifPresent(job->{
            if(!job.abort(FORCED_TERMINATION_GRACE))
                LOG.error("Failed-start Windows PTY ownership could not be closed safely");
        });
    }

    private static final class Handle implements PseudoTerminalHandle {
        private final PtyProcess process;private final Object lifecycleGate=new Object();
        private final ReentrantLock terminationGate=new ReentrantLock(true);
        private final ProcessTreeTerminator.TrackedProcessTree processTree;
        private final Optional<UnixPtyProcessGroup> processGroup;
        private final Optional<WindowsPtyProcessJob> windowsJob;
        private final CountDownLatch outputDrained=new CountDownLatch(1);
        private volatile Thread outputReader;
        private boolean activated;private boolean stopRequested;
        private final Object outputGate=new Object();private final Object inputGate=new Object();private volatile boolean outputPaused;
        Handle(PtyProcess process,Optional<WindowsPtyProcessJob> windowsJob){
            this.process=process;this.processTree=ProcessTreeTerminator.track(process);
            this.processGroup=UnixPtyProcessGroup.claim(process);
            this.windowsJob=windowsJob;
        }
        @Override public long pid(){return process.pid();}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){
            activate(output,exit,failure->LOG.error(
                    "Unhandled fatal PTY failure for pid {}; no lifecycle listener was registered",
                    pid(),failure));
        }
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit,
                                       Consumer<RuntimeException> failure){
            synchronized(lifecycleGate){
                if(stopRequested)throw new IllegalStateException("PTY handle was stopped before activation");
                if(activated)throw new IllegalStateException("PTY handle already activated");
                activated=true;
                // pty4j's native-backed InputStream can pin a Java 21 carrier while it blocks.
                // One daemon platform reader per active run is bounded by Agent Execution's
                // global live-run budget and keeps the virtual scheduler available for input.
                Thread.ofPlatform().daemon(true).name("termestra-pty-output-"+pid()).start(
                        ()->read(output,failure));
                // waitFor() itself can park, but its exit path owns native process-tree cleanup.
                // Keep that boundary off a virtual thread so a native call cannot pin a carrier
                // or stall virtual-thread lifecycle shutdown.
                Thread.ofPlatform().daemon(true).name("termestra-pty-exit-"+pid()).start(
                        ()->waitFor(exit,failure));
            }
        }
        private void read(Consumer<byte[]> output,Consumer<RuntimeException> failure){
            outputReader=Thread.currentThread();
            RuntimeException fatalFailure=null;
            try(InputStream input=process.getInputStream()){
                byte[] buffer=new byte[8192];int count;
                while(true){
                    if(!awaitOutputPermit())return;
                    count=input.read(buffer);if(count<0)return;
                    if(count>0){
                        try{output.accept(Arrays.copyOf(buffer,count));}
                        catch(RuntimeException listenerFailure){LOG.error("PTY output listener failed for pid {}; subsequent output will continue",pid(),listenerFailure);}
                    }
                }
            }catch(IOException error){
                if(process.isAlive()&&!stopWasRequested()){
                    fatalFailure=new IllegalStateException(
                            "PTY output stream failed while the process was still alive",error);
                }
            }finally{outputDrained.countDown();}
            if(fatalFailure!=null){
                try{failure.accept(fatalFailure);}
                catch(RuntimeException listenerFailure){
                    LOG.error("PTY failure listener failed for pid {}",pid(),listenerFailure);
                }
            }
        }
        private boolean awaitOutputPermit(){synchronized(outputGate){while(outputPaused&&process.isAlive())try{outputGate.wait(100);}catch(InterruptedException error){Thread.currentThread().interrupt();LOG.debug("PTY output reader interrupted for pid {}",pid());return false;}return true;}}
        private boolean stopWasRequested(){synchronized(lifecycleGate){return stopRequested;}}
        private void waitFor(IntConsumer exit,Consumer<RuntimeException> failure){
            try{
                int exitCode=process.waitFor();
                boolean ownershipReleased=terminateOwnedProcesses();
                resumeOutput();
                boolean outputComplete=awaitOutputDrain();
                if(!ownershipReleased||!outputComplete){
                    RuntimeException lifecycleFailure=new IllegalStateException(
                            "PTY root exited before complete process ownership and output drain could be confirmed");
                    try{failure.accept(lifecycleFailure);}
                    catch(RuntimeException listenerFailure){
                        LOG.error("PTY failure listener failed for pid {}",pid(),listenerFailure);
                    }
                    return;
                }
                try{exit.accept(exitCode);}catch(RuntimeException listenerFailure){LOG.error("PTY exit listener failed for pid {}",pid(),listenerFailure);}
            }catch(InterruptedException error){Thread.currentThread().interrupt();LOG.debug("PTY exit waiter interrupted for pid {}",pid());}
        }
        private boolean awaitOutputDrain(){
            if(Thread.currentThread()==outputReader)return true;
            try{
                if(!outputDrained.await(OUTPUT_DRAIN_TIMEOUT.toMillis(),TimeUnit.MILLISECONDS)){
                    LOG.warn("PTY output for pid {} did not drain within the bounded deadline",pid());
                    return false;
                }
            }catch(InterruptedException error){
                Thread.currentThread().interrupt();
                LOG.debug("PTY output drain interrupted for pid {}",pid());
                return false;
            }
            return true;
        }
        @Override public void write(byte[] input){synchronized(inputGate){try{OutputStream output=process.getOutputStream();output.write(input);output.flush();}catch(IOException error){throw new IllegalStateException("Failed to write PTY input",error);}}}
        @Override public void resize(int columns,int rows){process.setWinSize(new WinSize(columns,rows));}
        @Override public void pauseOutput(){synchronized(outputGate){outputPaused=true;}}
        @Override public void resumeOutput(){synchronized(outputGate){outputPaused=false;outputGate.notifyAll();}}
        @Override public void stop(){stopAndConfirm();}
        @Override public boolean stopAndConfirm(){
            boolean awaitReader;
            synchronized(lifecycleGate){stopRequested=true;awaitReader=activated;}
            resumeOutput();
            boolean terminated=terminateOwnedProcesses();
            if(!terminated){
                LOG.warn("PTY process ownership for pid {} did not fully stop within the bounded deadline; a later request may retry",pid());
                return false;
            }
            return !awaitReader||awaitOutputDrain();
        }
        private boolean terminateOwnedProcesses(){
            terminationGate.lock();
            try{
                // A Job Object is authoritative on Windows: children cannot break away because the
                // job does not opt into breakaway, and membership survives root re-parenting.
                if(windowsJob.isPresent())return windowsJob.get().terminate(
                        FORCED_TERMINATION_GRACE);
                // Capture and terminate observable descendants first. A child that changes its
                // process group is still covered while the root relationship remains available.
                boolean treeTerminated=processTree.terminate(
                        TERMINATION_GRACE,FORCED_TERMINATION_GRACE,MAX_DESCENDANTS);
                // The dedicated PTY process group survives root exit, covering the common orphan
                // race where ProcessHandle can no longer rediscover a background child.
                boolean groupTerminated=processGroup.map(group->group.terminate(
                        TERMINATION_GRACE,FORCED_TERMINATION_GRACE)).orElse(true);
                return treeTerminated&&groupTerminated;
            }finally{terminationGate.unlock();}
        }
        @Override public boolean alive(){return process.isAlive();}
    }
}
