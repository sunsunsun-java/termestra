package dev.termestra.team.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.team.application.port.in.DispatchView;
import java.util.List;

public record DispatchResponse(String id, @JsonProperty("workspace_id") String workspaceId,
                               @JsonProperty("from_agent_id") String fromAgentId,
                               @JsonProperty("to_agent_id") String toAgentId, String text, String state,
                               @JsonProperty("created_at") long createdAt, @JsonProperty("delivered_at") Long deliveredAt,
                               @JsonProperty("submitted_at") Long submittedAt, @JsonProperty("reported_at") Long reportedAt,
                               @JsonProperty("report_text") String reportText, List<String> artifacts, boolean truncated,
                               @JsonProperty("delivery_state") String deliveryState,
                               @JsonProperty("delivery_attempt_count") int deliveryAttemptCount,
                               @JsonProperty("delivery_error") String deliveryError,
                               @JsonProperty("delivery_next_attempt_at") Long deliveryNextAttemptAt,
                               @JsonProperty("delivery_input_attempted") boolean deliveryInputAttempted) {
    static DispatchResponse from(DispatchView v) { return new DispatchResponse(v.id(),v.workspaceId(),v.fromAgentId(),v.toAgentId(),v.text(),v.state(),v.createdAt(),v.deliveredAt(),v.submittedAt(),v.reportedAt(),v.reportText(),v.artifacts(),v.truncated(),v.deliveryState(),v.deliveryAttemptCount(),v.deliveryError(),v.deliveryNextAttemptAt(),v.deliveryInputAttempted()); }
}
