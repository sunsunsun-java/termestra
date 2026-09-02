package dev.termestra.bootstrap.config;

import dev.termestra.auth.application.UiSessionService;
import dev.termestra.auth.application.AgentCredentialService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.adapter.out.persistence.JdbcAgentDirectory;
import dev.termestra.execution.adapter.out.persistence.JdbcAgentExecutionRepository;
import dev.termestra.execution.adapter.out.persistence.JdbcAgentRecoveryContextProvider;
import dev.termestra.execution.adapter.out.pty.Pty4jProcessLauncher;
import dev.termestra.execution.adapter.out.system.SystemShellCommandResolver;
import dev.termestra.execution.adapter.out.session.FilesystemAgentSessionCapture;
import dev.termestra.execution.application.port.in.*;
import dev.termestra.execution.application.port.out.*;
import dev.termestra.execution.application.service.AgentExecutionService;
import dev.termestra.execution.application.service.AgentLaunchConfigurator;
import dev.termestra.execution.application.service.AgentLaunchOptionsService;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.platform.persistence.sqlite.*;
import dev.termestra.workspace.adapter.out.filesystem.NioWorkspacePathResolver;
import dev.termestra.workspace.adapter.out.filesystem.ProcessWorkspaceOpener;
import dev.termestra.workspace.adapter.out.persistence.JdbcWorkspaceRepository;
import dev.termestra.workspace.adapter.out.persistence.JdbcWorkspaceRegistrationLedger;
import dev.termestra.workspace.application.port.in.*;
import dev.termestra.workspace.application.port.in.registration.WorkspaceRegistrationUseCase;
import dev.termestra.workspace.application.port.out.*;
import dev.termestra.workspace.application.service.WorkspaceApplicationService;
import dev.termestra.workspace.application.service.OpenWorkspaceService;
import dev.termestra.workspace.application.service.WorkspaceRegistrationService;
import dev.termestra.team.adapter.out.persistence.*;
import dev.termestra.team.adapter.out.runtime.ExecutionTeamScenarioRuntime;
import dev.termestra.team.adapter.out.runtime.ExecutionWorkerExecution;
import dev.termestra.team.adapter.out.runtime.DispatchDeliveryRuntime;
import dev.termestra.team.application.port.in.DispatchDeliveryUseCase;
import dev.termestra.team.application.port.in.ApplyTeamScenarioUseCase;
import dev.termestra.team.application.port.in.CreateWorkerUseCase;
import dev.termestra.team.application.port.in.RemoveWorkerUseCase;
import dev.termestra.team.application.port.in.TeamAdminUseCase;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.application.service.PendingTaskProjection;
import dev.termestra.team.application.service.DispatchDeliveryApplicationService;
import dev.termestra.team.application.service.TeamApplicationService;
import dev.termestra.team.application.service.TeamScenarioApplicationService;
import dev.termestra.team.application.service.WorkerRemovalService;
import dev.termestra.team.application.service.CreateWorkerService;
import dev.termestra.team.domain.model.*;
import dev.termestra.terminal.application.port.in.*;
import dev.termestra.terminal.application.port.out.TerminalRuntimeGateway;
import dev.termestra.terminal.application.service.TerminalChannelService;
import dev.termestra.tasks.adapter.out.filesystem.NioTasksDocumentStore;
import dev.termestra.tasks.adapter.out.filesystem.NioTasksFileWatcher;
import dev.termestra.tasks.application.port.in.TasksUseCase;
import dev.termestra.tasks.application.port.out.*;
import dev.termestra.tasks.application.service.TasksApplicationService;
import dev.termestra.configuration.adapter.out.persistence.JdbcConfigurationRepository;
import dev.termestra.configuration.adapter.out.system.PathCommandAvailabilityProbe;
import dev.termestra.configuration.application.port.in.*;
import dev.termestra.configuration.application.port.out.CommandAvailabilityProbe;
import dev.termestra.configuration.application.port.out.ConfigurationRepository;
import dev.termestra.configuration.application.service.CommandAvailabilityService;
import dev.termestra.configuration.application.service.ConfigurationApplicationService;
import dev.termestra.marketplace.adapter.out.classpath.ClasspathMarketplaceCatalog;
import dev.termestra.marketplace.application.MarketplaceCatalog;
import dev.termestra.workspace.adapter.out.filesystem.browse.NioDirectoryBrowser;
import dev.termestra.workspace.adapter.out.filesystem.browse.NioSelectedDirectoryProbe;
import dev.termestra.workspace.application.port.in.browse.FilesystemBrowseUseCase;
import dev.termestra.workspace.application.port.out.browse.DirectoryBrowser;
import dev.termestra.workspace.application.port.out.browse.NativeFolderPicker;
import dev.termestra.workspace.application.port.out.browse.SelectedDirectoryProbe;
import dev.termestra.workspace.application.service.browse.FilesystemBrowseService;
import dev.termestra.workspace.application.service.browse.FilesystemPickerService;
import dev.termestra.workspace.application.port.in.browse.FilesystemPickerUseCase;
import dev.termestra.workspace.adapter.out.filesystem.browse.ProcessNativeFolderPicker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Configuration
public class RuntimeWiring {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    @Bean RuntimeOperationCoordinator runtimeOperationCoordinator(){return new RuntimeOperationCoordinator();}

