package dev.termestra.execution.application.port.in;

public sealed interface LaunchSource {
    record Preset(String presetId,String modelId,Long expectedPresetRevision) implements LaunchSource { }
    record Snapshot(String sourceAgentId,Long expectedSourceRevision) implements LaunchSource { }
    record Startup(String startupCommand,String recoveryPresetId,
                   boolean requireRecoveryPreset) implements LaunchSource { }
    record RoleDefault(String roleType) implements LaunchSource { }
}
