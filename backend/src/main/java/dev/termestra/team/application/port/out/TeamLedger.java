package dev.termestra.team.application.port.out;

import dev.termestra.team.domain.model.Dispatch;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TeamLedger {
    DispatchEnqueueResult enqueue(Dispatch dispatch, TeamMessage message, String runtimePort,
                                  String idempotencyKey);
    Optional<DispatchDeliveryWork> claimNextDelivery(String leaseOwner, Instant now,
                                                     Instant leaseExpiresAt);
    void deferDeliveryClaim(String attemptId, String reason, Instant nextAttemptAt, Instant updatedAt);
    void markDeliverySubmitted(String attemptId, Instant submittedAt);
    void rescheduleDelivery(String attemptId, String error, Instant nextAttemptAt, Instant updatedAt);
    void markDeliveryUncertain(String attemptId, String error, Instant updatedAt);
    void markDeliveryFailed(String attemptId, String error, Instant updatedAt);
    int recoverInterruptedDeliveries(Instant recoveredAt);
    boolean retryDelivery(String workspaceId, String dispatchId, Instant retriedAt);
    long create(Dispatch dispatch, TeamMessage message);
    void discardCreated(String dispatchId, long messageSequence);
    Optional<StoredDispatch> reportOne(String workspaceId, String workerId, String dispatchId,
                                       String result, List<String> artifacts, Instant reportedAt,
                                       TeamMessage message);
    Optional<StoredDispatch> cancelOne(String workspaceId, String dispatchId, String reason, Instant cancelledAt);
    default Optional<String> findOpenRecipient(String workspaceId, String dispatchId) {
        return findDetailById(workspaceId, dispatchId).map(DispatchDetailProjection::toAgentId);
    }
    void append(TeamMessage message);
    List<DispatchSummaryProjection> listSummaries(String workspaceId, String state, int limit, int offset);
    List<DispatchSummaryProjection> listDeliveryIssues(String workspaceId, int limit);
    Optional<DispatchDetailProjection> findDetailById(String workspaceId, String dispatchId);
    void markDelivered(StoredDispatch dispatch);
}
