package dev.termestra.team.application.port.in;

import java.util.List;

public record DispatchSummaryView(String id, String workspaceId, String fromAgentId, String toAgentId,
                                  String text, String state, long createdAt, Long deliveredAt, Long submittedAt,
                                  Long reportedAt, String reportText, List<String> artifacts, boolean truncated,
                                  String deliveryState, int deliveryAttemptCount, String deliveryError,
                                  Long deliveryNextAttemptAt, boolean deliveryInputAttempted) {
    public DispatchSummaryView {
        artifacts = List.copyOf(artifacts);
    }
}
