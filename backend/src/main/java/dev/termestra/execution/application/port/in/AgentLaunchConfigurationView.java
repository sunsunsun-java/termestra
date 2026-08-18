package dev.termestra.execution.application.port.in;

import java.util.List;
import java.util.Map;

public record AgentLaunchConfigurationView(String command, List<String> arguments, String commandPresetId,
                                           String interactiveCommand, boolean presetAugmentationDisabled,
                                           String resumeArgsTemplate, String sessionIdCaptureJson,
                                           Map<String,String> environment) {
    public AgentLaunchConfigurationView { arguments = List.copyOf(arguments);environment=Map.copyOf(environment); }
    public AgentLaunchConfigurationView(String command,List<String> arguments,String commandPresetId,
                                        String interactiveCommand,boolean presetAugmentationDisabled,
                                        String resumeArgsTemplate,String sessionIdCaptureJson){
        this(command,arguments,commandPresetId,interactiveCommand,presetAugmentationDisabled,
                resumeArgsTemplate,sessionIdCaptureJson,Map.of());
    }
}
