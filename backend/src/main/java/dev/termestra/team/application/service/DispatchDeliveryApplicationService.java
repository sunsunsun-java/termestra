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

/** Executes durable Team dispatch and report deliveries without exposing PTY timing to request threads. */
public final class DispatchDeliveryApplicationService implements DispatchDeliveryUseCase {
    static final int MAX_AUTOMATIC_ATTEMPTS = DeliveryRetryPolicy.MAX_AUTOMATIC_ATTEMPTS;
    /**
     * An active-worker delivery can legitimately spend 2 seconds acquiring its runtime
     * coordinator, 30 seconds waiting for a CLI prompt, and 3 seconds completing paste
     * acknowledgement. Ninety seconds leaves scheduling and SQLite acknowledgement margin.
     * Cold startup is asynchronous and defers its claim without holding this lease while waiting.
     */
    private static final Duration LEASE_DURATION = Duration.ofSeconds(90);
    private static final Duration RUNTIME_BUSY_RETRY_DELAY = Duration.ofSeconds(1);

    private final TeamLedger ledger;
    private final TeamMemberRepository members;
    private final AgentTeamNotifier notifier;
    private final RuntimeOperationCoordinator operations;
    private final Clock clock;
    private final String leaseOwner = UUID.randomUUID().toString();
    private final java.util.concurrent.atomic.AtomicInteger deliveryTurn = new java.util.concurrent.atomic.AtomicInteger();
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
        return (deliveryTurn.getAndIncrement() & 1) == 0
                ? processReport(now) || processDispatch(now)
                : processDispatch(now) || processReport(now);
    }

    private boolean processReport(Instant now) {
        Optional<ReportDeliveryWork> report = ledger.claimNextReportDelivery(now, now.plus(LEASE_DURATION));
        if (report.isEmpty()) return false;
        deliverReport(report.orElseThrow());
        return true;
    }

    private boolean processDispatch(Instant now) {
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

    private void deliverReport(ReportDeliveryWork work) {
        var dispatch = work.dispatch().dispatch();
        DeliveryResult delivery;
        try {
            delivery = operations.withWorkspace(dispatch.workspaceId().toString(), () -> {
                Optional<TeamMember> worker = members.findById(dispatch.workspaceId().toString(), dispatch.toAgentId().toString());
                return worker.isEmpty() ? DeliveryResult.unavailable("Worker no longer exists")
                        : notifier.report(dispatch, worker.orElseThrow());
            });
        } catch (RuntimeOperationBusyException busy) {
            delivery = DeliveryResult.deferred(busy.getMessage());
        } catch (RuntimeOperationInterruptedException interrupted) {
            finishReport(work, ReportDeliveryWork.Outcome.DEFERRED, interrupted.getMessage(), RUNTIME_BUSY_RETRY_DELAY);
            throw interrupted;
        } catch (RuntimeException unknown) {
            delivery = DeliveryResult.uncertain("Report notification has an unknown terminal outcome: " + unknown.getMessage());
        }
        if (delivery.forwarded()) {
            finishReport(work, ReportDeliveryWork.Outcome.SUBMITTED, null, Duration.ZERO);
        } else if (delivery.deferred()) {
            finishReport(work, ReportDeliveryWork.Outcome.DEFERRED, delivery.error(), RUNTIME_BUSY_RETRY_DELAY);
        } else if (delivery.inputAttempted() || delivery.uncertain()) {
            finishReport(work, ReportDeliveryWork.Outcome.UNCERTAIN, delivery.error(), Duration.ZERO);
        } else {
            var decision = retryPolicy.afterFailure(work.attemptCount());
            finishReport(work, decision.retry() ? ReportDeliveryWork.Outcome.RETRY : ReportDeliveryWork.Outcome.FAILED,
                    delivery.error(), decision.retry() ? decision.delay() : Duration.ZERO);
        }
    }

    private void finishReport(ReportDeliveryWork work, ReportDeliveryWork.Outcome outcome, String error, Duration delay) {
        Instant now = Instant.now(clock);
        ledger.finishReportDelivery(work.attemptId(), outcome, error == null ? null : boundedError(error), now.plus(delay), now);
    }

    private void deferClaim(DispatchDeliveryWork work, String reason) {
        Instant now = Instant.now(clock);
        // Contention and ongoing startup have not attempted input. A short durable delay avoids
        // repeatedly claiming and rewriting the same row while the runtime is not ready.
        ledger.deferDeliveryClaim(work.attemptId(), boundedError(reason),
                now.plus(RUNTIME_BUSY_RETRY_DELAY), now);
    }

    private void deliver(DispatchDeliveryWork work) {
        if (!ledger.isDeliveryClaimActive(work.attemptId(), Instant.now(clock))) return;
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
        if (delivery.deferred()) {
            deferClaim(work, error);
            return;
        }
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

    @Override public java.util.List<dev.termestra.team.application.port.in.ReportDeliveryIssue> reportIssues(String workspaceId, int limit) {
        if (limit < 0 || limit > 100) throw new dev.termestra.team.application.exception.TeamBadRequest("limit must be between 0 and 100");
        return ledger.listReportDeliveryIssues(workspaceId, limit);
    }

    @Override public boolean retryReport(String workspaceId, String dispatchId, boolean confirmUncertain) {
        return ledger.retryReportDelivery(workspaceId, dispatchId, confirmUncertain, Instant.now(clock));
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
