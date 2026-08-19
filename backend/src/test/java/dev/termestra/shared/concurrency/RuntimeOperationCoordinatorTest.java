package dev.termestra.shared.concurrency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeOperationCoordinatorTest {
    private static final Duration FAST_OPERATION_DEADLINE = Duration.ofSeconds(1);

    @Test
    void busyWorkspaceFailsWithATypedErrorWithinTheConfiguredDeadline() throws Exception {
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator(Duration.ofMillis(100));
        CountDownLatch workspaceEntered = new CountDownLatch(1);
        CountDownLatch releaseWorkspace = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = executor.submit(() -> coordinator.exclusivelyWithWorkspace(
                    "workspace-1",
                    () -> {
                        workspaceEntered.countDown();
                        await(releaseWorkspace);
                    }));

            assertTrue(workspaceEntered.await(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));

            Future<RuntimeOperationBusyException> contender = executor.submit(() ->
                    assertThrows(
                            RuntimeOperationBusyException.class,
                            () -> coordinator.withWorkspace("workspace-1", () -> "unreachable")));

            try {
                RuntimeOperationBusyException busy = contender.get(
                        FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
                assertEquals("workspace", busy.resourceType());
                assertEquals("workspace-1", busy.workspaceId());
                assertNull(busy.agentId());
            } finally {
                releaseWorkspace.countDown();
                holder.get(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    @Test
    void unrelatedWorkspacesDoNotBlockEachOtherWhenTheirLegacyStripesCollide() throws Exception {
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator();
        CountDownLatch firstWorkspaceEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWorkspace = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> firstWorkspace = executor.submit(() -> coordinator.exclusivelyWithWorkspace(
                    "workspace-9",
                    () -> {
                        firstWorkspaceEntered.countDown();
                        await(releaseFirstWorkspace);
                    }));

            assertTrue(firstWorkspaceEntered.await(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));

            Future<String> unrelatedWorkspace = executor.submit(() -> coordinator.withWorkspace(
                    "workspace-13",
                    () -> "completed"));

            try {
                assertEquals("completed", unrelatedWorkspace.get(
                        FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            } finally {
                releaseFirstWorkspace.countDown();
                firstWorkspace.get(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    @Test
    void differentAgentsInTheSameWorkspaceCanRunInParallel() throws Exception {
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator();
        CountDownLatch firstAgentEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAgent = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> firstAgent = executor.submit(() -> coordinator.withAgent(
                    "workspace-1", "agent-1", () -> {
                        firstAgentEntered.countDown();
                        await(releaseFirstAgent);
                    }));

            assertTrue(firstAgentEntered.await(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            Future<String> secondAgent = executor.submit(() -> coordinator.withAgent(
                    "workspace-1", "agent-2", () -> "completed"));

            try {
                assertEquals("completed", secondAgent.get(
                        FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            } finally {
                releaseFirstAgent.countDown();
                firstAgent.get(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        assertEquals(0, coordinator.retainedWorkspaceKeyCount());
        assertEquals(0, coordinator.retainedAgentKeyCount());
    }

    @Test
    void theSameAgentKeyCanBeEnteredReentrantly() {
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator();

        String result = coordinator.withAgent("workspace-1", "agent-1", () ->
                coordinator.withAgent("workspace-1", "agent-1", () -> "nested"));

        assertEquals("nested", result);
        assertEquals(0, coordinator.retainedWorkspaceKeyCount());
        assertEquals(0, coordinator.retainedAgentKeyCount());
    }

    @Test
    void anExclusiveWorkspaceOperationSupportsWriteToReadAndWriteToWriteReentrancy() {
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator();

        String result = coordinator.exclusivelyWithWorkspace("workspace-1", () -> {
            String readResult = coordinator.withWorkspace("workspace-1", () -> "read");
            String writeResult = coordinator.exclusivelyWithWorkspace("workspace-1", () -> "write");
            return readResult + '-' + writeResult;
        });

        assertEquals("read-write", result);
        assertEquals(0, coordinator.retainedWorkspaceKeyCount());
    }

    @Test
    void aReadToWriteUpgradeIsRejectedImmediately() {
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator(Duration.ofSeconds(5));
        long startedAt = System.nanoTime();

        RuntimeOperationNestingException nesting = coordinator.withWorkspace("workspace-1", () ->
                assertThrows(RuntimeOperationNestingException.class,
                        () -> coordinator.exclusivelyWithWorkspace("workspace-1", () -> "unreachable")));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(elapsedMillis < 500,
                () -> "lock upgrade should be rejected before waiting, but took " + elapsedMillis + " ms");
        assertTrue(nesting.getMessage().contains("workspace-1"));
        assertEquals(0, coordinator.retainedWorkspaceKeyCount());
    }

    @Test
    void agentAcquisitionUsesOneDeadlineAcrossWorkspaceAndAgentLocks() {
        Duration acquisitionTimeout = Duration.ofMillis(600);
        long budgetNanos = acquisitionTimeout.toNanos();
        AtomicInteger clockReads = new AtomicInteger();
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator(
                acquisitionTimeout,
                () -> clockReads.getAndIncrement() < 2 ? 0 : budgetNanos);

        RuntimeOperationBusyException busy = assertThrows(RuntimeOperationBusyException.class,
                () -> coordinator.withAgent(
                        "workspace-1", "agent-1", () -> "unreachable"));

        assertEquals("agent", busy.resourceType());
        assertEquals(3, clockReads.get(),
                "one clock origin and one remaining-budget read per lock are expected");
        assertEquals(0, coordinator.retainedWorkspaceKeyCount());
        assertEquals(0, coordinator.retainedAgentKeyCount());
    }

    @Test
    void interruptedAcquisitionPreservesTheThreadInterruptFlag() throws Exception {
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator(Duration.ofSeconds(5));
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        CountDownLatch contenderFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writer = executor.submit(() -> coordinator.exclusivelyWithWorkspace(
                    "workspace-1", () -> {
                        writerEntered.countDown();
                        await(releaseWriter);
                    }));
            assertTrue(writerEntered.await(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));

            Thread contender = Thread.ofVirtual().start(() -> {
                contenderStarted.countDown();
                try {
                    coordinator.withWorkspace("workspace-1", () -> "unreachable");
                } catch (Throwable thrown) {
                    failure.set(thrown);
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                } finally {
                    contenderFinished.countDown();
                }
            });

            assertTrue(contenderStarted.await(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            contender.interrupt();
            assertTrue(contenderFinished.await(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));

            releaseWriter.countDown();
            writer.get(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        }

        assertInstanceOf(RuntimeOperationInterruptedException.class, failure.get());
        assertTrue(interruptPreserved.get());
        assertEquals(0, coordinator.retainedWorkspaceKeyCount());
    }

    @Test
    void exactKeyRegistriesAreEmptyAfterContendedAgentOperationsFinish() throws Exception {
        RuntimeOperationCoordinator coordinator = new RuntimeOperationCoordinator(Duration.ofMillis(75));
        CountDownLatch agentEntered = new CountDownLatch(1);
        CountDownLatch releaseAgent = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = executor.submit(() -> coordinator.withAgent(
                    "workspace-1", "agent-1", () -> {
                        agentEntered.countDown();
                        await(releaseAgent);
                    }));
            assertTrue(agentEntered.await(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));

            Future<?> firstContender = executor.submit(() -> assertThrows(
                    RuntimeOperationBusyException.class,
                    () -> coordinator.withAgent("workspace-1", "agent-1", () -> "unreachable")));
            Future<?> secondContender = executor.submit(() -> assertThrows(
                    RuntimeOperationBusyException.class,
                    () -> coordinator.withAgent("workspace-1", "agent-1", () -> "unreachable")));

            firstContender.get(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            secondContender.get(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            releaseAgent.countDown();
            holder.get(FAST_OPERATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        }

        assertEquals(0, coordinator.retainedWorkspaceKeyCount());
        assertEquals(0, coordinator.retainedAgentKeyCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test operation was interrupted", interrupted);
        }
    }
}
