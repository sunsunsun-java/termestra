package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.out.AgentExecutionRepository;
import dev.termestra.execution.domain.model.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persists terminal run transitions without publishing an uncommitted state.
 *
 * <p>There is at most one scheduled item per run and a hard global bound. A
 * recovered database completes the original transition; a process restart
 * remains the final reconciliation path when the database stays unavailable.
 */
final class TerminalTransitionRetrier implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TerminalTransitionRetrier.class);
    static final int MAX_PENDING = 128;
    private static final long INITIAL_DELAY_MILLIS = 100;
    private static final long MAX_DELAY_MILLIS = 5_000;

    private final AgentExecutionRepository repository;
    private final ScheduledThreadPoolExecutor scheduler;
    private final ConcurrentHashMap<String, PendingTransition> pending = new ConcurrentHashMap<>();
    private final Semaphore pendingCapacity = new Semaphore(MAX_PENDING);
    private final AtomicBoolean closed = new AtomicBoolean();

    TerminalTransitionRetrier(AgentExecutionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.scheduler = new ScheduledThreadPoolExecutor(1,runnable -> {
            Thread thread = new Thread(runnable, "termestra-terminal-persistence-retry");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    RuntimeException persist(String runId, RunStatus status, Integer exitCode, Instant endedAt,
                             String workspaceId,String agentId,String failedResumeSessionId,
                             Runnable onPersisted, Runnable onDurablyMissing,
                             Runnable onRetryAbandoned) {
        Transition transition = new Transition(runId, status, exitCode, endedAt,
                workspaceId,agentId,failedResumeSessionId,
                onPersisted, onDurablyMissing, onRetryAbandoned);
        Outcome outcome;
        try {
            outcome = write(transition);
        } catch (RuntimeException failure) {
            if (!scheduleFirstRetry(transition, failure)) abandon(transition);
            return failure;
        }
        complete(transition, outcome);
        return null;
    }

    void cancel(String runId) {
        PendingTransition item=pending.remove(runId);
        if(item!=null){item.cancel();pendingCapacity.release();}
    }

    private Outcome write(Transition transition) {
        return repository.finishRun(transition.runId(), transition.status(),
                transition.exitCode(), transition.endedAt(),transition.workspaceId(),
                transition.agentId(),transition.failedResumeSessionId())
                ? Outcome.PERSISTED : Outcome.DURABLY_MISSING;
    }

    private boolean scheduleFirstRetry(Transition transition, RuntimeException failure) {
        if (closed.get()) return false;
        if (pending.containsKey(transition.runId())) return true;
        if (!pendingCapacity.tryAcquire()) {
            LOG.error("Terminal persistence retry capacity is exhausted; run {} will be reconciled on restart",
                    transition.runId(), failure);
            return false;
        }
        if (closed.get()) {
            pendingCapacity.release();
            return false;
        }
        PendingTransition candidate = new PendingTransition(transition, INITIAL_DELAY_MILLIS, 1);
        PendingTransition existing = pending.putIfAbsent(transition.runId(), candidate);
        if (existing == null) {
            if (closed.get()) {
                if (pending.remove(transition.runId(), candidate)) pendingCapacity.release();
                return false;
            }
            LOG.warn("Terminal state persistence failed for run {}; retrying in the background ({}: {})",
                    transition.runId(), failure.getClass().getSimpleName(), failure.getMessage());
            return schedule(candidate);
        } else {
            pendingCapacity.release();
            return true;
        }
    }

    private boolean schedule(PendingTransition item) {
        if (closed.get() || pending.get(item.transition().runId()) != item) return false;
        try {
            item.install(scheduler.schedule(() -> retry(item), item.delayMillis(), TimeUnit.MILLISECONDS));
            return true;
        } catch (RejectedExecutionException rejected) {
            if (!closed.get()) {
                boolean removed=pending.remove(item.transition().runId(), item);
                if (removed) pendingCapacity.release();
                LOG.error("Could not schedule terminal persistence retry for run {}",
                        item.transition().runId(), rejected);
                if(removed)abandon(item.transition());
            }
            return true;
        }
    }

    private void retry(PendingTransition item) {
        String runId = item.transition().runId();
        if (closed.get() || pending.get(runId) != item) return;
        Outcome outcome;
        try {
            outcome = write(item.transition());
        } catch (RuntimeException failure) {
            if (shouldLog(item.attempt())) {
                LOG.warn("Terminal state persistence is still failing for run {} after {} attempts",
                        runId, item.attempt(), failure);
            }
            long nextDelay = Math.min(MAX_DELAY_MILLIS, item.delayMillis() * 2);
            PendingTransition next = new PendingTransition(item.transition(), nextDelay, item.attempt() + 1);
            if (pending.replace(runId, item, next)) schedule(next);
            return;
        }
        if (pending.remove(runId, item)) {
            pendingCapacity.release();
            complete(item.transition(), outcome);
        }
    }

    private static boolean shouldLog(int attempt) {
        return attempt >= 4 && (attempt & (attempt - 1)) == 0;
    }

    private static void complete(Transition transition, Outcome outcome) {
        if (outcome == Outcome.PERSISTED) transition.onPersisted().run();
        else transition.onDurablyMissing().run();
    }

    private static void abandon(Transition transition){
        try{transition.onRetryAbandoned().run();}
        catch(RuntimeException failure){LOG.error("Could not discard abandoned terminal run {}",
                transition.runId(),failure);}
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.forEach((runId, ignored) -> {
            PendingTransition item=pending.remove(runId);
            if (item!=null) {
                item.cancel();pendingCapacity.release();abandon(item.transition());
            }
        });
        scheduler.shutdownNow();
    }

    private enum Outcome { PERSISTED, DURABLY_MISSING }

    private record Transition(String runId, RunStatus status, Integer exitCode, Instant endedAt,
                              String workspaceId,String agentId,String failedResumeSessionId,
                              Runnable onPersisted, Runnable onDurablyMissing,
                              Runnable onRetryAbandoned) {
        private Transition {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(endedAt, "endedAt");
            Objects.requireNonNull(workspaceId,"workspaceId");
            Objects.requireNonNull(agentId,"agentId");
            Objects.requireNonNull(onPersisted, "onPersisted");
            Objects.requireNonNull(onDurablyMissing, "onDurablyMissing");
            Objects.requireNonNull(onRetryAbandoned, "onRetryAbandoned");
        }
    }

    private static final class PendingTransition{
        private final Transition transition;private final long delayMillis;private final int attempt;
        private ScheduledFuture<?> future;private boolean cancelled;
        private PendingTransition(Transition transition,long delayMillis,int attempt){this.transition=transition;this.delayMillis=delayMillis;this.attempt=attempt;}
        Transition transition(){return transition;}long delayMillis(){return delayMillis;}int attempt(){return attempt;}
        synchronized void install(ScheduledFuture<?> scheduled){
            if(cancelled)scheduled.cancel(false);else future=scheduled;
        }
        synchronized void cancel(){cancelled=true;if(future!=null)future.cancel(false);}
    }
}
