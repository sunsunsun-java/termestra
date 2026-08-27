package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.execution.application.exception.RunNotFound;
import dev.termestra.execution.adapter.out.pty.Pty4jProcessLauncher;
import dev.termestra.bootstrap.support.PtyTestFixture;
import dev.termestra.bootstrap.support.TestJavaCommand;
import dev.termestra.execution.application.port.in.AgentRunView;
import dev.termestra.execution.application.port.in.StartAgentCommand;
import dev.termestra.execution.application.port.out.*;
import dev.termestra.execution.domain.model.AgentLaunchConfiguration;
import dev.termestra.execution.domain.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionTerminationSafetyTest {
    private static final String WORKSPACE="workspace-termination";
    private static final String AGENT="worker-termination";
    private static final TestJavaCommand PTY_FIXTURE=TestJavaCommand.fixture(PtyTestFixture.class,"echo");

    @Test void failedStopRetainsDurableStatusCredentialAndCapacityUntilRetryConfirmsTermination()
            throws Exception{
        RecordingRepository repository=new RecordingRepository();
        RecordingCredentials credentials=new RecordingCredentials();
        RetryingStopPty first=new RetryingStopPty(101);
        AtomicInteger launches=new AtomicInteger();
        AgentExecutionService service=service(repository,credentials,request->{
            if(launches.incrementAndGet()==1)return first;
            return new ImmediateStopPty(102);
        },new RunCapacityBudget(1,1));

        try{
            AgentRunView run=service.start(new StartAgentCommand(WORKSPACE,AGENT,"4010"));
            first.emit("running");

            ExecutionConflict failure=assertThrows(ExecutionConflict.class,()->service.stop(run.runId()));

            assertTrue(failure.getMessage().contains("not yet confirmed"));
            assertEquals("running",service.get(run.runId()).status(),
                    "memory must not publish an uncommitted terminal status");
            assertEquals("running",service.getSummary(run.runId()).status(),
                    "a process that is still alive must not be projected as terminal");
            assertFalse(credentials.revoked.get(),"the live process must retain its credential");
            assertThrows(ExecutionConflict.class,()->service.start(
                    new StartAgentCommand(WORKSPACE,"another-worker","4010")),
                    "the live process must retain its capacity lease");

            first.allowTermination();
            awaitStatus(service,run.runId(),"error");
            assertTrue(credentials.revoked.get());
            assertTrue(first.stopAttempts.get()>=2);
            assertDoesNotThrow(()->service.start(
                    new StartAgentCommand(WORKSPACE,"another-worker","4010")));
        }finally{service.close();}
    }

    @Test void explicitStopRetainsOutputProducedWhileTheProcessTreeIsDraining(){
        RecordingRepository repository=new RecordingRepository();
        TailDuringStopPty process=new TailDuringStopPty(105);
        AgentExecutionService service=service(repository,new RecordingCredentials(),ignored->process,
                new RunCapacityBudget(1,1));
        try{
            AgentRunView run=service.start(new StartAgentCommand(WORKSPACE,AGENT,"4010"));
            process.emit("head:");

            service.stop(run.runId());

            assertEquals("head:尾部",service.get(run.runId()).output());
            assertEquals("error",service.get(run.runId()).status());
        }finally{service.close();}
    }

    @Test void fatalPtyIoFailureUsesTheSupervisedTerminationRetryBeforeReleasingResources()
            throws Exception{
        RecordingRepository repository=new RecordingRepository();
        RecordingCredentials credentials=new RecordingCredentials();
        FatalReadPty process=new FatalReadPty(107);
        AtomicInteger launches=new AtomicInteger();
        AgentExecutionService service=service(repository,credentials,ignored->
                        launches.incrementAndGet()==1?process:new ImmediateStopPty(108),
                new RunCapacityBudget(1,1));
        try{
            AgentRunView run=service.start(new StartAgentCommand(WORKSPACE,AGENT,"4010"));
            process.emit("running");

            process.failOutput();

            assertTrue(process.stopAttempts.get()>=1);
            assertEquals("running",service.get(run.runId()).status(),
                    "an unconfirmed process termination must not be projected as terminal");
            assertFalse(credentials.revoked.get());
            assertThrows(ExecutionConflict.class,()->service.start(
                    new StartAgentCommand(WORKSPACE,"replacement-worker","4010")));

            process.allowTermination();
            awaitStatus(service,run.runId(),"error");
            assertTrue(credentials.revoked.get());
            assertDoesNotThrow(()->service.start(
                    new StartAgentCommand(WORKSPACE,"replacement-worker","4010")));
        }finally{service.close();}
    }

    @Test void failedUnregisteredStopRetainsCapacityAndCredentialUntilSupervisorSucceeds()
            throws Exception{
        RecordingRepository repository=new RecordingRepository();
        RecordingCredentials credentials=new RecordingCredentials();
        ActivationFailureRetryingPty first=new ActivationFailureRetryingPty(111);
        AtomicInteger launches=new AtomicInteger();
        AgentExecutionService service=service(repository,credentials,request->{
            if(launches.incrementAndGet()==1)return first;
            return new ImmediateStopPty(112);
        },new RunCapacityBudget(1,1));
        try{
            assertThrows(IllegalStateException.class,()->service.start(
                    new StartAgentCommand(WORKSPACE,AGENT,"4010")));
            assertFalse(credentials.revoked.get());
            assertThrows(ExecutionConflict.class,()->service.start(
                    new StartAgentCommand(WORKSPACE,"another-worker","4010")));

            first.allowTermination();
            awaitTrue(credentials.revoked,3,TimeUnit.SECONDS);
            assertDoesNotThrow(()->service.start(
                    new StartAgentCommand(WORKSPACE,"another-worker","4010")));
        }finally{service.close();}
    }

    @Test void insertRunFailureStopsTheUnactivatedRealPtyAndImmediatelyReusesItsCapacity(){
        RecordingRepository repository=new RecordingRepository();
        repository.rejectNextInsert.set(true);
        RecordingCredentials credentials=new RecordingCredentials();
        AgentExecutionService service=service(repository,credentials,new Pty4jProcessLauncher(),
                new RunCapacityBudget(1,1));
        try{
            assertThrows(ExecutionConflict.class,()->service.start(
                    new StartAgentCommand(WORKSPACE,AGENT,"4010")));

            assertEquals(1,credentials.revokeCount.get(),
                    "the failed unactivated PTY must release its credential immediately");
            assertDoesNotThrow(()->service.start(
                    new StartAgentCommand(WORKSPACE,"replacement-worker","4010")),
                    "the failed unactivated PTY must release its capacity lease immediately");
        }finally{service.close();}
    }

    @Test void durablyDeletedRunIsRetainedUntilItsProcessActuallyStops() throws Exception{
        RecordingRepository repository=new RecordingRepository();
        RecordingCredentials credentials=new RecordingCredentials();
        RetryingStopPty first=new RetryingStopPty(121);
        AtomicInteger launches=new AtomicInteger();
        AgentExecutionService service=service(repository,credentials,request->{
            if(launches.incrementAndGet()==1)return first;
            return new ImmediateStopPty(122);
        },new RunCapacityBudget(1,1));
        AgentRunView run=service.start(new StartAgentCommand(WORKSPACE,AGENT,"4010"));
        first.emit("running");

        service.forgetWorkspace(WORKSPACE);

        assertDoesNotThrow(()->service.get(run.runId()),
                "runtime ownership must remain while the deleted process is alive");
        assertFalse(credentials.revoked.get());
        assertThrows(ExecutionConflict.class,()->service.start(
                new StartAgentCommand("other-workspace","another-worker","4010")));

        first.allowTermination();
        awaitMissing(service,run.runId());
        assertTrue(credentials.revoked.get());
        assertDoesNotThrow(()->service.start(
                new StartAgentCommand("other-workspace","another-worker","4010")));
        service.close();
    }

    @Test void forgetWorkspaceReturnsAfterTheBoundedNativeStopDeadlineWithoutReleasingOwnership()
            throws Exception{
        RecordingRepository repository=new RecordingRepository();
        RecordingCredentials credentials=new RecordingCredentials();
        BlockingStopPty process=new BlockingStopPty(125);
        ProcessTerminationSupervisor supervisor=new ProcessTerminationSupervisor(Duration.ofMillis(100));
        AgentExecutionService service=service(repository,credentials,ignored->process,
                new RunCapacityBudget(1,1),supervisor);
        try{
            AgentRunView run=service.start(new StartAgentCommand(WORKSPACE,AGENT,"4010"));

            long started=System.nanoTime();
            service.forgetWorkspace(WORKSPACE);
            long elapsedMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);

            assertTrue(process.stopEntered.await(1,TimeUnit.SECONDS));
            assertTrue(elapsedMillis<1_000,
                    "workspace cleanup must not wait forever on an uninterruptible native stop");
            assertDoesNotThrow(()->service.get(run.runId()),
                    "ownership must remain until the native stop actually completes");
            assertFalse(credentials.revoked.get());

            process.allowTermination();
            awaitMissing(service,run.runId());
            assertTrue(credentials.revoked.get());
        }finally{
            process.allowTermination();
            service.close();
        }
    }

    @Test void forgetWorkspaceStopsAllRunsConcurrently() throws Exception{
        ConcurrentStopFixture fixture=new ConcurrentStopFixture(8);
        try{
            List<AgentRunView> runs=fixture.startShellRuns();
            fixture.service.forgetWorkspace(WORKSPACE);

            assertEquals(0,fixture.allStopCallsEntered.getCount(),
                    "all bounded stop attempts must overlap instead of serializing their deadlines");
            for(AgentRunView run:runs)assertThrows(RunNotFound.class,
                    ()->fixture.service.get(run.runId()));
        }finally{fixture.service.close();}
    }

    @Test void serviceCloseReturnsAfterTheBoundedNativeStopDeadlineWithoutReleasingOwnership()
            throws Exception{
        RecordingRepository repository=new RecordingRepository();
        RecordingCredentials credentials=new RecordingCredentials();
        BlockingStopPty process=new BlockingStopPty(126);
        AgentExecutionService service=service(repository,credentials,ignored->process,
                new RunCapacityBudget(1,1),new ProcessTerminationSupervisor(Duration.ofMillis(100)));
        AgentRunView run=service.start(new StartAgentCommand(WORKSPACE,AGENT,"4010"));
        try{
            long started=System.nanoTime();
            service.close();
            long elapsedMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);

            assertTrue(process.stopEntered.await(1,TimeUnit.SECONDS));
            assertTrue(elapsedMillis<1_000,
                    "service close must not wait forever on an uninterruptible native stop");
            assertDoesNotThrow(()->service.get(run.runId()));
            assertFalse(credentials.revoked.get());

            process.allowTermination();
            awaitStatus(service,run.runId(),"error");
            assertTrue(credentials.revoked.get());
        }finally{
            process.allowTermination();
            service.close();
        }
    }

    @Test void serviceCloseStopsAllRunsConcurrently(){
        ConcurrentStopFixture fixture=new ConcurrentStopFixture(8);
        fixture.startShellRuns();

        fixture.service.close();

        assertEquals(0,fixture.allStopCallsEntered.getCount(),
                "service shutdown must aggregate bounded stop deadlines concurrently");
        assertTrue(fixture.processes.stream().noneMatch(PseudoTerminalHandle::alive));
    }

    @Test void lifecycleCleanupDoesNotDependOnVirtualThreadCarriers() throws Exception{
        AtomicReference<Thread> executedBy=new AtomicReference<>();
        try(ExecutorService executor=AgentExecutionService.newLifecycleCleanupExecutor(1)){
            Future<?> completed=executor.submit(()->executedBy.set(Thread.currentThread()));
            completed.get(1,TimeUnit.SECONDS);
        }

        Thread worker=executedBy.get();
        assertFalse(worker.isVirtual());
        assertTrue(worker.isDaemon());
        assertTrue(worker.getName().startsWith("termestra-lifecycle-cleanup-"));
    }

    private static AgentExecutionService service(RecordingRepository repository,
                                                   AgentCredentialIssuer credentials,
                                                   PseudoTerminalLauncher launcher,
                                                   RunCapacityBudget capacity){
        return service(repository,credentials,launcher,capacity,new ProcessTerminationSupervisor());
    }

    private static AgentExecutionService service(RecordingRepository repository,
                                                   AgentCredentialIssuer credentials,
                                                   PseudoTerminalLauncher launcher,
                                                   RunCapacityBudget capacity,
                                                   ProcessTerminationSupervisor supervisor){
        return new AgentExecutionService(repository,(workspaceId,agentId)->Optional.of(
                descriptor(workspaceId,agentId)),credentials,launcher,noCapture(),
                (presetId,command)->List.of(),noRecovery(),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"),ZoneOffset.UTC),
                new dev.termestra.shared.concurrency.RuntimeOperationCoordinator(),capacity,supervisor);
    }

    private static AgentDescriptor descriptor(String workspaceId,String agentId){
        String role=agentId.endsWith(":shell")?"shell":"coder";
        return new AgentDescriptor(workspaceId,"Workspace",System.getProperty("java.io.tmpdir"),
                agentId,"Worker","Tasks",role);
    }

    private static AgentSessionCapture noCapture(){
        return new AgentSessionCapture(){
            @Override public Optional<CaptureSnapshot> snapshot(AgentDescriptor agent,String json){return Optional.empty();}
            @Override public Optional<String> findNew(CaptureSnapshot snapshot){return Optional.empty();}
            @Override public boolean exists(AgentDescriptor agent,String json,String sessionId){return false;}
        };
    }

    private static AgentRecoveryContextProvider noRecovery(){
        return new AgentRecoveryContextProvider(){
            @Override public boolean hasPreviousRun(String agentId,String currentRunId){return false;}
            @Override public RecoveryContext load(String workspaceId,Instant recentSince){
                return new RecoveryContext("",List.of(),List.of(),List.of());
            }
            @Override public long appendSystemRecoveryMessage(String workspaceId,String agentId,String text,Instant at){return 1;}
            @Override public long appendUserInput(String workspaceId,String agentId,String text,Instant at){return 1;}
            @Override public void deleteMessage(long sequence){}
        };
    }

    private static void awaitStatus(AgentExecutionService service,String runId,String status)
            throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){
            if(status.equals(service.get(runId).status()))return;
            Thread.sleep(10);
        }
        assertEquals(status,service.get(runId).status());
    }

    private static void awaitMissing(AgentExecutionService service,String runId)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){
            try{service.get(runId);}catch(RunNotFound expected){return;}
            Thread.sleep(10);
        }
        assertThrows(RunNotFound.class,()->service.get(runId));
    }

    private static void awaitTrue(AtomicBoolean value,long timeout,TimeUnit unit)throws Exception{
        long deadline=System.nanoTime()+unit.toNanos(timeout);
        while(System.nanoTime()<deadline){if(value.get())return;Thread.sleep(10);}
        assertTrue(value.get());
    }

    private static class RetryingStopPty implements PseudoTerminalHandle{
        private final long pid;private final AtomicBoolean alive=new AtomicBoolean(true);
        private final AtomicBoolean mayStop=new AtomicBoolean();private final AtomicInteger stopAttempts=new AtomicInteger();
        private volatile Consumer<byte[]> output;private volatile IntConsumer exit;
        private RetryingStopPty(long pid){this.pid=pid;}
        @Override public long pid(){return pid;}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){this.output=output;this.exit=exit;}
        @Override public void write(byte[] input){}
        @Override public void resize(int columns,int rows){}
        @Override public void pauseOutput(){}
        @Override public void resumeOutput(){}
        @Override public void stop(){stopAndConfirm();}
        @Override public boolean stopAndConfirm(){
            stopAttempts.incrementAndGet();
            if(!mayStop.get())return false;
            if(alive.compareAndSet(true,false)&&exit!=null)exit.accept(143);
            return true;
        }
        @Override public boolean alive(){return alive.get();}
        void emit(String text){output.accept(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        void allowTermination(){mayStop.set(true);}
    }

    private static final class BlockingStopPty implements PseudoTerminalHandle{
        private final long pid;private final AtomicBoolean alive=new AtomicBoolean(true);
        private final CountDownLatch stopEntered=new CountDownLatch(1);
        private final CountDownLatch release=new CountDownLatch(1);
        private BlockingStopPty(long pid){this.pid=pid;}
        @Override public long pid(){return pid;}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){}
        @Override public void write(byte[] input){}
        @Override public void resize(int columns,int rows){}
        @Override public void pauseOutput(){}
        @Override public void resumeOutput(){}
        @Override public void stop(){stopAndConfirm();}
        @Override public boolean stopAndConfirm(){
            stopEntered.countDown();
            boolean interrupted=false;
            while(true){
                try{release.await();break;}
                catch(InterruptedException ignored){interrupted=true;}
            }
            if(interrupted)Thread.currentThread().interrupt();
            alive.set(false);
            return true;
        }
        @Override public boolean alive(){return alive.get();}
        void allowTermination(){release.countDown();}
    }

    private static final class ActivationFailureRetryingPty extends RetryingStopPty{
        private ActivationFailureRetryingPty(long pid){super(pid);}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){
            throw new IllegalStateException("activation failed");
        }
    }

    private static final class ImmediateStopPty implements PseudoTerminalHandle{
        private final long pid;private final AtomicBoolean alive=new AtomicBoolean(true);
        private volatile IntConsumer exit;
        private ImmediateStopPty(long pid){this.pid=pid;}
        @Override public long pid(){return pid;}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){this.exit=exit;}
        @Override public void write(byte[] input){}
        @Override public void resize(int columns,int rows){}
        @Override public void pauseOutput(){}
        @Override public void resumeOutput(){}
        @Override public void stop(){if(alive.compareAndSet(true,false)&&exit!=null)exit.accept(0);}
        @Override public boolean alive(){return alive.get();}
    }

    private static final class TailDuringStopPty implements PseudoTerminalHandle{
        private final long pid;private final AtomicBoolean alive=new AtomicBoolean(true);
        private volatile Consumer<byte[]> output;
        private TailDuringStopPty(long pid){this.pid=pid;}
        @Override public long pid(){return pid;}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){this.output=output;}
        @Override public void write(byte[] input){}
        @Override public void resize(int columns,int rows){}
        @Override public void pauseOutput(){}
        @Override public void resumeOutput(){}
        @Override public void stop(){stopAndConfirm();}
        @Override public boolean stopAndConfirm(){
            byte[] tail="尾部".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            output.accept(java.util.Arrays.copyOfRange(tail,0,2));
            output.accept(java.util.Arrays.copyOfRange(tail,2,tail.length));
            alive.set(false);return true;
        }
        @Override public boolean alive(){return alive.get();}
        void emit(String text){output.accept(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }

    private static final class FatalReadPty implements PseudoTerminalHandle{
        private final long pid;private final AtomicBoolean alive=new AtomicBoolean(true);
        private final AtomicBoolean mayStop=new AtomicBoolean();
        private final AtomicInteger stopAttempts=new AtomicInteger();
        private volatile Consumer<byte[]> output;
        private volatile Consumer<RuntimeException> failure;
        private FatalReadPty(long pid){this.pid=pid;}
        @Override public long pid(){return pid;}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){this.output=output;}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit,
                                       Consumer<RuntimeException> failure){
            this.output=output;this.failure=failure;
        }
        @Override public void write(byte[] input){}
        @Override public void resize(int columns,int rows){}
        @Override public void pauseOutput(){}
        @Override public void resumeOutput(){}
        @Override public void stop(){stopAndConfirm();}
        @Override public boolean stopAndConfirm(){
            stopAttempts.incrementAndGet();
            if(!mayStop.get())return false;
            alive.set(false);return true;
        }
        @Override public boolean alive(){return alive.get();}
        void emit(String text){
            output.accept(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        void failOutput(){failure.accept(new IllegalStateException("simulated fatal output failure"));}
        void allowTermination(){mayStop.set(true);}
    }

    private static final class ConcurrentStopFixture{
        private final int count;private final CountDownLatch allStopCallsEntered;
        private final CopyOnWriteArrayList<GateStopPty> processes=new CopyOnWriteArrayList<>();
        private final AgentExecutionService service;
        private ConcurrentStopFixture(int count){
            this.count=count;allStopCallsEntered=new CountDownLatch(count);
            AtomicInteger pid=new AtomicInteger(200);
            service=service(new RecordingRepository(),new RecordingCredentials(),request->{
                GateStopPty process=new GateStopPty(pid.incrementAndGet(),allStopCallsEntered);
                processes.add(process);return process;
            },new RunCapacityBudget(count,count));
        }
        private List<AgentRunView> startShellRuns(){
            java.util.ArrayList<AgentRunView> result=new java.util.ArrayList<>();
            for(int index=0;index<count;index++)result.add(service.start(
                    new StartAgentCommand(WORKSPACE,WORKSPACE+":shell","4010")));
            return result;
        }
    }

    private static final class GateStopPty implements PseudoTerminalHandle{
        private final long pid;private final CountDownLatch allEntered;
        private final AtomicBoolean alive=new AtomicBoolean(true);
        private GateStopPty(long pid,CountDownLatch allEntered){this.pid=pid;this.allEntered=allEntered;}
        @Override public long pid(){return pid;}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){}
        @Override public void write(byte[] input){}
        @Override public void resize(int columns,int rows){}
        @Override public void pauseOutput(){}
        @Override public void resumeOutput(){}
        @Override public void stop(){stopAndConfirm();}
        @Override public boolean stopAndConfirm(){
            allEntered.countDown();
            try{
                if(!allEntered.await(2,TimeUnit.SECONDS))return false;
            }catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();return false;
            }
            alive.set(false);return true;
        }
        @Override public boolean alive(){return alive.get();}
    }

    private static final class RecordingCredentials implements AgentCredentialIssuer{
        private final AtomicBoolean revoked=new AtomicBoolean();
        private final AtomicInteger revokeCount=new AtomicInteger();
        @Override public String issue(String agentId){revoked.set(false);return "token:"+agentId;}
        @Override public void revoke(String agentId,String token){revoked.set(true);revokeCount.incrementAndGet();}
    }

    private static final class RecordingRepository implements AgentExecutionRepository{
        private final AgentLaunchConfiguration configuration=new AgentLaunchConfiguration(
                PTY_FIXTURE.command(),PTY_FIXTURE.arguments(),"cat",null,false,null,null);
        private final AtomicBoolean rejectNextInsert=new AtomicBoolean();
        @Override public boolean saveConfiguration(String workspaceId,String agentId,
                                                   AgentLaunchConfiguration configuration,Instant at){return true;}
        @Override public Optional<AgentLaunchConfiguration> copyConfigurationSnapshot(
                String workspaceId,String sourceAgentId,String targetAgentId,Long expectedSourceRevision,
                Instant at){return Optional.empty();}
        @Override public Optional<AgentLaunchConfiguration> findConfiguration(String workspaceId,String agentId){return Optional.of(configuration);}
        @Override public boolean insertRun(String runId,String workspaceId,String agentId,long pid,
                                           RunStatus status,Instant startedAt){return !rejectNextInsert.compareAndSet(true,false);}
        @Override public boolean markRunning(String runId,Instant at){return true;}
        @Override public boolean finishRun(String runId,RunStatus status,Integer exitCode,Instant endedAt,
                                           String workspaceId,String agentId,String failedResumeSessionId){return true;}
        @Override public void markUnfinishedRunsStale(Instant at){}
        @Override public Optional<String> findLastSession(String workspaceId,String agentId){return Optional.empty();}
        @Override public boolean saveLastSession(String workspaceId,String agentId,String runId,String sessionId,Instant at){return true;}
        @Override public void clearLastSession(String workspaceId,String agentId){}
    }
}
