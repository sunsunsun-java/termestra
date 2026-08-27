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
public final class AgentLaunchConfigurator implements ConfigureAgentLaunchUseCase, AgentLaunchPlanningUseCase {
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

    @Override public AgentLaunchConfigurationView plan(LaunchSource source){
        Resolved resolved=resolve(source);
        return new AgentLaunchConfigurationView(resolved.command(),resolved.arguments(),resolved.presetId(),
                resolved.interactiveCommand(),resolved.augmentationDisabled(),resolved.resumeArgsTemplate(),
                resolved.sessionIdCaptureJson(),resolved.environment(),resolved.modelId(),1);
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
        List<String> presetArguments=new ArrayList<>(descriptor.arguments());
        List<String> yoloArguments=new ArrayList<>(Objects.requireNonNullElse(
                descriptor.yoloArguments(),List.of()));
        if(modelId!=null){
            List<String> template=descriptor.modelArgumentTemplate();
            if(template==null||template.isEmpty())throw new InvalidLaunchRequest(
                    "MODEL_SELECTION_UNSUPPORTED",descriptor.id());
            if(!descriptor.allowCustomModel()&&!descriptor.suggestedModels().contains(modelId)){
                throw new InvalidLaunchRequest("MODEL_ID_INVALID",
                        "model is not allowed by preset "+descriptor.id());
            }
            removeConflictingModelArguments(presetArguments,template);
            removeConflictingModelArguments(yoloArguments,template);
        }
        List<String> arguments=new ArrayList<>(LaunchArguments.prependUnique(yoloArguments,presetArguments));
        if(modelId!=null)for(String token:descriptor.modelArgumentTemplate()){
            arguments.add(token.replace(MODEL_PLACEHOLDER,modelId));
        }
        return new Resolved(descriptor.command(),ExecutionInputLimits.arguments(arguments),descriptor.id(),null,true,
                descriptor.resumeArgsTemplate(),descriptor.sessionIdCaptureJson(),descriptor.environment(),modelId);
    }

    private static void removeConflictingModelArguments(List<String> arguments,List<String> template){
        int placeholderIndex=-1;
        for(int index=0;index<template.size();index++){
            if(template.get(index).contains(MODEL_PLACEHOLDER)){placeholderIndex=index;break;}
        }
        if(placeholderIndex<0)return;
        String placeholderToken=template.get(placeholderIndex);
        if(placeholderIndex>0){
            String option=template.get(placeholderIndex-1);
            if(!option.contains(MODEL_PLACEHOLDER)&&option.startsWith("-")){
                for(int index=0;index<arguments.size();){
                    if(arguments.get(index).equals(option)){
                        arguments.remove(index);
                        if(index<arguments.size())arguments.remove(index);
                    }else index++;
                }
            }
        }
        String prefix=placeholderToken.substring(0,placeholderToken.indexOf(MODEL_PLACEHOLDER));
        String suffix=placeholderToken.substring(placeholderToken.indexOf(MODEL_PLACEHOLDER)
                +MODEL_PLACEHOLDER.length());
        if(!prefix.isEmpty()||!suffix.isEmpty())arguments.removeIf(value->
                value.startsWith(prefix)&&value.endsWith(suffix)
                        &&value.length()>=prefix.length()+suffix.length());
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
