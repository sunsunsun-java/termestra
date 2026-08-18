package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;

/** One bounded FIFO and one virtual worker own all automatic input for a run. */
final class AutomaticInputMailbox implements AutoCloseable {
    interface SubmissionHandler { long submit(String text, long readyAfterPosition); }

    private static final int MAX_PENDING = 64;
    private final String runId;
    private final SubmissionHandler handler;
    private final ArrayBlockingQueue<Request> queue = new ArrayBlockingQueue<>(MAX_PENDING, true);
    private final Object admission = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread worker;

    AutomaticInputMailbox(String runId, SubmissionHandler handler) {
        this.runId = runId;
        this.handler = handler;
        worker = Thread.ofVirtual().name("termestra-input-mailbox-" + runId).unstarted(this::consume);
        worker.start();
    }

    long submit(String text) {
        Request request = new Request(Objects.requireNonNull(text, "text"), new CompletableFuture<>());
        synchronized (admission) {
            if (closed.get()) throw new ExecutionConflict("PTY is not active for run: " + runId);
            if (!queue.offer(request)) {
                throw new ExecutionConflict("Automatic terminal input queue is full for run: " + runId);
            }
        }
        try {
            return request.result().get();
        } catch (InterruptedException interrupted) {
            boolean definitelyNotStarted = queue.remove(request);
            request.result().cancel(false);
            Thread.currentThread().interrupt();
            throw new InteractiveInputSubmitter.SubmissionException(
                    "Automatic terminal input was interrupted", !definitelyNotStarted, interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Automatic terminal input failed", cause);
        }
    }

    private void consume() {
        long readyAfterPosition = -1;
        try {
            while (!closed.get()) {
                Request request = queue.take();
                if (request.result().isCancelled()) continue;
                try {
                    readyAfterPosition = handler.submit(request.text(), readyAfterPosition);
                    request.result().complete(readyAfterPosition);
                } catch (RuntimeException failure) {
                    request.result().completeExceptionally(failure);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            failPending();
        }
    }

    private void failPending() {
        List<Request> pending = new ArrayList<>();
        queue.drainTo(pending);
        for (Request request : pending) request.result().completeExceptionally(
                new ExecutionConflict("PTY is not active for run: " + runId));
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (admission) {
            failPending();
        }
        worker.interrupt();
    }

    private record Request(String text, CompletableFuture<Long> result) { }
}
