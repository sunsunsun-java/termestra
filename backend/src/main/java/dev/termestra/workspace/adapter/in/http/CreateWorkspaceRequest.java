package dev.termestra.workspace.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateWorkspaceRequest(
        @JsonProperty("registration_id") String registrationId,
        String path,
        String name,
        @JsonProperty("startup_command") String startupCommand,
        @JsonProperty("command_preset_id") String commandPresetId,
        @JsonProperty("autostart_orchestrator") Boolean autostartOrchestrator,
        @JsonProperty("revision_selection") RevisionSelectionRequest revisionSelection) {
    public boolean shouldAutostart() { return autostartOrchestrator == null || autostartOrchestrator; }

    public record RevisionSelectionRequest(
            String kind,
            String name,
            @JsonProperty("selection_token") String selectionToken) { }
}
