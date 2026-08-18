package dev.termestra.execution.application.port.in;

import java.util.List;
import java.util.Map;
public record ConfigureAgentCommand(String workspaceId,String agentId,String command,List<String> arguments,
                                    String commandPresetId,String interactiveCommand,
                                    boolean presetAugmentationDisabled,String resumeArgsTemplate,
                                    String sessionIdCaptureJson,Map<String,String> environment) {
    public ConfigureAgentCommand {
        command = ExecutionInputLimits.command(command);
        arguments = ExecutionInputLimits.arguments(arguments);
        commandPresetId = ExecutionInputLimits.optionalPresetId(commandPresetId);
        interactiveCommand = ExecutionInputLimits.optionalCommand(interactiveCommand, "interactive_command");
        resumeArgsTemplate = ExecutionInputLimits.optionalCommand(resumeArgsTemplate, "resume_args_template");
        sessionIdCaptureJson = ExecutionInputLimits.optionalCaptureJson(sessionIdCaptureJson);
        environment = ExecutionInputLimits.environment(environment);
    }
    public ConfigureAgentCommand(String workspaceId,String agentId,String command,List<String> arguments,
                                 String commandPresetId,String interactiveCommand) {
        this(workspaceId,agentId,command,arguments,commandPresetId,interactiveCommand,false,null,null,Map.of());
    }
    public ConfigureAgentCommand(String workspaceId,String agentId,String command,List<String> arguments,
                                 String commandPresetId,String interactiveCommand,boolean presetAugmentationDisabled,
                                 String resumeArgsTemplate,String sessionIdCaptureJson) {
        this(workspaceId,agentId,command,arguments,commandPresetId,interactiveCommand,
                presetAugmentationDisabled,resumeArgsTemplate,sessionIdCaptureJson,Map.of());
    }
}
