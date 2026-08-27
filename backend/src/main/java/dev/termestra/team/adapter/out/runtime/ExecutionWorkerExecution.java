package dev.termestra.team.adapter.out.runtime;

import dev.termestra.execution.application.port.in.*;
import dev.termestra.team.application.port.in.WorkerLaunchIntent;
import dev.termestra.team.application.port.out.WorkerExecution;

public final class ExecutionWorkerExecution implements WorkerExecution {
    private final ConfigureAgentLaunchUseCase launches;
    private final AgentExecutionUseCase execution;

    public ExecutionWorkerExecution(ConfigureAgentLaunchUseCase launches,AgentExecutionUseCase execution){
        this.launches=launches;this.execution=execution;
    }

    @Override public void configure(String workspaceId,String workerId,WorkerLaunchIntent launch){
        LaunchSource source=switch(launch){
            case WorkerLaunchIntent.Preset value -> new LaunchSource.Preset(value.presetId(),value.modelId(),
                    value.expectedPresetRevision());
            case WorkerLaunchIntent.OrchestratorSnapshot value -> new LaunchSource.Snapshot(
                    workspaceId+":orchestrator",value.expectedSourceRevision());
            case WorkerLaunchIntent.Startup value -> new LaunchSource.Startup(value.startupCommand(),
                    value.recoveryPresetId(),true);
            case WorkerLaunchIntent.LegacyStartup value -> new LaunchSource.Startup(value.startupCommand(),
                    value.recoveryPresetId(),false);
        };
        launches.configure(new ConfigureAgentLaunchCommand(workspaceId,workerId,source));
    }

    @Override public StartedWorker start(String workspaceId,String workerId,String runtimePort){
        return new StartedWorker(execution.start(new StartAgentCommand(workspaceId,workerId,runtimePort)).runId());
    }
}
