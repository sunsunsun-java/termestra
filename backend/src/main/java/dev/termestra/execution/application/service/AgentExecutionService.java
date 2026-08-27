package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.*;
import dev.termestra.execution.application.port.in.*;
import dev.termestra.execution.application.port.out.*;
import dev.termestra.execution.domain.model.*;
import dev.termestra.shared.id.RunId;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

public final class AgentExecutionService implements AgentExecutionUseCase,AgentLaunchConfigurationQuery,AgentMessagingUseCase,RunOutputUseCase,AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(AgentExecutionService.class);
    private static final int MAX_OUTPUT=1_000_000;
    private static final int MAX_PENDING_OUTPUT_PUBLICATION_CHARACTERS=MAX_OUTPUT;
    private static final int MAX_COMPLETED_RUNS=16;
    private static final int MAX_ACTIVE_RUNS=128;
    private static final int MAX_ACTIVE_RUNS_PER_WORKSPACE=32;
    static final long SESSION_CAPTURE_INITIAL_DELAY_MILLIS=200;
    static final long SESSION_CAPTURE_MAX_DELAY_MILLIS=1_000;
    private static final Duration SESSION_CAPTURE_TIMEOUT=Duration.ofSeconds(30);
    private final AgentExecutionRepository repository; private final AgentDirectory directory; private final AgentCredentialIssuer credentials;
    private final PseudoTerminalLauncher launcher; private final AgentSessionCapture sessionCapture;private final CommandPresetPolicy presetPolicy;private final AgentRecoveryContextProvider recovery;private final Clock clock; private final ConcurrentHashMap<String,LiveRun> runs=new ConcurrentHashMap<>();
    private final RunOutputHub outputHub=new RunOutputHub();
    private final RunCapacityBudget runCapacity;
    private final RuntimeOperationCoordinator operations;
    private final TerminalTransitionRetrier terminalTransitions;
    private final ProcessTerminationSupervisor processTerminations;
    private final ReentrantReadWriteLock lifecycle=new ReentrantReadWriteLock(true);
    private final AtomicBoolean closed=new AtomicBoolean();

    public AgentExecutionService(AgentExecutionRepository repository,AgentDirectory directory,AgentCredentialIssuer credentials,
                                 PseudoTerminalLauncher launcher,AgentSessionCapture sessionCapture,CommandPresetPolicy presetPolicy,AgentRecoveryContextProvider recovery,Clock clock){this(repository,directory,credentials,launcher,sessionCapture,presetPolicy,recovery,clock,new RuntimeOperationCoordinator(),new RunCapacityBudget(MAX_ACTIVE_RUNS,MAX_ACTIVE_RUNS_PER_WORKSPACE));}
    public AgentExecutionService(AgentExecutionRepository repository,AgentDirectory directory,AgentCredentialIssuer credentials,
                                 PseudoTerminalLauncher launcher,AgentSessionCapture sessionCapture,CommandPresetPolicy presetPolicy,AgentRecoveryContextProvider recovery,Clock clock,RuntimeOperationCoordinator operations){this(repository,directory,credentials,launcher,sessionCapture,presetPolicy,recovery,clock,operations,new RunCapacityBudget(MAX_ACTIVE_RUNS,MAX_ACTIVE_RUNS_PER_WORKSPACE));}
    AgentExecutionService(AgentExecutionRepository repository,AgentDirectory directory,AgentCredentialIssuer credentials,
                          PseudoTerminalLauncher launcher,AgentSessionCapture sessionCapture,CommandPresetPolicy presetPolicy,AgentRecoveryContextProvider recovery,Clock clock,RunCapacityBudget runCapacity){this(repository,directory,credentials,launcher,sessionCapture,presetPolicy,recovery,clock,new RuntimeOperationCoordinator(),runCapacity);}
    AgentExecutionService(AgentExecutionRepository repository,AgentDirectory directory,AgentCredentialIssuer credentials,
                          PseudoTerminalLauncher launcher,AgentSessionCapture sessionCapture,CommandPresetPolicy presetPolicy,
                          AgentRecoveryContextProvider recovery,Clock clock,RuntimeOperationCoordinator operations,
                          RunCapacityBudget runCapacity){
        this(repository,directory,credentials,launcher,sessionCapture,presetPolicy,recovery,clock,operations,
                runCapacity,new ProcessTerminationSupervisor());
    }
    AgentExecutionService(AgentExecutionRepository repository,AgentDirectory directory,AgentCredentialIssuer credentials,
                          PseudoTerminalLauncher launcher,AgentSessionCapture sessionCapture,CommandPresetPolicy presetPolicy,
                          AgentRecoveryContextProvider recovery,Clock clock,RuntimeOperationCoordinator operations,
                          RunCapacityBudget runCapacity,ProcessTerminationSupervisor processTerminations){
        this.repository=repository;this.directory=directory;this.credentials=credentials;this.launcher=launcher;
        this.sessionCapture=sessionCapture;this.presetPolicy=presetPolicy;this.recovery=recovery;this.clock=clock;
        this.operations=operations;this.runCapacity=runCapacity;
        repository.markUnfinishedRunsStale(Instant.now(clock));
        this.terminalTransitions=new TerminalTransitionRetrier(repository);
        this.processTerminations=Objects.requireNonNull(processTerminations,"processTerminations");
    }

    @Override public void configure(ConfigureAgentCommand command){operations.withAgent(command.workspaceId(),command.agentId(),()->{requireAgent(command.workspaceId(),command.agentId());AgentLaunchConfiguration config=new AgentLaunchConfiguration(command.command(),command.arguments(),command.commandPresetId(),command.interactiveCommand(),command.presetAugmentationDisabled(),command.resumeArgsTemplate(),command.sessionIdCaptureJson(),command.environment());if(!repository.saveConfiguration(command.workspaceId(),command.agentId(),config,Instant.now(clock)))throw new ExecutionConflict("Agent no longer exists: "+command.agentId());});}

    @Override public AgentRunView configureAndStart(ConfigureAgentCommand configuration,
                                                     StartAgentCommand start) {
        requireSameAgent(configuration, start);
        return operations.withAgent(configuration.workspaceId(), configuration.agentId(), () -> {
            configure(configuration);
            return start(start);
        });
    }

    private static void requireSameAgent(ConfigureAgentCommand configuration,
                                         StartAgentCommand start) {
        if (!configuration.workspaceId().equals(start.workspaceId())
                || !configuration.agentId().equals(start.agentId())) {
            throw new IllegalArgumentException(
                    "Configuration and start must target the same workspace agent");
        }
    }

    @Override public Optional<AgentLaunchConfigurationView> find(String workspaceId,String agentId){return repository.findConfiguration(workspaceId,agentId).map(value->new AgentLaunchConfigurationView(value.command(),value.arguments(),value.commandPresetId(),value.interactiveCommand(),value.presetAugmentationDisabled(),value.resumeArgsTemplate(),value.sessionIdCaptureJson(),value.environment(),value.modelId(),value.revision()));}

    @Override public AgentRunView start(StartAgentCommand command){
        return operations.withAgent(command.workspaceId(),command.agentId(),()->startCoordinated(command));
    }

    private AgentRunView startCoordinated(StartAgentCommand command){
        lifecycle.readLock().lock();
        try{
            if(closed.get())throw new ExecutionConflict("Agent execution service is closed");
            AgentDescriptor agent=requireAgent(command.workspaceId(),command.agentId());
            boolean terminalPersistencePending=runs.values().stream().anyMatch(run->
                    run.agent.workspaceId().equals(command.workspaceId())
                            &&run.agent.agentId().equals(command.agentId())
                            &&run.pendingTerminalPersistence());
            if(terminalPersistencePending)throw new ExecutionConflict(
                    "Previous run terminal state is awaiting durable persistence: "+command.agentId());
            if(command.agentId().endsWith(":shell"))return startNewRun(command,agent);
            Optional<LiveRun> active=findActiveRun(command.workspaceId(),command.agentId());
            if(active.isPresent())return active.get().view();
            return startNewRun(command,agent);
        }finally{lifecycle.readLock().unlock();}
    }

    private AgentRunView startNewRun(StartAgentCommand command,AgentDescriptor agent){
        AgentLaunchConfiguration stored=repository.findConfiguration(command.workspaceId(),command.agentId()).orElseThrow(()->new ExecutionConflict("No agent launch config available"));
        Optional<AgentSessionCapture.CaptureSnapshot> capture=sessionCapture.snapshot(agent,stored.sessionIdCaptureJson());
        String resumedSession=repository.findLastSession(command.workspaceId(),command.agentId()).orElse(null);
        if(resumedSession!=null&&stored.resumeArgsTemplate()!=null&&!hasResumeArgs(stored.arguments())){boolean verify=stored.sessionIdCaptureJson()!=null&&(stored.sessionIdCaptureJson().contains("claude_project_jsonl_dir")||stored.sessionIdCaptureJson().contains("opencode_session_db"));if(verify&&!sessionCapture.exists(agent,stored.sessionIdCaptureJson(),resumedSession)){repository.clearLastSession(command.workspaceId(),command.agentId());resumedSession=null;}}
        List<String> yoloArguments=stored.presetAugmentationDisabled()?List.of():presetPolicy.yoloArguments(stored.commandPresetId(),stored.command());
        List<String> effectiveArguments=LaunchArguments.prependUnique(yoloArguments,stored.arguments());
        if(resumedSession!=null&&stored.resumeArgsTemplate()!=null&&!hasResumeArgs(effectiveArguments)){List<String> resumeArguments=new ArrayList<>(List.of(stored.resumeArgsTemplate().replace("{session_id}",resumedSession).trim().split("\\s+")));resumeArguments.addAll(stored.arguments());effectiveArguments=LaunchArguments.prependUnique(yoloArguments,resumeArguments);capture=Optional.empty();}
        AgentLaunchConfiguration config=new AgentLaunchConfiguration(stored.command(),effectiveArguments,stored.commandPresetId(),stored.interactiveCommand(),stored.presetAugmentationDisabled(),stored.resumeArgsTemplate(),stored.sessionIdCaptureJson(),stored.environment(),stored.modelId(),stored.revision());
        RunCapacityBudget.Lease capacity=runCapacity.reserve(command.workspaceId());
        String token=null;PseudoTerminalHandle process=null;LiveRun live=null;
        String runId=RunId.newId().toString();Instant started=Instant.now(clock);
        List<String> processCommand=new ArrayList<>();processCommand.add(config.command());processCommand.addAll(config.arguments());
        Map<String,String> env=new HashMap<>(config.environment());env.put("TERM","xterm-256color");env.put("COLORTERM","truecolor");env.put("FORCE_COLOR","1");
        env.put("TERM_PROGRAM","termestra");capture.ifPresent(value->env.putAll(value.environment()));env.put("TERMESTRA_PORT",Objects.requireNonNullElse(command.runtimePort(),""));
        env.put("TERMESTRA_WORKSPACE_ID",command.workspaceId());env.put("TERMESTRA_AGENT_ID",command.agentId());
        try{
            token=command.agentId().endsWith(":shell")
                    ?credentials.issueConcurrent(command.agentId()):credentials.issue(command.agentId());
            env.put("TERMESTRA_AGENT_TOKEN",token);
            process=launcher.start(new ProcessLaunchRequest(processCommand,agent.workspacePath(),env,80,24));
            if(!repository.insertRun(runId,command.workspaceId(),command.agentId(),process.pid(),RunStatus.STARTING,started))throw new ExecutionConflict("Agent no longer exists: "+command.agentId());
            String nativeResumedSession=stored.resumeArgsTemplate()==null?null:resumedSession;
            live=new LiveRun(runId,agent,config,process,started,nativeResumedSession,token,capacity);runs.put(runId,live);
            if(directory.find(command.workspaceId(),command.agentId()).isEmpty())throw new ExecutionConflict("Agent no longer exists: "+command.agentId());
            LiveRun activatedRun=live;
            process.activate(bytes->onOutput(activatedRun,bytes),
                    exitCode->onExit(activatedRun,exitCode),
                    failure->onPtyFailure(activatedRun,failure));
            if(!live.active()||!process.alive()){
                // A short-lived shell command is still a successfully created run. Its
                // exit callback owns the durable terminal transition; callers may
                // observe STARTING briefly and then the recorded EXITED/ERROR state.
                if("shell".equals(agent.role()))return live.view();
                throw new ExecutionConflict("Agent process exited during startup: "+command.agentId());
            }
            Optional<AgentSessionCapture.CaptureSnapshot> captureToStart=capture;
            captureToStart.ifPresent(value->captureSession(activatedRun,value));
            if(recovery.hasPreviousRun(agent.agentId(),runId)){if(nativeResumedSession==null)injectRecoverySummary(live);}else injectStartupInstructions(live);
            return live.view();
        }catch(InteractiveInputSubmitter.SubmissionException error){
            if(live!=null)abortFailedStart(live,error);else addCleanupFailure(
                    error,cleanupUnregistered(runId,agent.agentId(),token,process,capacity));
            throw new ExecutionConflict("Agent startup input failed: "+Objects.requireNonNullElse(error.getMessage(),error.getClass().getSimpleName()),error);
        }catch(RuntimeException error){
            if(live!=null)abortFailedStart(live,error);else addCleanupFailure(
                    error,cleanupUnregistered(runId,agent.agentId(),token,process,capacity));
            throw error;
        }
    }
    private RuntimeException cleanupUnregistered(String runId,String agentId,String token,
                                                  PseudoTerminalHandle process,
                                                  RunCapacityBudget.Lease capacity){
        Runnable cleanup=()->{if(token!=null)safeRevoke(agentId,token);capacity.close();};
        if(process==null){cleanup.run();return null;}
        return processTerminations.terminate("unregistered:"+runId,process::stopAndConfirm,cleanup);
    }
    private static void addCleanupFailure(RuntimeException root,RuntimeException cleanupFailure){
        if(cleanupFailure!=null&&cleanupFailure!=root)root.addSuppressed(cleanupFailure);
    }
    private void abortFailedStart(LiveRun run,RuntimeException root){RuntimeException persistenceFailure=transitionTerminal(run,RunStatus.ERROR,null,true);if(persistenceFailure!=null&&persistenceFailure!=root)root.addSuppressed(persistenceFailure);}
    private boolean hasResumeArgs(List<String> arguments){return arguments.stream().anyMatch(Set.of("--resume","-r","--continue","-c","--session","-s")::contains)||(!arguments.isEmpty()&&"resume".equals(arguments.getFirst()));}
    private void captureSession(LiveRun run,AgentSessionCapture.CaptureSnapshot snapshot){
        Thread captureThread=Thread.ofVirtual().name("termestra-session-capture-"+run.id).unstarted(()->{
            long started=System.nanoTime();long delayMillis=SESSION_CAPTURE_INITIAL_DELAY_MILLIS;boolean persisted=false;
            try{
                while(run.active()&&Duration.ofNanos(System.nanoTime()-started).compareTo(SESSION_CAPTURE_TIMEOUT)<0){
                    Optional<String> found=sessionCapture.claimNew(snapshot,run.id);
                    if(found.isPresent()){
                        if(!run.active())return;
                        persisted=repository.saveLastSession(run.agent.workspaceId(),run.agent.agentId(),
                                run.id,found.orElseThrow(),Instant.now(clock));
                        return;
                    }
                    long remainingMillis=Math.max(1,SESSION_CAPTURE_TIMEOUT.minusNanos(
                            Math.max(0,System.nanoTime()-started)).toMillis());
                    Thread.sleep(Math.min(delayMillis,remainingMillis));
                    delayMillis=nextSessionCaptureDelay(delayMillis);
                }
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(RuntimeException failure){LOG.warn("Session capture failed for run {}",run.id,failure);}
            finally{if(!persisted)sessionCapture.releaseClaims(run.id);}
        });
        run.captureThread=captureThread;captureThread.start();
    }

    static long nextSessionCaptureDelay(long currentDelayMillis){
        if(currentDelayMillis<SESSION_CAPTURE_INITIAL_DELAY_MILLIS)return SESSION_CAPTURE_INITIAL_DELAY_MILLIS;
        if(currentDelayMillis>=SESSION_CAPTURE_MAX_DELAY_MILLIS)return SESSION_CAPTURE_MAX_DELAY_MILLIS;
        return Math.min(SESSION_CAPTURE_MAX_DELAY_MILLIS,currentDelayMillis*2);
    }

    private void cancelSessionCapture(LiveRun run){Thread thread=run.captureThread;if(thread!=null&&thread!=Thread.currentThread()){thread.interrupt();try{thread.join(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}sessionCapture.releaseClaims(run.id);}

    private void injectStartupInstructions(LiveRun run){if(!InteractiveInputSubmitter.supports(inputCommand(run))||"shell".equals(run.agent.role()))return;deliverText(run,AgentStartupPrompt.build(run.agent));}
    private void injectRecoverySummary(LiveRun run){if("shell".equals(run.agent.role()))return;var context=recovery.load(run.agent.workspaceId(),Instant.now(clock).minus(Duration.ofHours(1)));String text=AgentRecoverySummary.build(run.agent,context);injectPersisted(run,text);}
    private void injectPersisted(LiveRun run,String text){try{PersistedMessageDelivery.execute(()->recovery.appendSystemRecoveryMessage(run.agent.workspaceId(),run.agent.agentId(),text,Instant.now(clock)),recovery::deleteMessage,()->deliverText(run,text));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}

    private void onOutput(LiveRun run,byte[] bytes){
        RuntimeException persistenceFailure=null;boolean drainOutput=false;
        synchronized(run){
            if(!run.acceptsOutput())return;
            if(run.status==RunStatus.STARTING){
                try{if(!repository.markRunning(run.id,Instant.now(clock)))persistenceFailure=new ExecutionConflict("Agent run disappeared before it could start: "+run.id);else run.status=RunStatus.RUNNING;}
                catch(RuntimeException failure){persistenceFailure=failure;}
            }
            if(persistenceFailure==null&&run.acceptsOutput()){
                String decoded=run.decoder.decode(bytes);
                drainOutput=appendOutputLocked(run,decoded);
            }
        }
        if(persistenceFailure!=null){LOG.error("Could not persist the running transition for run {}; stopping its PTY",run.id,persistenceFailure);RuntimeException finishFailure=transitionTerminal(run,RunStatus.ERROR,null,true);if(finishFailure!=null&&finishFailure!=persistenceFailure)persistenceFailure.addSuppressed(finishFailure);return;}
        if(drainOutput)drainOutput(run);
    }

    private boolean appendOutputLocked(LiveRun run,String decoded){
        if(decoded.isEmpty())return false;
        run.output.append(decoded);run.interactiveOutput.append(decoded);run.lastLine.write(decoded);
        run.publications.addLast(new OutputPublication(++run.outputSequence,decoded));
        run.pendingPublicationCharacters+=decoded.length();
        while(run.pendingPublicationCharacters>MAX_PENDING_OUTPUT_PUBLICATION_CHARACTERS
                &&run.publications.size()>1){
            run.pendingPublicationCharacters-=run.publications.removeFirst().text().length();
        }
        if(run.publishing)return false;
        run.publishing=true;
        return true;
    }

    private void flushFinalOutput(LiveRun run){
        boolean drain;
        synchronized(run){drain=appendOutputLocked(run,run.decoder.finish());}
        if(drain)drainOutput(run);
    }

    private void drainOutput(LiveRun run){
        while(true){
            OutputPublication publication;
            synchronized(run){
                publication=run.publications.pollFirst();
                if(publication==null){run.publishing=false;return;}
                run.pendingPublicationCharacters-=publication.text().length();
            }
            outputHub.publish(run.id,publication.sequence(),publication.text());
        }
    }
    private void onExit(LiveRun run,int exitCode){
        // An automatic delivery owns the agent coordinator while it waits for a prompt or writes
        // input. Wake that waiter before attempting to enter the same coordinator, otherwise a
        // natural exit can be delayed by the full prompt timeout (or a blocked PTY write).
        quiesceAutomaticInput(run);
        // The PTY adapter guarantees that its output reader has reached EOF before
        // publishing exit, so it is now safe to flush any incomplete UTF-8 tail.
        flushFinalOutput(run);
        RunStatus terminal=exitCode==0?RunStatus.EXITED:RunStatus.ERROR;
        RuntimeException failure=transitionTerminal(run,terminal,exitCode,false);
        if(failure!=null)LOG.error("Could not persist terminal state {} for run {}; the durable projection remains unchanged while a bounded retry is pending",terminal,run.id,failure);
    }

    private void onPtyFailure(LiveRun run,RuntimeException failure){
        LOG.error("Fatal PTY I/O failure for run {}; supervised termination is required",
                run.id,failure);
        // Do not enter the agent coordinator here. The failed I/O path may be the only operation
        // capable of killing a process whose blocked writer currently owns that coordinator.
        quiesceAutomaticInput(run);
        RuntimeException terminationFailure=transitionTerminal(run,RunStatus.ERROR,null,true);
        if(terminationFailure!=null){
            if(terminationFailure!=failure)failure.addSuppressed(terminationFailure);
            LOG.error("Run {} remains active until its PTY process tree can be confirmed stopped",
                    run.id,terminationFailure);
        }
    }

    private RuntimeException transitionTerminal(LiveRun run,RunStatus terminal,Integer exitCode,boolean stopProcess){
        if(!run.terminalTransition.compareAndSet(false,true)){
            synchronized(run){return run.terminalPersistenceFailure;}
        }
        Instant ended=Instant.now(clock);
        if(stopProcess){
            // Wake prompt-delivery waiters before stopping. This only quiesces input; credentials,
            // capture ownership and capacity remain held until process-tree termination is proven.
            quiesceAutomaticInput(run);
            run.terminationPending.set(true);
            RuntimeException terminationFailure=processTerminations.terminate(
                    run.id,run.process::stopAndConfirm,
                    ()->{
                        synchronized(run){run.terminationPending.set(false);}
                        continueAfterConfirmedTermination(run,terminal,exitCode,ended);
                    });
            if(terminationFailure!=null){
                synchronized(run){
                    // The native attempt can complete at the same instant its bounded caller
                    // times out. Its callback then owns the terminal transition, so do not
                    // overwrite that confirmed result with the stale wait failure.
                    if(run.terminationPending.get())run.terminalPersistenceFailure=terminationFailure;
                }
                // The caller reached its deadline, even if the native callback won the race
                // immediately afterwards. Preserve that explicit uncertainty for the caller.
                return terminationFailure;
            }
            synchronized(run){return run.terminalPersistenceFailure;}
        }
        return continueAfterConfirmedTermination(run,terminal,exitCode,ended);
    }

    private RuntimeException continueAfterConfirmedTermination(LiveRun run,RunStatus terminal,
                                                                 Integer exitCode,Instant ended){
        // stopAndConfirm() guarantees that no more PTY bytes can arrive. Flush the decoder here as
        // well as on natural exit because an explicit stop may win the exit-callback race.
        flushFinalOutput(run);
        if(run.durablyDeleted){discardDurablyMissingRun(run);return null;}
        cleanupRuntime(run);
        String failedResumeSession=exitCode!=null&&exitCode!=0?run.resumedSessionId:null;
        RuntimeException failure=terminalTransitions.persist(run.id,terminal,exitCode,ended,
                run.agent.workspaceId(),run.agent.agentId(),failedResumeSession,
                ()->completeTerminalTransition(run,terminal,exitCode,ended),
                ()->discardDurablyMissingRun(run),
                ()->discardAbandonedRun(run));
        if(failure!=null)synchronized(run){run.terminalPersistenceFailure=failure;}
        return failure;
    }

    private void completeTerminalTransition(LiveRun run,RunStatus terminal,Integer exitCode,Instant ended){
        boolean remove;
        synchronized(run){
            run.status=terminal;run.exitCode=exitCode;run.endedAt=ended;
            run.terminalPersistenceFailure=null;remove=run.removeWhenTerminal;
        }
        releaseCapacity(run);
        if(remove){runs.remove(run.id,run);outputHub.clear(run.id);}else retainRecentCompletedRuns();
    }

    private void discardDurablyMissingRun(LiveRun run){
        synchronized(run){run.terminalPersistenceFailure=null;}
        releaseCapacity(run);
        runs.remove(run.id,run);cleanupRuntime(run);outputHub.clear(run.id);
    }

    private void discardAbandonedRun(LiveRun run){
        releaseCapacity(run);
        runs.remove(run.id,run);cleanupRuntime(run);outputHub.clear(run.id);
    }

    private void cleanupRuntime(LiveRun run){
        if(!run.runtimeCleaned.compareAndSet(false,true))return;
        quiesceAutomaticInput(run);
        try{cancelSessionCapture(run);}catch(RuntimeException failure){LOG.warn("Could not release session capture for run {}",run.id,failure);}
        safeRevoke(run.agent.agentId(),run.token);
        try{outputHub.clear(run.id);}catch(RuntimeException failure){LOG.warn("Could not clear output subscribers for run {}",run.id,failure);}
    }
    private void quiesceAutomaticInput(LiveRun run){
        if(!run.inputQuiesced.compareAndSet(false,true))return;
        try{run.automaticInput.close();}
        catch(RuntimeException failure){LOG.warn("Could not close automatic input for run {}",run.id,failure);}
    }
    private void releaseCapacity(LiveRun run){if(run.capacityReleased.compareAndSet(false,true)){try{run.capacity.close();}catch(RuntimeException failure){LOG.warn("Could not release run capacity for {}",run.id,failure);}}}
    private void safeRevoke(String agentId,String token){try{credentials.revoke(agentId,token);}catch(RuntimeException failure){LOG.warn("Could not revoke runtime credential for agent {}",agentId,failure);}}

    @Override public void stop(String runId){
        LiveRun run=live(runId);
        // Process termination must precede the coordinator. An in-flight delivery can own the
        // coordinator while blocked inside a PTY write; killing the PTY is what releases that write.
        // terminalTransition is the per-run serialization point and startCoordinated rejects a
        // replacement while durable terminal persistence is pending.
        quiesceAutomaticInput(run);
        RuntimeException failure=transitionTerminal(run,RunStatus.ERROR,null,true);
        if(failure!=null)throw failure;
    }
    @Override public void write(String runId,byte[] input){
        LiveRun run=live(runId);boolean locked=false;
        try{
            run.ptyInputLock.lockInterruptibly();locked=true;
            if(!run.active())throw new ExecutionConflict("PTY is not active for run: "+runId);
            run.process.write(input);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            throw new ExecutionConflict("Interrupted while waiting to write PTY input: "+runId,
                    interrupted);
        }finally{if(locked)run.ptyInputLock.unlock();}
    }
    @Override public void resize(String runId,int columns,int rows){if(columns<=0||rows<=0)throw new IllegalArgumentException("terminal size must be positive");live(runId).process.resize(columns,rows);}
    @Override public void pauseOutput(String runId){LiveRun run=live(runId);if(run.active())run.process.pauseOutput();}
    @Override public void resumeOutput(String runId){LiveRun run=live(runId);if(run.active())run.process.resumeOutput();}
    @Override public AgentRunView get(String runId){return live(runId).view();}
    @Override public AgentRunSummaryView getSummary(String runId){return live(runId).summary();}
    @Override public List<AgentRunSummaryView> listActiveSummaries(String workspaceId){return runs.values().stream().filter(run->run.agent.workspaceId().equals(workspaceId)&&run.active()).sorted(Comparator.comparing(run->run.startedAt)).map(LiveRun::summary).toList();}
    @Override public void forgetWorkspace(String workspaceId){
        operations.deletingWorkspace(workspaceId,()->forgetConcurrently(runs.values().stream()
                .filter(run->run.agent.workspaceId().equals(workspaceId)).toList()));
    }
    @Override public void forgetAgent(String workspaceId,String agentId){
        operations.withAgent(workspaceId,agentId,()->forgetConcurrently(runs.values().stream()
                .filter(run->run.agent.workspaceId().equals(workspaceId)
                        &&run.agent.agentId().equals(agentId)).toList()));
    }
    private void forgetConcurrently(List<LiveRun> selected){
        runConcurrently(selected,this::forgetAfterDurableDelete,"durably deleted run cleanup");
    }
    private void forgetAfterDurableDelete(LiveRun run){
        terminalTransitions.cancel(run.id);
        run.durablyDeleted=true;
        run.terminalTransition.set(true);
        run.terminationPending.set(true);
        quiesceAutomaticInput(run);
        RuntimeException failure=processTerminations.terminate(run.id,run.process::stopAndConfirm,
                ()->{synchronized(run){run.terminationPending.set(false);}discardDurablyMissingRun(run);});
        if(failure!=null)LOG.warn(
                "Run {} was deleted durably, but its process tree is still awaiting bounded termination retry",
                run.id,failure);
    }
    @Override public void forgetRun(String runId){
        LiveRun run=live(runId);
        operations.withAgent(run.agent.workspaceId(),run.agent.agentId(),()->{
            synchronized(run){run.removeWhenTerminal=true;}
            RuntimeException failure=transitionTerminal(run,RunStatus.ERROR,null,true);
            if(failure!=null)throw failure;
            synchronized(run){
                if(run.status.active()){
                    RuntimeException pending=run.terminalPersistenceFailure;
                    throw new ExecutionConflict("Run terminal state is still waiting for durable persistence: "+run.id,pending);
                }
            }
            runs.remove(runId,run);cleanupRuntime(run);outputHub.clear(runId);
        });
    }
    @Override public RunOutputSnapshot open(String runId,java.util.function.Consumer<String> listener){LiveRun run=live(runId);synchronized(run){if(!run.active())return new RunOutputSnapshot(run.output.toString(),()->{});run.outputViewers++;RunOutputSubscription underlying=outputHub.subscribe(runId,run.outputSequence,listener);AtomicBoolean closed=new AtomicBoolean();RunOutputSubscription leased=()->{if(!closed.compareAndSet(false,true))return;underlying.close();synchronized(run){run.outputViewers--;}retainRecentCompletedRuns();};return new RunOutputSnapshot(run.output.toString(),leased);}}

    @Override public MessageDeliveryResult userInput(String workspaceId,String text){String validated=ExecutionInputLimits.userInput(text);String agentId=workspaceId+":orchestrator";return operations.withAgent(workspaceId,agentId,()->userInputCoordinated(workspaceId,agentId,validated));}
    private MessageDeliveryResult userInputCoordinated(String workspaceId,String agentId,String text){Optional<LiveRun> active=findActiveRun(workspaceId,agentId);if(active.isEmpty())return MessageDeliveryResult.failed("No active orchestrator run");try{LiveRun run=active.orElseThrow();PersistedMessageDelivery.execute(()->recovery.appendUserInput(workspaceId,agentId,text,Instant.now(clock)),recovery::deleteMessage,()->deliverText(run,AgentPromptBuilder.userInput(text)));return MessageDeliveryResult.success();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return MessageDeliveryResult.failed("User input delivery was interrupted");}catch(InteractiveInputSubmitter.SubmissionException error){return deliveryFailure(error);}catch(RuntimeException error){return MessageDeliveryResult.failed(error.getMessage());}}
    @Override public MessageDeliveryResult deliver(String workspaceId,String workerId,String dispatchId,String senderName,String workerDescription,String text,String runtimePort){return operations.withAgent(workspaceId,workerId,()->deliverCoordinated(workspaceId,workerId,dispatchId,senderName,workerDescription,text,runtimePort));}
    private MessageDeliveryResult deliverCoordinated(String workspaceId,String workerId,String dispatchId,String senderName,String workerDescription,String text,String runtimePort){try{requireAgent(workspaceId,workerId);LiveRun run=findActiveRun(workspaceId,workerId).orElseGet(()->live(startCoordinated(new StartAgentCommand(workspaceId,workerId,runtimePort)).runId()));requireAgent(workspaceId,workerId);deliverText(run,AgentPromptBuilder.dispatch(senderName,workerDescription,dispatchId,text));return MessageDeliveryResult.success();}catch(InteractiveInputSubmitter.SubmissionException error){return deliveryFailure(error);}catch(RuntimeException error){return MessageDeliveryResult.failed(error.getMessage());}}
    @Override public MessageDeliveryResult report(String workspaceId,String workerName,String text,List<String> artifacts){return writeToAgent(workspaceId,workspaceId+":orchestrator",AgentPromptBuilder.report(workerName,text,artifacts));}
    @Override public MessageDeliveryResult status(String workspaceId,String workerName,String text,List<String> artifacts){return writeToAgent(workspaceId,workspaceId+":orchestrator",AgentPromptBuilder.status(workerName,text,artifacts));}
    @Override public MessageDeliveryResult cancel(String workspaceId,String workerId,String dispatchId,String reason){return writeToAgent(workspaceId,workerId,AgentPromptBuilder.cancel(dispatchId,reason));}
    private MessageDeliveryResult writeToAgent(String workspaceId,String agentId,String text){return operations.withAgent(workspaceId,agentId,()->writeToAgentCoordinated(workspaceId,agentId,text));}
    private MessageDeliveryResult writeToAgentCoordinated(String workspaceId,String agentId,String text){Optional<LiveRun> active=findActiveRun(workspaceId,agentId);if(active.isEmpty())return MessageDeliveryResult.failed("No active run for agent: "+agentId);try{requireAgent(workspaceId,agentId);deliverText(active.orElseThrow(),text);return MessageDeliveryResult.success();}catch(InteractiveInputSubmitter.SubmissionException error){return deliveryFailure(error);}catch(RuntimeException error){return MessageDeliveryResult.failed(error.getMessage());}}
    private void deliverText(LiveRun run,String text){run.automaticInput.submit(text);}
    private long submitAutomaticInput(LiveRun run,String text,long readyAfterPosition){
        LockedPtyWriter writer=new LockedPtyWriter(run);
        try{
            if(!run.active())throw new ExecutionConflict("PTY is not active for run: "+run.id);
            return InteractiveInputSubmitter.submit(inputCommand(run),text,run::active,run.interactiveOutput::snapshot,writer,readyAfterPosition);
        }finally{writer.close();}
    }
    private MessageDeliveryResult deliveryFailure(InteractiveInputSubmitter.SubmissionException error){return error.inputAttempted()?MessageDeliveryResult.uncertain(error.getMessage()):MessageDeliveryResult.failed(error.getMessage());}
    private String inputCommand(LiveRun run){return Objects.requireNonNullElse(run.configuration.interactiveCommand(),run.configuration.command());}
    private Optional<LiveRun> findActiveRun(String workspaceId,String agentId){return runs.values().stream().filter(run->run.agent.workspaceId().equals(workspaceId)&&run.agent.agentId().equals(agentId)&&run.active()).findFirst();}
    private synchronized void retainRecentCompletedRuns(){List<LiveRun> completed=runs.values().stream().filter(LiveRun::durablyTerminal).sorted(Comparator.comparing(LiveRun::retentionTime)).toList();int remove=completed.size()-MAX_COMPLETED_RUNS;for(LiveRun expired:completed){if(remove<=0)break;synchronized(expired){if(expired.outputViewers>0)continue;}if(runs.remove(expired.id,expired)){outputHub.clear(expired.id);remove--;}}}
    private LiveRun live(String id){LiveRun run=runs.get(id);if(run==null)throw new RunNotFound("Run not found: "+id);return run;}
    private AgentDescriptor requireAgent(String workspace,String agent){return directory.find(workspace,agent).orElseThrow(()->new ExecutionConflict("Agent not found: "+agent));}
    @Override public void close(){
        lifecycle.writeLock().lock();
        try{
            if(!closed.compareAndSet(false,true))return;
            try{
                runConcurrently(runs.values().stream().filter(LiveRun::active).toList(),
                        run->{
                            RuntimeException failure=transitionTerminal(run,RunStatus.ERROR,null,true);
                            if(failure!=null)LOG.warn(
                                    "Run {} remains supervised after service close because its process tree did not stop",
                                    run.id,failure);
                        },"service-close process termination");
            }finally{
                terminalTransitions.close();
                processTerminations.closeWhenIdle();
            }
        }finally{lifecycle.writeLock().unlock();}
    }

    private void runConcurrently(List<LiveRun> selected,Consumer<LiveRun> action,String description){
        if(selected.isEmpty())return;
        try(ExecutorService executor=newLifecycleCleanupExecutor(selected.size())){
            List<Future<?>> futures=new ArrayList<>(selected.size());
            for(LiveRun run:selected)futures.add(executor.submit(()->action.accept(run)));
            boolean interrupted=false;
            for(Future<?> future:futures){
                while(true){
                    try{future.get();break;}
                    catch(InterruptedException ignored){interrupted=true;}
                    catch(ExecutionException failure){
                        LOG.warn("Could not complete {}",description,failure.getCause());
                        break;
                    }
                }
            }
            if(interrupted)Thread.currentThread().interrupt();
        }
    }

    static ExecutorService newLifecycleCleanupExecutor(int taskCount){
        if(taskCount<1||taskCount>MAX_ACTIVE_RUNS)throw new IllegalArgumentException(
                "Lifecycle cleanup task count must be between 1 and "+MAX_ACTIVE_RUNS);
        int workers=Math.min(taskCount,ProcessTerminationSupervisor.MAX_CONCURRENT_ATTEMPTS);
        return new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_ACTIVE_RUNS),
                Thread.ofPlatform().daemon(true).name("termestra-lifecycle-cleanup-",0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private final class LiveRun {
        final String id;final AgentDescriptor agent;final AgentLaunchConfiguration configuration;final PseudoTerminalHandle process;final Instant startedAt;final String resumedSessionId;final String token;final BoundedUtf8TextBuffer output=new BoundedUtf8TextBuffer(MAX_OUTPUT);final IncrementalUtf8Decoder decoder=new IncrementalUtf8Decoder();final InteractiveOutputTail interactiveOutput=new InteractiveOutputTail();final PtyLastLineTracker lastLine=new PtyLastLineTracker();final ArrayDeque<OutputPublication> publications=new ArrayDeque<>();
        final ReentrantLock ptyInputLock=new ReentrantLock(true);final AtomicBoolean terminalTransition=new AtomicBoolean();final AtomicBoolean terminationPending=new AtomicBoolean();final AtomicBoolean inputQuiesced=new AtomicBoolean();final AtomicBoolean runtimeCleaned=new AtomicBoolean();final AtomicBoolean capacityReleased=new AtomicBoolean();final RunCapacityBudget.Lease capacity;final AutomaticInputMailbox automaticInput;
        volatile Thread captureThread;volatile boolean durablyDeleted;RunStatus status=RunStatus.STARTING;Integer exitCode;Instant endedAt;RuntimeException terminalPersistenceFailure;int outputViewers;int pendingPublicationCharacters;long outputSequence;boolean publishing;boolean removeWhenTerminal;
        LiveRun(String id,AgentDescriptor agent,AgentLaunchConfiguration configuration,PseudoTerminalHandle process,Instant startedAt,String resumedSessionId,String token,RunCapacityBudget.Lease capacity){this.id=id;this.agent=agent;this.configuration=configuration;this.process=process;this.startedAt=startedAt;this.resumedSessionId=resumedSessionId;this.token=token;this.capacity=capacity;this.automaticInput=new AutomaticInputMailbox(id,(text,position)->submitAutomaticInput(this,text,position));}
        AgentRunView view(){synchronized(this){return new AgentRunView(id,agent.agentId(),agent.name(),agent.workspaceId(),process.pid(),status.wireValue(),output.toString(),exitCode,startedAt.toEpochMilli(),endedAt==null?null:endedAt.toEpochMilli(),terminalInputProfile());}}
        AgentRunSummaryView summary(){synchronized(this){String visibleStatus=status.active()&&terminalTransition.get()&&!terminationPending.get()?RunStatus.ERROR.wireValue():status.wireValue();return new AgentRunSummaryView(id,agent.agentId(),agent.name(),visibleStatus,terminalInputProfile(),lastLine.lastLine(),exitCode);}}
        boolean active(){synchronized(this){return status.active()&&!terminalTransition.get();}}
        boolean acceptsOutput(){synchronized(this){return status.active()&&!durablyDeleted&&!runtimeCleaned.get();}}
        boolean pendingTerminalPersistence(){synchronized(this){return status.active()&&terminalTransition.get();}}
        boolean durablyTerminal(){synchronized(this){return !status.active();}}
        Instant retentionTime(){synchronized(this){return endedAt==null?startedAt:endedAt;}}
        private String terminalInputProfile(){return "opencode".equals(configuration.commandPresetId())||configuration.command().endsWith("opencode")?"opencode":"default";}
    }

    private record OutputPublication(long sequence,String text) { }

    private static final class LockedPtyWriter implements Consumer<byte[]>,AutoCloseable {
        private final LiveRun run;private boolean locked;
        private LockedPtyWriter(LiveRun run){this.run=run;}
        @Override public void accept(byte[] input){
            if(!locked){
                try{run.ptyInputLock.lockInterruptibly();locked=true;}
                catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw new InteractiveInputSubmitter.SubmissionException(
                            "Interrupted before terminal input could be written",false,interrupted);
                }
            }
            if(!run.active())throw new ExecutionConflict("PTY is not active for run: "+run.id);
            run.process.write(input);
        }
        @Override public void close(){if(locked){locked=false;run.ptyInputLock.unlock();}}
    }
}
