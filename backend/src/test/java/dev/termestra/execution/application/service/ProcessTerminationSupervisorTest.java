package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTerminationSupervisorTest {
    @Test
    void retainsOneStuckNativeAttemptWithoutPinningTheCallerOrDuplicatingTheStop() throws Exception {
        ProcessTerminationSupervisor supervisor = new ProcessTerminationSupervisor(Duration.ofMillis(100));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicInteger stopCalls = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean invokedOnVirtualThread = new AtomicBoolean();
        try {
            long started = System.nanoTime();
            ExecutionConflict first = (ExecutionConflict) supervisor.terminate("run-1", () -> {
                stopCalls.incrementAndGet();
                invokedOnVirtualThread.set(Thread.currentThread().isVirtual());
                entered.countDown();
                await(release, interrupted);
                return true;
            }, cleaned::countDown);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(elapsedMillis < 1_000, "the caller must return at the configured deadline");
            assertTrue(first.getMessage().contains("did not finish within the bounded deadline"));

            ExecutionConflict second = (ExecutionConflict) supervisor.terminate("run-1", () -> {
                throw new AssertionError("an in-flight native stop must not be duplicated");
            }, () -> {
                throw new AssertionError("the original callback owns cleanup");
            });
            assertSame(first, second);
            assertEquals(1, stopCalls.get());
            assertFalse(invokedOnVirtualThread.get(), "native termination must stay off virtual threads");

            release.countDown();
            assertTrue(cleaned.await(1, TimeUnit.SECONDS));
            assertEquals(0, supervisor.pendingCount());
            assertFalse(interrupted.get());
        } finally {
            release.countDown();
            supervisor.closeWhenIdle();
        }
    }

    private static void await(CountDownLatch latch, AtomicBoolean interrupted) {
        boolean wasInterrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                wasInterrupted = true;
            }
        }
        if (wasInterrupted) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
        }
    }
}
