package dev.termestra.workspace.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateWorkspaceRequest(
        String path,
        String name,
        @JsonProperty("startup_command") String startupCommand,
        @JsonProperty("command_preset_id") String commandPresetId,
        @JsonProperty("autostart_orchestrator") Boolean autostartOrchestrator) {
    public boolean shouldAutostart() { return autostartOrchestrator == null || autostartOrchestrator; }
}
