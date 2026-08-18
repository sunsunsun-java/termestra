package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.in.AgentRunView;
import dev.termestra.execution.application.port.in.ConfigureAgentCommand;
import dev.termestra.execution.application.port.in.MessageDeliveryResult;
import dev.termestra.execution.application.port.in.StartAgentCommand;
import dev.termestra.execution.application.exception.RunNotFound;
import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.execution.application.port.out.AgentCredentialIssuer;
import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentExecutionRepository;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider;
import dev.termestra.execution.application.port.out.AgentSessionCapture;
import dev.termestra.execution.application.port.out.PseudoTerminalHandle;
import dev.termestra.execution.application.port.out.PseudoTerminalLauncher;
import dev.termestra.execution.application.port.out.ProcessLaunchRequest;
import dev.termestra.execution.domain.model.AgentLaunchConfiguration;
import dev.termestra.execution.domain.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionServiceConcurrencyTest {
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String AGENT_ID = "worker-1";

    @Test void sessionCapturePollingUsesABoundedExponentialBackoff() {
        long delay = AgentExecutionService.SESSION_CAPTURE_INITIAL_DELAY_MILLIS;
        long elapsed = 0;
        int scans = 1;
        List<Long> firstDelays = new java.util.ArrayList<>();
        while (elapsed < 30_000) {
            if (firstDelays.size() < 5) firstDelays.add(delay);
            elapsed += delay;
            delay = AgentExecutionService.nextSessionCaptureDelay(delay);
            scans++;
        }

        assertEquals(List.of(200L, 400L, 800L, 1_000L, 1_000L), firstDelays);
        assertEquals(AgentExecutionService.SESSION_CAPTURE_MAX_DELAY_MILLIS, delay);
        assertTrue(scans <= 35, "a 30 second capture must not perform the former ~300 filesystem scans");
    }

    @Test void concurrentStartsForTheSameAgentReuseOneLiveRun() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        BlockingFirstLaunch launcher = new BlockingFirstLaunch();
        AgentDescriptor agent = new AgentDescriptor(
                WORKSPACE_ID, "Workspace", "/tmp", AGENT_ID, "Worker", "Implement tasks", "coder");
        AgentExecutionService service = new AgentExecutionService(
                repository,
                (workspaceId, agentId) -> workspaceId.equals(WORKSPACE_ID) && agentId.equals(AGENT_ID)
                        ? Optional.of(agent) : Optional.empty(),
                credentials(),
                launcher,
                noSessionCapture(),
                (presetId, command) -> List.of(),
                noRecovery(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));
        ExecutorService starts = Executors.newFixedThreadPool(2);

        try {
            StartAgentCommand command = new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010");
            Future<AgentRunView> first = starts.submit(() -> service.start(command));
            assertTrue(launcher.firstLaunchEntered.await(5, TimeUnit.SECONDS));

            Future<AgentRunView> second = starts.submit(() -> service.start(command));
            boolean duplicateLaunchEntered = launcher.secondLaunchEntered.await(500, TimeUnit.MILLISECONDS);
            launcher.releaseFirstLaunch.countDown();

            AgentRunView firstRun = first.get(5, TimeUnit.SECONDS);
            AgentRunView secondRun = second.get(5, TimeUnit.SECONDS);
            assertFalse(duplicateLaunchEntered, "a concurrent Start must wait for the first Start result");
            assertEquals(firstRun.runId(), secondRun.runId());
            assertEquals(1, launcher.launchCount.get());
            assertEquals(List.of(firstRun.runId()), repository.insertedRunIds);
            assertEquals(1, service.listActiveSummaries(WORKSPACE_ID).size());
        } finally {
            launcher.releaseFirstLaunch.countDown();
            starts.shutdownNow();
            service.close();
        }
    }

    @Test void configureAndStartUsesOneAgentCriticalSection() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        repository.blockFirstConfiguration = true;
        AtomicReference<ProcessLaunchRequest> launched = new AtomicReference<>();
        AgentExecutionService service = service(repository, request -> {
            launched.set(request);
            return new TestPty(19);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ConfigureAgentCommand firstConfiguration = new ConfigureAgentCommand(
                    WORKSPACE_ID, AGENT_ID, "first-command", List.of(), null, null);
            ConfigureAgentCommand racingConfiguration = new ConfigureAgentCommand(
                    WORKSPACE_ID, AGENT_ID, "racing-command", List.of(), null, null);

            Future<AgentRunView> atomicStart = executor.submit(() -> service.configureAndStart(
                    firstConfiguration, new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010")));
            assertTrue(repository.firstConfigurationSaved.await(1, TimeUnit.SECONDS));
            Future<?> racingConfigure = executor.submit(() -> service.configure(racingConfiguration));
            boolean racingWriteEntered = repository.racingConfigurationSaved.await(200, TimeUnit.MILLISECONDS);

            repository.releaseFirstConfiguration.countDown();
            atomicStart.get(2, TimeUnit.SECONDS);
            racingConfigure.get(2, TimeUnit.SECONDS);

            assertFalse(racingWriteEntered,
                    "a concurrent configure must not replace the launch configuration before start reads it");
            assertEquals("first-command", launched.get().command().getFirst());
        } finally {
            repository.releaseFirstConfiguration.countDown();
            service.close();
        }
    }

    @Test void configureAndStartRejectsDifferentAgentTargetsBeforeMutation() {
        RecordingRepository repository = new RecordingRepository();
        AgentExecutionService service = service(repository, ignored -> new TestPty(18));

        try {
            ConfigureAgentCommand configuration = new ConfigureAgentCommand(
                    WORKSPACE_ID, AGENT_ID, "first-command", List.of(), null, null);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.configureAndStart(configuration,
                            new StartAgentCommand(WORKSPACE_ID, "different-agent", "4010")));

            assertTrue(error.getMessage().contains("same workspace agent"));
            assertEquals("cat", repository.configuration.command());
            assertTrue(repository.insertedRunIds.isEmpty());
        } finally {
            service.close();
        }
    }

    @Test void sameAgentStartIsRejectedUntilConcurrentExitCommitsItsTerminalState()
            throws Exception{
        RecordingRepository repository=new RecordingRepository();
        repository.blockFinish=true;
        CopyOnWriteArrayList<TestPty> processes=new CopyOnWriteArrayList<>();
        AgentExecutionService service=service(repository,ignored->{
            TestPty process=new TestPty(20+processes.size());processes.add(process);return process;
        });
        ExecutorService requests=Executors.newFixedThreadPool(2);
        try{
            AgentRunView first=service.start(new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010"));
            processes.getFirst().emitOutput("running");

            Future<?> exit=requests.submit(()->processes.getFirst().exit(0));
            assertTrue(repository.finishEntered.await(2,TimeUnit.SECONDS));
            ExecutionConflict pending=assertThrows(ExecutionConflict.class,()->service.start(
                    new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010")));
            assertTrue(pending.getMessage().contains("awaiting durable persistence"));
            assertEquals(1,processes.size());

            repository.releaseFinish.countDown();
            exit.get(2,TimeUnit.SECONDS);
            AgentRunView second=service.start(
                    new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010"));
            assertFalse(first.runId().equals(second.runId()));
            assertEquals(2,processes.size());
        }finally{
            repository.releaseFinish.countDown();
            requests.shutdownNow();
            service.close();
        }
    }

    @Test void interactiveDeliveriesAreFifoRequireFreshPromptsAndExcludeManualWrites() throws Exception {
        RecordingRepository repository = new RecordingRepository("hermes");
        PromptingPtyLauncher launcher = new PromptingPtyLauncher();
        AgentExecutionService service = service(repository, launcher);
        ExecutorService requests = Executors.newFixedThreadPool(3);

        try {
            AgentRunView run = service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010"));
            assertTrue(launcher.pty.hasSubmittedEnter(), "startup delivery must include Enter before start returns");

            Future<MessageDeliveryResult> first = requests.submit(() -> service.deliver(
                    WORKSPACE_ID, AGENT_ID, "dispatch-one", "orchestrator", "worker", "first task", "4010"));
            Thread.sleep(100);
            assertFalse(first.isDone(), "the first task must wait for a prompt newer than startup submission");

            launcher.pty.blockNextEnter();
            launcher.pty.emitPrompt();
            assertTrue(launcher.pty.awaitWriteContaining("dispatch-one", 2, TimeUnit.SECONDS));
            assertTrue(launcher.pty.awaitBlockedEnter(2, TimeUnit.SECONDS));
            Future<MessageDeliveryResult> second = requests.submit(() -> service.deliver(
                    WORKSPACE_ID, AGENT_ID, "dispatch-two", "orchestrator", "worker", "second task", "4010"));
            Future<?> manual = requests.submit(() -> service.write(
                    run.runId(), "MANUAL".getBytes(StandardCharsets.UTF_8)));

            Thread.sleep(100);
            assertFalse(first.isDone(), "delivery must not report success before Enter is written");
            assertFalse(manual.isDone(), "manual input must not interleave between automatic body and Enter");

            launcher.pty.releaseBlockedEnter();
            assertTrue(first.get(3, TimeUnit.SECONDS).delivered());
            manual.get(1, TimeUnit.SECONDS);
            assertFalse(second.isDone(), "the next queued task must wait for the next fresh prompt");

            launcher.pty.emitPrompt();
            assertTrue(second.get(3, TimeUnit.SECONDS).delivered());

            List<String> writes = launcher.pty.writes();
            int firstBody = indexContaining(writes, "dispatch-one");
            int firstEnter = indexOf(writes, "\r", firstBody + 1);
            int manualWrite = indexOf(writes, "MANUAL", firstBody + 1);
            int secondBody = indexContaining(writes, "dispatch-two");
            assertTrue(firstBody >= 0 && firstEnter > firstBody);
            assertTrue(manualWrite > firstEnter, "manual input must follow the complete first submission");
            assertTrue(secondBody > manualWrite, "queued automatic deliveries must preserve arrival order");
        } finally {
            requests.shutdownNow();
            service.close();
        }
    }

    @Test void runExitFailsPromptWaitWithoutWaitingForTheHardTimeout() throws Exception {
        RecordingRepository repository = new RecordingRepository("hermes");
        PromptingPtyLauncher launcher = new PromptingPtyLauncher();
        AgentExecutionService service = service(repository, launcher);
        ExecutorService requests = Executors.newSingleThreadExecutor();

        try {
            AgentRunView run = service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010"));
            Future<MessageDeliveryResult> waiting = requests.submit(() -> service.deliver(
                    WORKSPACE_ID, AGENT_ID, "dispatch-after-exit", "orchestrator", "worker", "task", "4010"));
            Thread.sleep(100);
            assertFalse(waiting.isDone());

            long stoppedAt = System.nanoTime();
            service.stop(run.runId());
            MessageDeliveryResult result = waiting.get(1, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stoppedAt);

            assertFalse(result.delivered());
            assertFalse(result.inputAttempted());
            assertTrue(elapsedMillis < 1_000, "PTY exit should wake prompt waiting promptly");
        } finally {
            requests.shutdownNow();
            service.close();
        }
    }

    @Test void stopKillsTheProcessBeforeWaitingForAWriterThatOwnsTheAgentCoordinator()
            throws Exception{
        RecordingRepository repository=new RecordingRepository("hermes");
        BlockingWritePtyLauncher launcher=new BlockingWritePtyLauncher();
        AgentExecutionService service=service(repository,launcher);
        ExecutorService requests=Executors.newFixedThreadPool(3);
        try{
            AgentRunView run=service.start(new StartAgentCommand(
                    WORKSPACE_ID,AGENT_ID,"4010"));
            launcher.pty.blockNextWrite();
            Future<?> manual=requests.submit(()->service.write(
                    run.runId(),"blocked-manual-input".getBytes(StandardCharsets.UTF_8)));
            assertTrue(launcher.pty.awaitBlockedWrite(2,TimeUnit.SECONDS));

            Future<MessageDeliveryResult> automatic=requests.submit(()->service.deliver(
                    WORKSPACE_ID,AGENT_ID,"dispatch-during-write","orchestrator","worker",
                    "queued task","4010"));
            Thread.sleep(100);
            assertFalse(automatic.isDone(),
                    "the automatic delivery should be waiting behind the manual PTY writer");

            Future<?> stop=requests.submit(()->service.stop(run.runId()));

            assertTrue(launcher.pty.awaitStop(1,TimeUnit.SECONDS),
                    "stop must terminate the PTY before waiting for the agent coordinator");
            stop.get(2,TimeUnit.SECONDS);
            ExecutionException manualFailure=assertThrows(ExecutionException.class,
                    ()->manual.get(2,TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,manualFailure.getCause());
            assertFalse(automatic.get(2,TimeUnit.SECONDS).delivered());
            assertEquals("error",service.get(run.runId()).status());
        }finally{
            requests.shutdownNow();
            service.close();
        }
    }

    @Test void startupInputFailureIsNotReportedAsAttemptedDispatchAndDoesNotLeakRun() {
        RecordingRepository repository = new RecordingRepository("hermes");
        FailingStartupPtyLauncher launcher = new FailingStartupPtyLauncher();
        AgentExecutionService service = service(repository, launcher);

        try {
            MessageDeliveryResult result = service.deliver(
                    WORKSPACE_ID, AGENT_ID, "never-written", "orchestrator", "worker", "task", "4010");

            assertFalse(result.delivered());
            assertFalse(result.inputAttempted(), "startup bytes are not an attempt to deliver the task body");
            assertFalse(launcher.pty.alive());
            assertTrue(service.listActiveSummaries(WORKSPACE_ID).isEmpty());
        } finally {
            service.close();
        }
    }

    @Test void runningPersistenceFailureStopsThePtyAndNeverLeavesAPublicActiveRun() {
        RecordingRepository repository = new RecordingRepository();
        repository.failMarkRunning = true;
        TestPty pty = new TestPty(71);
        AgentExecutionService service = service(repository, ignored -> pty);

        try {
            AgentRunView run = service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010"));

            assertDoesNotThrow(() -> pty.emitOutput("started"));

            assertFalse(pty.alive());
            assertEquals("error", service.get(run.runId()).status());
            assertTrue(service.listActiveSummaries(WORKSPACE_ID).isEmpty());
        } finally {
            service.close();
        }
    }

    @Test void exitPersistenceFailureKeepsTheProjectionUnchangedUntilABoundedRetrySucceeds() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        TestPty pty = new TestPty(72);
        AgentExecutionService service = service(repository, ignored -> pty);

        try {
            AgentRunView run = service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010"));
            pty.emitOutput("started");
            repository.failFinish = true;

            assertDoesNotThrow(() -> pty.exit(0));

            assertEquals("running", service.get(run.runId()).status(),
                    "an uncommitted terminal status must not be published from memory");
            assertEquals("error",service.getSummary(run.runId()).status(),
                    "the terminal transport must observe the physical exit while persistence retries");
            assertTrue(service.listActiveSummaries(WORKSPACE_ID).isEmpty());
            assertFalse(pty.alive());
            assertThrows(ExecutionConflict.class,()->service.start(
                    new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010")),
                    "a new session must not race an older terminal transition");

            repository.failFinish = false;
            awaitStatus(service, run.runId(), "exited");
            assertTrue(repository.finishAttempts.get() >= 2);
        } finally {
            service.close();
        }
    }

    @Test void outputHandoffDoesNotDuplicateSnapshotTextOrSplitUtf8Characters() {
        RecordingRepository repository = new RecordingRepository();
        TestPty pty = new TestPty(73);
        AgentExecutionService service = service(repository, ignored -> pty);

        try {
            AgentRunView run = service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010"));
            pty.emitOutput("snapshot:");
            byte[] glyph = "你".getBytes(StandardCharsets.UTF_8);
            pty.emitOutput(java.util.Arrays.copyOfRange(glyph, 0, 2));
            List<String> streamed = new CopyOnWriteArrayList<>();

            var session = service.open(run.runId(), streamed::add);
            pty.emitOutput(java.util.Arrays.copyOfRange(glyph, 2, glyph.length));

            assertEquals("snapshot:", session.snapshot());
            assertEquals(List.of("你"), streamed);
            assertEquals("snapshot:你", service.get(run.runId()).output());
            session.subscription().close();
        } finally {
            service.close();
        }
    }

    @Test void forgettingARunDoesNotRemoveItsProjectionBeforeTheTerminalWriteCommits() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        TestPty pty = new TestPty(74);
        AgentExecutionService service = service(repository, ignored -> pty);

        try {
            AgentRunView run = service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010"));
            pty.emitOutput("started");
            repository.failFinish = true;

            assertThrows(IllegalStateException.class, () -> service.forgetRun(run.runId()));
            assertEquals("running", service.get(run.runId()).status());
            assertTrue(service.listActiveSummaries(WORKSPACE_ID).isEmpty());

            repository.failFinish = false;
            awaitMissing(service, run.runId());
        } finally {
            service.close();
        }
    }

    @Test void activationFailureCleansTheFailedRunAndReleasesItsCapacity() {
        RecordingRepository repository = new RecordingRepository();
        AtomicInteger attempts = new AtomicInteger();
        dev.termestra.execution.application.port.out.PseudoTerminalLauncher launcher = ignored -> {
            if (attempts.incrementAndGet() == 1) return new ActivationFailurePty();
            return new TestPty(81);
        };
        AgentDescriptor agent = new AgentDescriptor(
                WORKSPACE_ID, "Workspace", "/tmp", AGENT_ID, "Worker", "Implement tasks", "coder");
        AgentExecutionService service = new AgentExecutionService(
                repository,
                (workspaceId, agentId) -> Optional.of(agent),
                credentials(), launcher, noSessionCapture(), (presetId, command) -> List.of(), noRecovery(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
                new RunCapacityBudget(1, 1));

        try {
            assertThrows(IllegalStateException.class,
                    () -> service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010")));
            assertTrue(service.listActiveSummaries(WORKSPACE_ID).isEmpty());
            assertDoesNotThrow(() -> service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010")));
        } finally {
            service.close();
        }
    }

    @Test void closeRejectsAllSubsequentStarts() {
        RecordingRepository repository=new RecordingRepository();
        AtomicInteger launches=new AtomicInteger();
        AgentExecutionService service=service(repository,ignored->{launches.incrementAndGet();return new TestPty(82);});

        service.close();

        assertThrows(ExecutionConflict.class,()->service.start(
                new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010")));
        assertEquals(0,launches.get());
    }

    @Test void durablyMissingTerminalRunIsDiscardedAndReleasesCapacity() {
        RecordingRepository repository=new RecordingRepository();
        List<TestPty> processes=new CopyOnWriteArrayList<>();
        AgentDescriptor agent=new AgentDescriptor(
                WORKSPACE_ID,"Workspace","/tmp",AGENT_ID,"Worker","Implement tasks","coder");
        AgentExecutionService service=new AgentExecutionService(repository,
                (workspaceId,agentId)->Optional.of(agent),credentials(),ignored->{
                    TestPty pty=new TestPty(90+processes.size());processes.add(pty);return pty;
                },noSessionCapture(),(presetId,command)->List.of(),noRecovery(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"),ZoneOffset.UTC),
                new RunCapacityBudget(1,1));

        try{
            AgentRunView first=service.start(new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010"));
            processes.getFirst().emitOutput("started");
            repository.finishMissing=true;
            processes.getFirst().exit(0);

            assertThrows(RunNotFound.class,()->service.get(first.runId()));
            repository.finishMissing=false;
            assertDoesNotThrow(()->service.start(new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010")));
            assertEquals(2,processes.size());
        }finally{service.close();}
    }

    @Test void closeCancelsPendingRetriesAndDropsTheirRuntimeState() {
        RecordingRepository repository=new RecordingRepository();
        TestPty pty=new TestPty(84);
        AgentExecutionService service=service(repository,ignored->pty);
        AgentRunView run=service.start(new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010"));
        pty.emitOutput("started");
        repository.failFinish=true;
        pty.exit(0);

        service.close();

        assertThrows(RunNotFound.class,()->service.get(run.runId()));
    }

    @Test void cleanupFailureCannotSkipTheDurableTerminalTransition() {
        RecordingRepository repository=new RecordingRepository();
        TestPty pty=new TestPty(83);
        AgentDescriptor agent=new AgentDescriptor(
                WORKSPACE_ID,"Workspace","/tmp",AGENT_ID,"Worker","Implement tasks","coder");
        AgentSessionCapture failingCleanup=new AgentSessionCapture(){
            @Override public Optional<CaptureSnapshot> snapshot(AgentDescriptor ignored,String captureJson){return Optional.empty();}
            @Override public Optional<String> findNew(CaptureSnapshot snapshot){return Optional.empty();}
            @Override public void releaseClaims(String claimantId){throw new IllegalStateException("claim store unavailable");}
            @Override public boolean exists(AgentDescriptor ignored,String captureJson,String sessionId){return false;}
        };
        AgentExecutionService service=new AgentExecutionService(repository,
                (workspaceId,agentId)->Optional.of(agent),credentials(),ignored->pty,failingCleanup,
                (presetId,command)->List.of(),noRecovery(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"),ZoneOffset.UTC));

        try{
            AgentRunView run=service.start(new StartAgentCommand(WORKSPACE_ID,AGENT_ID,"4010"));
            pty.emitOutput("started");
            assertDoesNotThrow(()->pty.exit(0));
            assertEquals("exited",service.get(run.runId()).status());
            assertEquals(1,repository.finishAttempts.get());
        }finally{service.close();}
    }

    private static AgentExecutionService service(RecordingRepository repository,
                                                  dev.termestra.execution.application.port.out.PseudoTerminalLauncher launcher) {
        AgentDescriptor agent = new AgentDescriptor(
                WORKSPACE_ID, "Workspace", "/tmp", AGENT_ID, "Worker", "Implement tasks", "coder");
        return new AgentExecutionService(
                repository,
                (workspaceId, agentId) -> workspaceId.equals(WORKSPACE_ID) && agentId.equals(AGENT_ID)
                        ? Optional.of(agent) : Optional.empty(),
                credentials(), launcher, noSessionCapture(), (presetId, command) -> List.of(), noRecovery(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));
    }

    private static int indexContaining(List<String> values, String expected) {
        for (int index = 0; index < values.size(); index++) if (values.get(index).contains(expected)) return index;
        return -1;
    }

    private static int indexOf(List<String> values, String expected, int start) {
        for (int index = Math.max(0, start); index < values.size(); index++) if (values.get(index).equals(expected)) return index;
        return -1;
    }

    private static void awaitStatus(AgentExecutionService service, String runId, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (expected.equals(service.get(runId).status())) return;
            Thread.sleep(10);
        }
        assertEquals(expected, service.get(runId).status());
    }

    private static void awaitMissing(AgentExecutionService service, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            try {
                service.get(runId);
            } catch (RunNotFound expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThrows(RunNotFound.class, () -> service.get(runId));
    }

    private static AgentCredentialIssuer credentials() {
        return new AgentCredentialIssuer() {
            @Override public String issue(String agentId) { return "token-for-" + agentId; }
            @Override public void revoke(String agentId, String token) { }
        };
    }

    private static AgentSessionCapture noSessionCapture() {
        return new AgentSessionCapture() {
            @Override public Optional<CaptureSnapshot> snapshot(AgentDescriptor agent, String captureJson) {
                return Optional.empty();
            }
            @Override public Optional<String> findNew(CaptureSnapshot snapshot) { return Optional.empty(); }
            @Override public boolean exists(AgentDescriptor agent, String captureJson, String sessionId) { return false; }
        };
    }

    private static AgentRecoveryContextProvider noRecovery() {
        return new AgentRecoveryContextProvider() {
            @Override public boolean hasPreviousRun(String agentId, String currentRunId) { return false; }
            @Override public RecoveryContext load(String workspaceId, Instant recentSince) {
                return new RecoveryContext("", List.of(), List.of(), List.of());
            }
            @Override public long appendSystemRecoveryMessage(String workspaceId, String agentId, String text, Instant at) { return 1; }
            @Override public long appendUserInput(String workspaceId, String agentId, String text, Instant at) { return 1; }
            @Override public void deleteMessage(long sequence) { }
        };
    }

    private static final class BlockingFirstLaunch implements dev.termestra.execution.application.port.out.PseudoTerminalLauncher {
        private final AtomicInteger launchCount = new AtomicInteger();
        private final CountDownLatch firstLaunchEntered = new CountDownLatch(1);
        private final CountDownLatch secondLaunchEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstLaunch = new CountDownLatch(1);

        @Override public PseudoTerminalHandle start(ProcessLaunchRequest request) {
            int attempt = launchCount.incrementAndGet();
            if (attempt == 1) {
                firstLaunchEntered.countDown();
                await(releaseFirstLaunch);
            } else {
                secondLaunchEntered.countDown();
            }
            return new TestPty(attempt);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting to release PTY launch");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("PTY launch interrupted", interrupted);
            }
        }
    }

    private static final class TestPty implements PseudoTerminalHandle {
        private final long pid;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private volatile IntConsumer exitListener;
        private volatile Consumer<byte[]> outputListener;

        private TestPty(long pid) { this.pid = pid; }
        @Override public long pid() { return pid; }
        @Override public void activate(Consumer<byte[]> output, IntConsumer exit) { outputListener = output; exitListener = exit; }
        @Override public void write(byte[] input) { }
        @Override public void resize(int columns, int rows) { }
        @Override public void pauseOutput() { }
        @Override public void resumeOutput() { }
        @Override public void stop() {
            if (alive.compareAndSet(true, false) && exitListener != null) exitListener.accept(0);
        }
        @Override public boolean alive() { return alive.get(); }
        private void emitOutput(String value) { outputListener.accept(value.getBytes(StandardCharsets.UTF_8)); }
        private void emitOutput(byte[] value) { outputListener.accept(value); }
        private void exit(int code) { if (alive.compareAndSet(true, false)) exitListener.accept(code); }
    }

    private static final class ActivationFailurePty implements PseudoTerminalHandle {
        private final AtomicBoolean alive = new AtomicBoolean(true);
        @Override public long pid() { return 80; }
        @Override public void activate(Consumer<byte[]> output, IntConsumer exit) {
            throw new IllegalStateException("simulated activation failure");
        }
        @Override public void write(byte[] input) { }
        @Override public void resize(int columns, int rows) { }
        @Override public void pauseOutput() { }
        @Override public void resumeOutput() { }
        @Override public void stop() { alive.set(false); }
        @Override public boolean alive() { return alive.get(); }
    }

    private static final class PromptingPtyLauncher implements dev.termestra.execution.application.port.out.PseudoTerminalLauncher {
        private final PromptingPty pty = new PromptingPty();
        @Override public PseudoTerminalHandle start(ProcessLaunchRequest request) { return pty; }
    }

    private static final class BlockingWritePtyLauncher implements PseudoTerminalLauncher{
        private final BlockingWritePty pty=new BlockingWritePty();
        @Override public PseudoTerminalHandle start(ProcessLaunchRequest request){return pty;}
    }

    private static final class BlockingWritePty implements PseudoTerminalHandle{
        private final AtomicBoolean alive=new AtomicBoolean(true);
        private final AtomicBoolean blockNextWrite=new AtomicBoolean();
        private final CountDownLatch blockedWrite=new CountDownLatch(1);
        private final CountDownLatch releaseWrite=new CountDownLatch(1);
        private final CountDownLatch stopEntered=new CountDownLatch(1);
        private volatile Consumer<byte[]> output;
        private volatile IntConsumer exit;
        @Override public long pid(){return 44;}
        @Override public void activate(Consumer<byte[]> output,IntConsumer exit){
            this.output=output;this.exit=exit;
            output.accept("Welcome to Hermes Agent!\n❯\n".getBytes(StandardCharsets.UTF_8));
        }
        @Override public void write(byte[] input){
            if(blockNextWrite.compareAndSet(true,false)){
                blockedWrite.countDown();
                try{
                    if(!releaseWrite.await(3,TimeUnit.SECONDS))
                        throw new IllegalStateException("timed out waiting for PTY termination");
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("blocked PTY write interrupted",interrupted);
                }
            }
            if(!alive.get())throw new IllegalStateException("PTY is stopped");
            if(output!=null)output.accept("\n❯\n".getBytes(StandardCharsets.UTF_8));
        }
        @Override public void resize(int columns,int rows){}
        @Override public void pauseOutput(){}
        @Override public void resumeOutput(){}
        @Override public void stop(){
            stopEntered.countDown();
            alive.set(false);releaseWrite.countDown();
            IntConsumer listener=exit;if(listener!=null)listener.accept(143);
        }
        @Override public boolean alive(){return alive.get();}
        void blockNextWrite(){blockNextWrite.set(true);}
        boolean awaitBlockedWrite(long timeout,TimeUnit unit)throws InterruptedException{
            return blockedWrite.await(timeout,unit);
        }
        boolean awaitStop(long timeout,TimeUnit unit)throws InterruptedException{
            return stopEntered.await(timeout,unit);
        }
    }

    private static final class FailingStartupPtyLauncher implements dev.termestra.execution.application.port.out.PseudoTerminalLauncher {
        private final FailingStartupPty pty = new FailingStartupPty();
        @Override public PseudoTerminalHandle start(ProcessLaunchRequest request) { return pty; }
    }

    private static final class FailingStartupPty implements PseudoTerminalHandle {
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private volatile IntConsumer exitListener;

        @Override public long pid() { return 43; }
        @Override public void activate(Consumer<byte[]> output, IntConsumer exit) {
            exitListener = exit;
            output.accept("Welcome to Hermes Agent!\n❯\n".getBytes(StandardCharsets.UTF_8));
        }
        @Override public void write(byte[] input) { throw new IllegalStateException("simulated PTY write failure"); }
        @Override public void resize(int columns, int rows) { }
        @Override public void pauseOutput() { }
        @Override public void resumeOutput() { }
        @Override public void stop() {
            if (alive.compareAndSet(true, false) && exitListener != null) exitListener.accept(1);
        }
        @Override public boolean alive() { return alive.get(); }
    }

    private static final class PromptingPty implements PseudoTerminalHandle {
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CopyOnWriteArrayList<String> writes = new CopyOnWriteArrayList<>();
        private final AtomicBoolean blockNextEnter = new AtomicBoolean();
        private final CountDownLatch blockedEnter = new CountDownLatch(1);
        private final CountDownLatch releaseEnter = new CountDownLatch(1);
        private volatile Consumer<byte[]> outputListener;
        private volatile IntConsumer exitListener;

        @Override public long pid() { return 42; }
        @Override public void activate(Consumer<byte[]> output, IntConsumer exit) {
            outputListener = output;
            exitListener = exit;
            emitPrompt();
        }
        @Override public void write(byte[] input) {
            String value = new String(input, StandardCharsets.UTF_8);
            writes.add(value);
            if (value.contains("\u001b[200~") && outputListener != null) {
                outputListener.accept("[Pasted text #1: 5 lines → /tmp/paste.txt]".getBytes(StandardCharsets.UTF_8));
            }
            if ("\r".equals(value) && blockNextEnter.compareAndSet(true, false)) {
                blockedEnter.countDown();
                try {
                    if (!releaseEnter.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("timed out releasing Enter");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Enter write interrupted", interrupted);
                }
            }
        }
        @Override public void resize(int columns, int rows) { }
        @Override public void pauseOutput() { }
        @Override public void resumeOutput() { }
        @Override public void stop() {
            if (alive.compareAndSet(true, false) && exitListener != null) exitListener.accept(0);
        }
        @Override public boolean alive() { return alive.get(); }

        private void emitPrompt() {
            Consumer<byte[]> listener = outputListener;
            if (listener != null) listener.accept("Welcome to Hermes Agent!\n❯\n".getBytes(StandardCharsets.UTF_8));
        }
        private boolean hasSubmittedEnter() { return writes.contains("\r"); }
        private void blockNextEnter() { blockNextEnter.set(true); }
        private boolean awaitBlockedEnter(long timeout, TimeUnit unit) throws InterruptedException {
            return blockedEnter.await(timeout, unit);
        }
        private void releaseBlockedEnter() { releaseEnter.countDown(); }
        private List<String> writes() { return List.copyOf(writes); }
        private boolean awaitWriteContaining(String text, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (writes.stream().anyMatch(value -> value.contains(text))) return true;
                Thread.sleep(10);
            }
            return false;
        }
    }

    private static final class RecordingRepository implements AgentExecutionRepository {
        private volatile AgentLaunchConfiguration configuration;
        private final List<String> insertedRunIds = new CopyOnWriteArrayList<>();
        private final AtomicInteger finishAttempts = new AtomicInteger();
        private volatile boolean failMarkRunning;
        private volatile boolean failFinish;
        private volatile boolean finishMissing;
        private volatile boolean blockFinish;
        private final CountDownLatch finishEntered=new CountDownLatch(1);
        private final CountDownLatch releaseFinish=new CountDownLatch(1);
        private volatile boolean blockFirstConfiguration;
        private final CountDownLatch firstConfigurationSaved = new CountDownLatch(1);
        private final CountDownLatch releaseFirstConfiguration = new CountDownLatch(1);
        private final CountDownLatch racingConfigurationSaved = new CountDownLatch(1);

        private RecordingRepository() { this("cat"); }
        private RecordingRepository(String command) {
            configuration = new AgentLaunchConfiguration(command, List.of(), command, null, false, null, null);
        }

        @Override public boolean saveConfiguration(String workspaceId, String agentId,
                                                AgentLaunchConfiguration configuration, Instant at) {
            this.configuration = configuration;
            if (blockFirstConfiguration && "first-command".equals(configuration.command())) {
                firstConfigurationSaved.countDown();
                try {
                    if (!releaseFirstConfiguration.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release configuration persistence");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("configuration persistence interrupted", interrupted);
                }
            }
            if ("racing-command".equals(configuration.command())) racingConfigurationSaved.countDown();
            return true;
        }
        @Override public Optional<AgentLaunchConfiguration> findConfiguration(String workspaceId, String agentId) {
            return Optional.of(configuration);
        }
        @Override public boolean insertRun(String runId, String workspaceId, String agentId, long pid, RunStatus status, Instant startedAt) {
            insertedRunIds.add(runId);
            return true;
        }
        @Override public boolean markRunning(String runId, Instant at) {
            if (failMarkRunning) throw new IllegalStateException("database unavailable");
            return true;
        }
        @Override public boolean finishRun(String runId, RunStatus status, Integer exitCode, Instant endedAt,
                                           String workspaceId,String agentId,String failedResumeSessionId) {
            finishAttempts.incrementAndGet();
            if(blockFinish){
                finishEntered.countDown();
                try{
                    if(!releaseFinish.await(3,TimeUnit.SECONDS))throw new IllegalStateException(
                            "timed out waiting to release terminal persistence");
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("terminal persistence interrupted",interrupted);
                }
            }
            if (failFinish) throw new IllegalStateException("database unavailable");
            return !finishMissing;
        }
        @Override public void markUnfinishedRunsStale(Instant at) { }
        @Override public Optional<String> findLastSession(String workspaceId, String agentId) { return Optional.empty(); }
        @Override public boolean saveLastSession(String workspaceId, String agentId, String runId,
                                                 String sessionId, Instant at) { return true; }
        @Override public void clearLastSession(String workspaceId, String agentId) { }
    }
}
