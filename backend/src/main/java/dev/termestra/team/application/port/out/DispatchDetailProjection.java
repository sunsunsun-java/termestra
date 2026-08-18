package dev.termestra.team.application.port.out;

import java.util.List;

public record DispatchDetailProjection(String id, String workspaceId, String fromAgentId, String toAgentId,
                                       String text, String state, long createdAt, Long deliveredAt, Long submittedAt,
                                       Long reportedAt, String reportText, List<String> artifacts, boolean truncated,
                                       String deliveryState, int deliveryAttemptCount, String deliveryError,
                                       Long deliveryNextAttemptAt, boolean deliveryInputAttempted) {
    public DispatchDetailProjection {
        artifacts = List.copyOf(artifacts);
    }
}