    @Bean SqliteDatabase sqliteDatabase(@Value("${termestra.data-directory}") Path dataDirectory, Clock clock) throws IOException {
        Path databaseFile = TermestraDatabaseLocation.prepare(dataDirectory);
        SqliteDatabase database = new SqliteDatabase(databaseFile);
        new SqliteSchemaMigrator(database, clock).migrate();
        return database;
    }

    @Bean WorkspaceRepository workspaceRepository(SqliteDatabase database) { return new JdbcWorkspaceRepository(database); }
    @Bean WorkspacePathResolver workspacePathResolver() { return new NioWorkspacePathResolver(); }
    @Bean WorkspaceOpener workspaceOpener(){return new ProcessWorkspaceOpener();}
    @Bean OpenWorkspaceUseCase openWorkspaceUseCase(WorkspaceRepository repository,WorkspaceOpener opener){return new OpenWorkspaceService(repository,opener);}
    @Bean OrchestratorStarter orchestratorStarter(AgentExecutionUseCase execution,
                                                   ConfigureAgentLaunchUseCase launches,
                                                   AgentLaunchConfigurationQuery configurations,
                                                   ObjectMapper json) {
        return new OrchestratorStarter(){
          @Override public OrchestratorStartView prepare(dev.termestra.workspace.domain.model.Workspace workspace,
                                                         String startupCommand,String commandPresetId,String modelId,
                                                         Long expectedPresetRevision,boolean autostart){
            String workspaceId=workspace.id().toString();
            String agentId=workspace.id()+":orchestrator";
            if(startupCommand!=null&&!startupCommand.isBlank()){
                launches.configure(new ConfigureAgentLaunchCommand(workspaceId,agentId,
                        new LaunchSource.Startup(startupCommand,commandPresetId,true)));
            }else if(commandPresetId!=null&&!commandPresetId.isBlank()){
                launches.configure(new ConfigureAgentLaunchCommand(workspaceId,agentId,
                        new LaunchSource.Preset(commandPresetId,modelId,expectedPresetRevision)));
            }else {
                String configuredCommand=environmentValue("TERMESTRA_ORCHESTRATOR_COMMAND");
                if(configuredCommand!=null){
                    String configuredArguments=environmentValue("TERMESTRA_ORCHESTRATOR_ARGS_JSON");
                    execution.configure(new ConfigureAgentCommand(workspaceId,agentId,configuredCommand,
                            parseArguments(configuredArguments,json),null,null));
                } else {
                    launches.configure(new ConfigureAgentLaunchCommand(workspaceId,agentId,
                            new LaunchSource.RoleDefault("orchestrator")));
                }
            }
            if(!autostart)return OrchestratorStartView.disabled();
            try {
                AgentRunView run=execution.start(new StartAgentCommand(workspaceId,agentId,null));
                return new OrchestratorStartView(true, null, run.runId());
            } catch (RuntimeException error) {
                return new OrchestratorStartView(false, error.getMessage(), null);
            }
          }

          @Override public OrchestratorStartView prepareIfMissing(
                  dev.termestra.workspace.domain.model.Workspace workspace,
                  String startupCommand,String commandPresetId,String modelId,
                  Long expectedPresetRevision,boolean autostart) {
              String workspaceId=workspace.id().toString();
              String agentId=workspace.id()+":orchestrator";
              if(configurations.find(workspaceId,agentId).isPresent()){
                  return OrchestratorStartView.disabled();
              }
              return prepare(workspace,startupCommand,commandPresetId,modelId,
                      expectedPresetRevision,autostart);
          }
        };
    }
    private static List<String> parseArguments(String raw,ObjectMapper json){if(raw==null||raw.isBlank())return List.of();try{return json.readValue(raw,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}catch(com.fasterxml.jackson.core.JsonProcessingException invalidJson){return List.of(raw.trim().split("\\s+"));}}
    @Bean WorkspaceRuntimeCleaner workspaceRuntimeCleaner(AgentExecutionUseCase execution,
                                                            PendingTaskProjection pendingTasks,
                                                            TasksUseCase tasks){
        return workspaceId -> {
            tasks.forgetWorkspace(workspaceId);
            execution.forgetWorkspace(workspaceId);
            pendingTasks.invalidate(workspaceId);
        };
    }
    @Bean WorkspaceApplicationService workspaceService(WorkspaceRepository repository,
                                                         WorkspaceRuntimeCleaner cleaner,
                                                         RuntimeOperationCoordinator operations) {
        return new WorkspaceApplicationService(repository, cleaner, operations);
    }
    @Bean WorkspaceRegistrationLedger workspaceRegistrationLedger(SqliteDatabase database) {
        return new JdbcWorkspaceRegistrationLedger(database);
    }
    @Bean WorkspaceRegistrationUseCase workspaceRegistrationUseCase(
            WorkspaceRegistrationLedger ledger, WorkspaceRepository repository,
            WorkspacePathResolver resolver, WorkspaceMetadataInitializer metadata,
            OrchestratorStarter starter, RuntimeOperationCoordinator operations, Clock clock) {
        WorkspaceRegistrationService service = new WorkspaceRegistrationService(
                ledger, repository, resolver, metadata, starter, operations, clock);
        service.recover();
        return service;
    }
    @Bean UiSessionService uiSessionService() { return new UiSessionService(); }
    @Bean AgentCredentialService agentCredentialService() { return new AgentCredentialService(); }
    @Bean AgentExecutionRepository agentExecutionRepository(SqliteDatabase database, ObjectMapper json) {
        return new JdbcAgentExecutionRepository(database, json);
    }
    @Bean LaunchPresetCatalog launchPresetCatalog(ConfigurationUseCase configuration,
                                                   CommandAvailabilityUseCase availability,ObjectMapper json){
        return new ConfigurationLaunchPresetCatalog(configuration,availability,json);
    }
    @Bean AgentLaunchConfigurator agentLaunchConfigurator(AgentExecutionRepository repository,
                                                           LaunchPresetCatalog presets,
                                                           ShellCommandResolver shells,Clock clock,
                                                           RuntimeOperationCoordinator operations){
        return new AgentLaunchConfigurator(repository,presets,shells,clock,operations);
    }
    @Bean ShellCommandResolver shellCommandResolver(){return new SystemShellCommandResolver();}
    @Bean AgentLaunchOptionsQuery agentLaunchOptionsQuery(LaunchPresetCatalog presets,
                                                           AgentLaunchConfigurationQuery configurations){
        return new AgentLaunchOptionsService(presets,configurations);
    }
    @Bean AgentRecoveryContextProvider agentRecoveryContextProvider(SqliteDatabase database) { return new JdbcAgentRecoveryContextProvider(database); }
    @Bean AgentDirectory agentDirectory(SqliteDatabase database) { return new JdbcAgentDirectory(database); }
    @Bean AgentCredentialIssuer agentCredentialIssuer(AgentCredentialService credentials) {
        return new AgentCredentialIssuer() {
            @Override public String issue(String agentId) { return credentials.issue(agentId); }
            @Override public String issueConcurrent(String agentId) {
                return credentials.issueConcurrent(agentId);
            }
            @Override public void revoke(String agentId, String token) { credentials.revoke(agentId, token); }
        };
    }
    @Bean PseudoTerminalLauncher pseudoTerminalLauncher() { return new Pty4jProcessLauncher(); }
    @Bean AgentSessionCapture agentSessionCapture(ObjectMapper json){return new FilesystemAgentSessionCapture(json);}
    @Bean CommandPresetPolicy commandPresetPolicy(ConfigurationUseCase configuration){return (presetId,command)->configuration.commandPresets().stream().filter(value->presetId!=null?presetId.equals(value.id()):command.equals(value.id())&&command.equals(value.command())).findFirst().map(value->Objects.requireNonNullElse(value.yoloArgsTemplate(),List.<String>of())).orElse(List.of());}
    @Bean(destroyMethod = "close") AgentExecutionService agentExecutionService(
            AgentExecutionRepository repository, AgentDirectory directory,
            AgentCredentialIssuer credentials, PseudoTerminalLauncher launcher,AgentSessionCapture sessionCapture,CommandPresetPolicy presetPolicy,AgentRecoveryContextProvider recovery, Clock clock,
            RuntimeOperationCoordinator operations) {
        return new AgentExecutionService(repository, directory, credentials, launcher,sessionCapture,presetPolicy,recovery, clock,operations);
    }
    @Bean TerminalRuntimeGateway terminalRuntimeGateway(AgentExecutionUseCase execution, RunOutputUseCase output) {
        return new TerminalRuntimeGateway() {
            @Override public TerminalRunStatusView status(String runId) {
                AgentRunSummaryView run = execution.getSummary(runId);
                return new TerminalRunStatusView(run.status(), run.exitCode());
            }
            @Override public void write(String runId, byte[] input) { execution.write(runId, input); }
            @Override public void resize(String runId, int columns, int rows) { execution.resize(runId, columns, rows); }
            @Override public void stop(String runId) { execution.stop(runId); }
            @Override public void pauseOutput(String runId) { execution.pauseOutput(runId); }
            @Override public void resumeOutput(String runId) { execution.resumeOutput(runId); }
            @Override public TerminalOutputSession open(String runId, java.util.function.Consumer<String> listener) {
                var session = output.open(runId, listener);
                return new TerminalOutputSession(session.snapshot(), session.subscription()::close);
            }
        };
    }
    @Bean TerminalChannelUseCase terminalChannelUseCase(TerminalRuntimeGateway runtime) {
        return new TerminalChannelService(runtime);
    }
    @Bean WorkspaceLocation tasksWorkspaceLocation(SqliteDatabase database) {
        return workspaceId -> database.read("find tasks workspace", connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT CASE WHEN length(path) BETWEEN 1 AND ? THEN path ELSE NULL END AS path
                    FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active'
                    """)) {
                statement.setInt(1, dev.termestra.workspace.application.port.in.WorkspaceInputLimits.MAX_PATH_CHARACTERS);
                statement.setString(2, workspaceId);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) return java.util.Optional.empty();
                    String path = result.getString("path");
                    if (path == null) throw new dev.termestra.workspace.application.exception.InvalidWorkspaceRecord();
                    try {
                        return java.util.Optional.of(Path.of(path));
                    } catch (java.nio.file.InvalidPathException invalidPath) {
                        throw new dev.termestra.workspace.application.exception.InvalidWorkspaceRecord();
                    }
                }
            }
        });
    }
    @Bean TasksDocumentStore tasksDocumentStore() { return new NioTasksDocumentStore(); }
    @Bean WorkspaceMetadataInitializer workspaceMetadataInitializer(TasksDocumentStore documents) {
        return workspacePath -> documents.initialize(Path.of(workspacePath.value()));
    }
    @Bean TasksFileWatcher tasksFileWatcher() { return new NioTasksFileWatcher(); }
    @Bean(destroyMethod="close") TasksUseCase tasksUseCase(WorkspaceLocation workspaces, TasksDocumentStore documents, TasksFileWatcher watcher) {
        return new TasksApplicationService(workspaces, documents, watcher);
    }
    @Bean ConfigurationRepository configurationRepository(SqliteDatabase database,ObjectMapper json){return new JdbcConfigurationRepository(database,json);}
    @Bean ConfigurationUseCase configurationUseCase(ConfigurationRepository repository,Clock clock){return new ConfigurationApplicationService(repository,clock);}
    @Bean CommandAvailabilityProbe commandAvailabilityProbe(){return new PathCommandAvailabilityProbe();}
    @Bean CommandAvailabilityUseCase commandAvailabilityUseCase(CommandAvailabilityProbe probe){return new CommandAvailabilityService(probe);}
    @Bean MarketplaceCatalog marketplaceCatalog(ObjectMapper json){return new ClasspathMarketplaceCatalog(json);}
    @Bean DirectoryBrowser directoryBrowser(){String configured=environmentValue("TERMESTRA_FS_BROWSE_ROOT");Path root=configured==null?Path.of(System.getProperty("user.home")):Path.of(configured);return new NioDirectoryBrowser(root);}
    @Bean FilesystemBrowseUseCase filesystemBrowseUseCase(DirectoryBrowser browser){return new FilesystemBrowseService(browser);}
    @Bean SelectedDirectoryProbe selectedDirectoryProbe(){return new NioSelectedDirectoryProbe();}
    @Bean NativeFolderPicker nativeFolderPicker(){return new ProcessNativeFolderPicker();}
    @Bean FilesystemPickerUseCase filesystemPickerUseCase(NativeFolderPicker picker,SelectedDirectoryProbe selectedDirectoryProbe){return new FilesystemPickerService(picker,selectedDirectoryProbe);}
    @Bean TeamMemberRepository teamMemberRepository(SqliteDatabase database) { return new JdbcTeamMemberRepository(database); }
    @Bean MemberProvisioningRepository memberProvisioningRepository(SqliteDatabase database,ObjectMapper json){return new JdbcMemberProvisioningRepository(database,json);}
    @Bean JdbcTeamLedger teamLedger(SqliteDatabase database, ObjectMapper json) { return new JdbcTeamLedger(database, json); }
    @Bean PendingTaskProjection pendingTaskProjection(OpenDispatchCountSource source) { return new PendingTaskProjection(source); }
    @Bean WorkerRuntimeStatus workerRuntimeStatus(AgentExecutionUseCase execution) {
        return workspaceId -> execution.listActiveSummaries(workspaceId).stream()
                .map(AgentRunSummaryView::agentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Bean AgentAuthenticator agentAuthenticator(AgentCredentialService credentials) { return credentials::validate; }
    @Bean AgentTeamNotifier agentTeamNotifier(AgentMessagingUseCase messaging) {
        return new AgentTeamNotifier() {
            private DeliveryResult result(MessageDeliveryResult result) {
                return new DeliveryResult(result.delivered(), result.inputAttempted(),
                        result.uncertain(), result.error());
            }
            @Override public DeliveryResult deliver(Dispatch dispatch, TeamMember worker, String runtimePort) {
                return result(messaging.deliver(dispatch.workspaceId().toString(), worker.id().toString(),
                        dispatch.id().toString(), "Orchestrator", worker.description(),
                        dispatch.task().value(), runtimePort));
            }
            @Override public DeliveryResult report(Dispatch dispatch, TeamMember worker) {
                return result(messaging.report(dispatch.workspaceId().toString(), worker.name(),
                        dispatch.reportText().orElse(""), dispatch.artifacts()));
            }
            @Override public DeliveryResult status(String workspaceId, TeamMember worker, String text, java.util.List<String> artifacts) {
                return result(messaging.status(workspaceId, worker.name(), text, artifacts));
            }
            @Override public DeliveryResult cancel(Dispatch dispatch, TeamMember worker) {
                return result(messaging.cancel(dispatch.workspaceId().toString(), worker.id().toString(),
                        dispatch.id().toString(), dispatch.cancellationReason().orElse("cancelled")));
            }
        };
    }
    @Bean DispatchDeliveryUseCase dispatchDeliveryUseCase(TeamLedger ledger,
                                                           TeamMemberRepository members,
                                                           AgentTeamNotifier notifier,
                                                           RuntimeOperationCoordinator operations,
                                                           Clock clock) {
        return new DispatchDeliveryApplicationService(ledger, members, notifier, operations, clock);
    }
    @Bean(initMethod="start",destroyMethod="close")
    DispatchDeliveryRuntime dispatchDeliveryRuntime(DispatchDeliveryUseCase deliveries) {
        return new DispatchDeliveryRuntime(deliveries);
    }
    @Bean TeamApplicationService teamApplicationService(TeamLedger ledger, TeamMemberRepository members,
                                                         AgentAuthenticator authenticator, AgentTeamNotifier notifier,
                                                         WorkerRuntimeStatus runtime,
                                                         PendingTaskProjection pendingTasks, Clock clock,
                                                         RuntimeOperationCoordinator operations,
                                                         DispatchDeliveryScheduler deliveryScheduler) {
        return new TeamApplicationService(ledger,members,authenticator,notifier,runtime,pendingTasks,clock,operations,deliveryScheduler);
    }
    @Bean RemoveWorkerUseCase removeWorkerUseCase(TeamAdminUseCase team,
                                                   AgentExecutionUseCase execution,
                                                   RuntimeOperationCoordinator operations) {
        return new WorkerRemovalService(team, execution::forgetAgent,operations);
    }
    @Bean WorkerExecution workerExecution(AgentLaunchPlanningUseCase launches,AgentExecutionUseCase execution){
        return new ExecutionWorkerExecution(launches,execution);
    }
    @Bean CreateWorkerUseCase createWorkerUseCase(TeamAdminUseCase team,TeamMemberRepository members,
                                                   MemberProvisioningRepository provisioning,
                                                   WorkerExecution execution,Clock clock,
                                                   RuntimeOperationCoordinator operations){
        return new CreateWorkerService(team,members,provisioning,execution,clock,operations);
    }
    @Bean TeamScenarioRuntime teamScenarioRuntime(AgentLaunchConfigurationQuery configurations,
                                                   AgentExecutionUseCase execution,
                                                   AgentMessagingUseCase messaging,
                                                   ConfigurationUseCase settings,
                                                   CommandAvailabilityUseCase availability,
                                                   ObjectMapper json){return new ExecutionTeamScenarioRuntime(configurations,execution,messaging,settings,availability,json);}
    @Bean ApplyTeamScenarioUseCase applyTeamScenarioUseCase(TeamMemberRepository members,
                                                             MemberProvisioningRepository provisioning,
                                                             TeamScenarioRuntime runtime,Clock clock,
                                                             RuntimeOperationCoordinator operations){return new TeamScenarioApplicationService(members,provisioning,runtime,clock,operations);}

    private static String environmentValue(String name){String value=System.getenv(name);return value==null||value.isBlank()?null:value;}
}
