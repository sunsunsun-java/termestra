package dev.termestra.team.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.execution.application.exception.InvalidLaunchRequest;
import java.util.List;

final class TeamRequests {
    private TeamRequests() { }
    record Send(@JsonProperty("project_id") String projectId, @JsonProperty("from_agent_id") String fromAgentId,
                String token, String to, String text,
                @JsonProperty("runtime_port") String runtimePort,
                @JsonProperty("idempotency_key") String idempotencyKey) { }
    record Cancel(@JsonProperty("project_id") String projectId, @JsonProperty("from_agent_id") String fromAgentId,
                  String token, @JsonProperty("dispatch_id") String dispatchId, String reason) { }
    record Report(@JsonProperty("project_id") String projectId, @JsonProperty("from_agent_id") String fromAgentId,
                  String token, @JsonProperty("dispatch_id") String dispatchId, String result, String status, List<String> artifacts) { }
    record Worker(String name, String description, String role,Boolean autostart,
                  @JsonProperty("startup_command")String startupCommand,
                  @JsonProperty("command_preset_id")String commandPresetId,Launch launch) { }
    record Launch(String type,@JsonProperty("preset_id")String presetId,
                  @JsonProperty("model_id")String modelId,
                  @JsonProperty("expected_preset_revision")Long expectedPresetRevision,
                  @JsonProperty("expected_source_revision")Long expectedSourceRevision,
                  @JsonProperty("startup_command")String startupCommand,
                  @JsonProperty("recovery_preset_id")String recoveryPresetId) {
        void validate(){
            switch(type==null?"":type){
                case "preset" -> requireAbsent(expectedSourceRevision,startupCommand,recoveryPresetId);
                case "startup" -> requireAbsent(presetId,modelId,expectedPresetRevision,expectedSourceRevision);
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
