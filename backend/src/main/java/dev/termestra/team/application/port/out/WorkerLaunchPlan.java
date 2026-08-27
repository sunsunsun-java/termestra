package dev.termestra.team.application.port.out;

import java.util.List;
import java.util.Map;

public record WorkerLaunchPlan(String command, List<String> arguments, String commandPresetId,
                               String resumeArgsTemplate, String sessionIdCaptureJson,
                               Map<String,String> environment,String modelId,
                               boolean presetAugmentationDisabled) {
    public WorkerLaunchPlan { arguments = List.copyOf(arguments);environment=Map.copyOf(environment); }
}
