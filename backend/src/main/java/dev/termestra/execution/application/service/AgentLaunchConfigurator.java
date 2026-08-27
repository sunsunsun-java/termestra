package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.execution.application.exception.InvalidLaunchRequest;
import dev.termestra.execution.application.port.in.*;
import dev.termestra.execution.application.port.out.*;
import dev.termestra.execution.domain.model.AgentLaunchConfiguration;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Resolves launch intent into one durable Agent Execution-owned snapshot. */
public final class AgentLaunchConfigurator implements ConfigureAgentLaunchUseCase {
    private static final String MODEL_PLACEHOLDER="{model_id}";
    private final AgentExecutionRepository repository;
    private final LaunchPresetCatalog presets;
    private final ShellCommandResolver shells;
    private final Clock clock;
    private final RuntimeOperationCoordinator operations;

    public AgentLaunchConfigurator(AgentExecutionRepository repository,LaunchPresetCatalog presets,
                                   ShellCommandResolver shells,
                                   Clock clock,RuntimeOperationCoordinator operations){
        this.repository=repository;this.presets=presets;this.shells=shells;
        this.clock=clock;this.operations=operations;
    }

    @Override public void configure(ConfigureAgentLaunchCommand command){
        operations.withAgent(command.workspaceId(),command.agentId(),()->configureCoordinated(command));
    }

    private void configureCoordinated(ConfigureAgentLaunchCommand command){
        Instant now=Instant.now(clock);
        if(command.source() instanceof LaunchSource.Snapshot source){
            repository.copyConfigurationSnapshot(command.workspaceId(),
                    source.sourceAgentId(),command.agentId(),source.expectedSourceRevision(),now)
                    .orElseThrow(()->new ExecutionConflict("ORCHESTRATOR_LAUNCH_CHANGED",
                            "ORCHESTRATOR_LAUNCH_CHANGED: launch snapshot is unavailable or stale"));
            return;
        }
        Resolved resolved=resolve(command.source());
        ConfigureAgentCommand validated=new ConfigureAgentCommand(command.workspaceId(),command.agentId(),
                resolved.command(),resolved.arguments(),resolved.presetId(),resolved.interactiveCommand(),
                resolved.augmentationDisabled(),resolved.resumeArgsTemplate(),
                resolved.sessionIdCaptureJson(),resolved.environment());
        AgentLaunchConfiguration configuration=new AgentLaunchConfiguration(validated.command(),validated.arguments(),
                validated.commandPresetId(),validated.interactiveCommand(),validated.presetAugmentationDisabled(),
                validated.resumeArgsTemplate(),validated.sessionIdCaptureJson(),validated.environment(),
                resolved.modelId(),1);
        if(!repository.saveConfiguration(command.workspaceId(),command.agentId(),configuration,now)){
            throw new ExecutionConflict("AGENT_NO_LONGER_EXISTS",
                    "AGENT_NO_LONGER_EXISTS: "+command.agentId());
        }
    }

    private Resolved resolve(LaunchSource source){
        if(source instanceof LaunchSource.Preset preset)return preset(preset);
        if(source instanceof LaunchSource.Startup startup)return startup(startup);
        if(source instanceof LaunchSource.RoleDefault role){
            LaunchPresetDescriptor descriptor=presets.roleDefault(role.roleType());
            return resolvedPreset(descriptor,null);
        }
        throw new IllegalArgumentException("Unsupported launch source");
    }

    private Resolved preset(LaunchSource.Preset source){
        if(source.presetId()==null||source.presetId().isBlank())throw new InvalidLaunchRequest(
                "COMMAND_PRESET_NOT_FOUND","preset_id is required");
        LaunchPresetDescriptor descriptor=presets.require(source.presetId());
        if(!descriptor.available())throw new InvalidLaunchRequest(
                "COMMAND_PRESET_UNAVAILABLE",source.presetId());
        if(source.expectedPresetRevision()!=null&&descriptor.revision()!=source.expectedPresetRevision()){
            throw new ExecutionConflict("COMMAND_PRESET_CHANGED",
                    "COMMAND_PRESET_CHANGED: "+source.presetId());
        }
        return resolvedPreset(descriptor,ExecutionInputLimits.optionalModelId(source.modelId()));
    }

    private Resolved resolvedPreset(LaunchPresetDescriptor descriptor,String modelId){
        List<String> arguments=new ArrayList<>();
        if(modelId!=null){
            List<String> template=descriptor.modelArgumentTemplate();
            if(template==null||template.isEmpty())throw new InvalidLaunchRequest(
                    "MODEL_SELECTION_UNSUPPORTED",descriptor.id());
            if(!descriptor.allowCustomModel()&&!descriptor.suggestedModels().contains(modelId)){
                throw new InvalidLaunchRequest("MODEL_ID_INVALID",
                        "model is not allowed by preset "+descriptor.id());
            }
            for(String token:template)arguments.add(token.replace(MODEL_PLACEHOLDER,modelId));
        }
        arguments.addAll(descriptor.arguments());
        List<String> frozenArguments=LaunchArguments.prependUnique(descriptor.yoloArguments(),arguments);
        return new Resolved(descriptor.command(),ExecutionInputLimits.arguments(frozenArguments),descriptor.id(),null,true,
                descriptor.resumeArgsTemplate(),descriptor.sessionIdCaptureJson(),descriptor.environment(),modelId);
    }

    private Resolved startup(LaunchSource.Startup source){
        String startup=source.startupCommand()==null?null:source.startupCommand().trim();
        if(startup==null||startup.isBlank())throw new IllegalArgumentException("startup_command is required");
        LaunchPresetDescriptor recovery=recoveryPreset(source);
        ShellCommandResolver.ShellCommand shell=shells.resolve(startup);
        return new Resolved(shell.command(),ExecutionInputLimits.arguments(shell.arguments()),null,
                recovery==null?startup:recovery.command(),true,
                recovery==null?null:recovery.resumeArgsTemplate(),
                recovery==null?null:recovery.sessionIdCaptureJson(),
                recovery==null?Map.of():recovery.environment(),null);
    }

    private LaunchPresetDescriptor recoveryPreset(LaunchSource.Startup source){
        String presetId=ExecutionInputLimits.optionalPresetId(source.recoveryPresetId());
        if(presetId==null||presetId.isBlank())return null;
        if(source.requireRecoveryPreset())return presets.require(presetId);
        return presets.availablePresets().stream().filter(value->presetId.equals(value.id())).findFirst().orElse(null);
    }

    private record Resolved(String command,List<String> arguments,String presetId,String interactiveCommand,
                            boolean augmentationDisabled,String resumeArgsTemplate,String sessionIdCaptureJson,
                            Map<String,String> environment,String modelId){ }
}
