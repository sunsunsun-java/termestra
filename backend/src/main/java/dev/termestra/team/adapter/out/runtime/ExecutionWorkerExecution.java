package dev.termestra.team.adapter.out.runtime;

import dev.termestra.execution.application.port.in.*;
import dev.termestra.team.application.port.in.WorkerLaunchIntent;
import dev.termestra.team.application.port.out.WorkerExecution;
import dev.termestra.team.application.port.out.WorkerLaunchPlan;
import dev.termestra.team.application.port.out.WorkerLaunchProvisioning;

public final class ExecutionWorkerExecution implements WorkerExecution {
    private final AgentLaunchPlanningUseCase launches;
    private final AgentExecutionUseCase execution;

    public ExecutionWorkerExecution(AgentLaunchPlanningUseCase launches,AgentExecutionUseCase execution){
        this.launches=launches;this.execution=execution;
    }

    @Override public WorkerLaunchProvisioning plan(String workspaceId,WorkerLaunchIntent launch){
        if(launch instanceof WorkerLaunchIntent.OrchestratorSnapshot value){
            return new WorkerLaunchProvisioning.SourceSnapshot(
                    workspaceId+":orchestrator",value.expectedSourceRevision());
        }
        LaunchSource source=switch(launch){
            case WorkerLaunchIntent.Preset value -> new LaunchSource.Preset(value.presetId(),value.modelId(),
                    value.expectedPresetRevision());
            case WorkerLaunchIntent.Startup value -> new LaunchSource.Startup(value.startupCommand(),
                    value.recoveryPresetId(),true);
            case WorkerLaunchIntent.LegacyStartup value -> new LaunchSource.Startup(value.startupCommand(),
                    value.recoveryPresetId(),false);
            case WorkerLaunchIntent.OrchestratorSnapshot ignored -> throw new IllegalStateException(
                    "Orchestrator snapshot must remain transaction-bound");
        };
        AgentLaunchConfigurationView resolved=launches.plan(source);
        return new WorkerLaunchProvisioning.Resolved(new WorkerLaunchPlan(resolved.command(),
                resolved.arguments(),resolved.commandPresetId(),resolved.interactiveCommand(),
                resolved.resumeArgsTemplate(),resolved.sessionIdCaptureJson(),resolved.environment(),
                resolved.modelId(),resolved.presetAugmentationDisabled()));
    }

    @Override public StartedWorker start(String workspaceId,String workerId,String runtimePort){
        return new StartedWorker(execution.start(new StartAgentCommand(workspaceId,workerId,runtimePort)).runId());
    }
}
