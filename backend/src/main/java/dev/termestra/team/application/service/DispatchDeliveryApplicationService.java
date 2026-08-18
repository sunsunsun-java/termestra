package dev.termestra.team.application.service;

import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.shared.concurrency.RuntimeOperationBusyException;
import dev.termestra.shared.concurrency.RuntimeOperationInterruptedException;
import dev.termestra.team.application.port.in.DispatchDeliveryUseCase;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.TeamMember;
import dev.termestra.team.domain.model.DeliveryRetryPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Executes the durable Team delivery queue without exposing PTY timing to request threads. */
public final class DispatchDeliveryApplicationService implements DispatchDeliveryUseCase {
    static final int MAX_AUTOMATIC_ATTEMPTS = DeliveryRetryPolicy.MAX_AUTOMATIC_ATTEMPTS;
    /**
     * An active-worker delivery can legitimately spend 2 seconds acquiring its runtime
     * coordinator, 30 seconds waiting for a CLI prompt, and 3 seconds completing paste
     * acknowledgement. A cold worker may require a second prompt/paste cycle while it starts.
     * Ninety seconds covers both bounded cycles plus scheduling and SQLite acknowledgement margin.
     */
    private static final Duration LEASE_DURATION = Duration.ofSeconds(90);
    private static final Duration RUNTIME_BUSY_RETRY_DELAY = Duration.ofSeconds(1);

    private final TeamLedger ledger;
    private final TeamMemberRepository members;
    private final AgentTeamNotifier notifier;
    private final RuntimeOperationCoordinator operations;
    private final Clock clock;
    private final String leaseOwner = UUID.randomUUID().toString();
    private final DeliveryRetryPolicy retryPolicy = new DeliveryRetryPolicy();

    public DispatchDeliveryApplicationService(TeamLedger ledger, TeamMemberRepository members,
                                              AgentTeamNotifier notifier,
                                              RuntimeOperationCoordinator operations, Clock clock) {
        this.ledger = ledger;
        this.members = members;
        this.notifier = notifier;
        this.operations = operations;
        this.clock = clock;
    }

    @Override public boolean processNext() {
        Instant now = Instant.now(clock);
        Optional<DispatchDeliveryWork> claimed = ledger.claimNextDelivery(
                leaseOwner, now, now.plus(LEASE_DURATION));
        if (claimed.isEmpty()) return false;
        DispatchDeliveryWork work = claimed.orElseThrow();
        try {
            operations.withAgent(work.dispatch().dispatch().workspaceId().toString(),
                    work.dispatch().dispatch().toAgentId().toString(), () -> {
                        deliver(work);
                        return null;
                    });
        } catch (RuntimeOperationBusyException busy) {
            deferClaim(work, busy.getMessage());
        } catch (RuntimeOperationInterruptedException interrupted) {
            deferClaim(work, interrupted.getMessage());
            throw interrupted;
        }
        return true;
    }

    private void deferClaim(DispatchDeliveryWork work, String reason) {
        Instant now = Instant.now(clock);
        // This is scheduling contention, not a delivery attempt. A short durable delay avoids
        // repeatedly claiming and rewriting the same row while the runtime resource stays busy.
        ledger.deferDeliveryClaim(work.attemptId(), boundedError(reason),
                now.plus(RUNTIME_BUSY_RETRY_DELAY), now);
    }

    private void deliver(DispatchDeliveryWork work) {
        var dispatch = work.dispatch().dispatch();
        Optional<TeamMember> member = members.findById(dispatch.workspaceId().toString(),
                dispatch.toAgentId().toString());
        if (member.isEmpty()) {
            definiteFailure(work, "Worker is no longer active");
            return;
        }
        DeliveryResult delivery;
        try {
            delivery = notifier.deliver(dispatch, member.orElseThrow(), work.runtimePort());
        } catch (RuntimeException unknownOutcome) {
            ledger.markDeliveryUncertain(work.attemptId(),
                    boundedError("Delivery adapter failed with an unknown outcome: " + unknownOutcome.getMessage()),
                    Instant.now(clock));
            return;
        }
        Instant completedAt = Instant.now(clock);
        if (delivery.forwarded()) {
            ledger.markDeliverySubmitted(work.attemptId(), completedAt);
            return;
        }
        String error = boundedError(delivery.error() == null ? "Worker input was not accepted" : delivery.error());
        if (delivery.inputAttempted() || delivery.uncertain()) {
            ledger.markDeliveryUncertain(work.attemptId(), error, completedAt);
            return;
        }
        definiteFailure(work, error);
    }

    private void definiteFailure(DispatchDeliveryWork work, String error) {
        Instant now = Instant.now(clock);
        DeliveryRetryPolicy.RetryDecision decision = retryPolicy.afterFailure(work.attemptCount());
        if (!decision.retry()) {
            ledger.markDeliveryFailed(work.attemptId(), boundedError(error), now);
            return;
        }
        ledger.rescheduleDelivery(work.attemptId(), boundedError(error), now.plus(decision.delay()), now);
    }

    @Override public int recoverInterrupted() {
        return ledger.recoverInterruptedDeliveries(Instant.now(clock));
    }

    @Override public boolean retry(String workspaceId, String dispatchId) {
        boolean retried = ledger.retryDelivery(workspaceId, dispatchId, Instant.now(clock));
        return retried;
    }

    private static String boundedError(String value) {
        String safe = value == null ? "Unknown delivery failure" : value;
        return safe.length() <= 2_048 ? safe : safe.substring(0, 2_048);
    }
}
