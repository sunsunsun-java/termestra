package dev.termestra.team.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
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
                  @JsonProperty("command_preset_id")String commandPresetId) { }
}
