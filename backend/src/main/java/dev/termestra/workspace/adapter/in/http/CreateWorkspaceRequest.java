package dev.termestra.workspace.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.execution.application.exception.InvalidLaunchRequest;

public record CreateWorkspaceRequest(
        @JsonProperty("registration_id") String registrationId,
        String path,
        String name,
        @JsonProperty("startup_command") String startupCommand,
        @JsonProperty("command_preset_id") String commandPresetId,
        @JsonProperty("autostart_orchestrator") Boolean autostartOrchestrator,
        @JsonProperty("revision_selection") RevisionSelectionRequest revisionSelection,
        Launch launch) {
    public boolean shouldAutostart() { return autostartOrchestrator == null || autostartOrchestrator; }

    public record RevisionSelectionRequest(
            String kind,
            String name,
            @JsonProperty("selection_token") String selectionToken) { }

    public record Launch(String type,@JsonProperty("preset_id")String presetId,
                         @JsonProperty("model_id")String modelId,
                         @JsonProperty("expected_preset_revision")Long expectedPresetRevision,
                         @JsonProperty("startup_command")String startupCommand,
                         @JsonProperty("recovery_preset_id")String recoveryPresetId) {
        void validate(){
            switch(type==null?"":type){
                case "preset" -> requireAbsent(startupCommand,recoveryPresetId);
                case "startup" -> requireAbsent(presetId,modelId,expectedPresetRevision);
                case "inherit_orchestrator" -> requireAbsent(presetId,modelId,expectedPresetRevision,
                        startupCommand,recoveryPresetId);
                default -> throw new InvalidLaunchRequest("LAUNCH_CONTRACT_CONFLICT",
                        "unsupported launch type: "+type);
            }
        }
        private static void requireAbsent(Object... values){
            for(Object value:values)if(value!=null)throw new InvalidLaunchRequest(
                    "LAUNCH_CONTRACT_CONFLICT","launch contains fields that do not apply to its type");
        }
    }
}
