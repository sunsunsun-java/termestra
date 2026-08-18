package dev.termestra.team.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.team.application.port.in.DispatchSummaryView;
import dev.termestra.team.application.port.in.TeamInputLimits;

import java.util.List;

public record DispatchSummaryResponse(String id, @JsonProperty("workspace_id") String workspaceId,
                                      @JsonProperty("from_agent_id") String fromAgentId,
                                      @JsonProperty("to_agent_id") String toAgentId, String text, String state,
                                      @JsonProperty("created_at") long createdAt,
                                      @JsonProperty("delivered_at") Long deliveredAt,
                                      @JsonProperty("submitted_at") Long submittedAt,
                                      @JsonProperty("reported_at") Long reportedAt,
                                      @JsonProperty("report_text") String reportText, List<String> artifacts,
                                      boolean truncated,
                                      @JsonProperty("delivery_state") String deliveryState,
                                      @JsonProperty("delivery_attempt_count") int deliveryAttemptCount,
                                      @JsonProperty("delivery_error") String deliveryError,
                                      @JsonProperty("delivery_next_attempt_at") Long deliveryNextAttemptAt,
                                      @JsonProperty("delivery_input_attempted") boolean deliveryInputAttempted) {
    static DispatchSummaryResponse from(DispatchSummaryView value) {
        List<String> artifacts = TeamInputLimits.boundedArtifacts(value.artifacts());
        return new DispatchSummaryResponse(value.id(), value.workspaceId(), value.fromAgentId(), value.toAgentId(),
                value.text(), value.state(), value.createdAt(), value.deliveredAt(), value.submittedAt(),
                value.reportedAt(), value.reportText(), artifacts,
                value.truncated() || !artifacts.equals(value.artifacts()), value.deliveryState(),
                value.deliveryAttemptCount(), value.deliveryError(), value.deliveryNextAttemptAt(),
                value.deliveryInputAttempted());
    }
}
