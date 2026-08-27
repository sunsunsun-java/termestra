package dev.termestra.execution.domain.model;

import java.util.*;

public record AgentLaunchConfiguration(String command, List<String> arguments, String commandPresetId,
                                       String interactiveCommand, boolean presetAugmentationDisabled,
                                       String resumeArgsTemplate, String sessionIdCaptureJson,
                                       Map<String,String> environment,String modelId,long revision) {
    public AgentLaunchConfiguration {
        if(command==null||command.isBlank())throw new IllegalArgumentException("command must not be blank");
        command=command.trim(); arguments=List.copyOf(Objects.requireNonNullElse(arguments,List.of()));
        environment=Map.copyOf(Objects.requireNonNullElse(environment,Map.of()));
        if(revision<1)throw new IllegalArgumentException("revision must be positive");
    }
    public AgentLaunchConfiguration(String command,List<String> arguments,String commandPresetId,
                                    String interactiveCommand,boolean presetAugmentationDisabled,
                                    String resumeArgsTemplate,String sessionIdCaptureJson){
        this(command,arguments,commandPresetId,interactiveCommand,presetAugmentationDisabled,
                resumeArgsTemplate,sessionIdCaptureJson,Map.of(),null,1);
    }
    public AgentLaunchConfiguration(String command,List<String> arguments,String commandPresetId,
                                    String interactiveCommand,boolean presetAugmentationDisabled,
                                    String resumeArgsTemplate,String sessionIdCaptureJson,
                                    Map<String,String> environment){
        this(command,arguments,commandPresetId,interactiveCommand,presetAugmentationDisabled,
                resumeArgsTemplate,sessionIdCaptureJson,environment,null,1);
    }
}
