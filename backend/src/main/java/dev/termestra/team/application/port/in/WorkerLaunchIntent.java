package dev.termestra.team.application.port.in;

public sealed interface WorkerLaunchIntent {
    record Preset(String presetId,String modelId,Long expectedPresetRevision) implements WorkerLaunchIntent { }
    record OrchestratorSnapshot(Long expectedSourceRevision) implements WorkerLaunchIntent { }
    record Startup(String startupCommand,String recoveryPresetId) implements WorkerLaunchIntent { }
    record LegacyStartup(String startupCommand,String recoveryPresetId) implements WorkerLaunchIntent { }
}
