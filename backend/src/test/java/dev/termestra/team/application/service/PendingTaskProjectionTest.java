package dev.termestra.team.application.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PendingTaskProjectionTest {
    @Test void hydratesOnceAndReloadsOnlyAfterCommittedMutationInvalidation() {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<Map<String, Integer>> durable = new AtomicReference<>(Map.of("worker-1", 2));
        PendingTaskProjection projection = new PendingTaskProjection(workspaceId -> {
            loads.incrementAndGet();
            return durable.get();
        });

        assertEquals(2, projection.snapshot("workspace-1").get("worker-1"));
        assertEquals(2, projection.snapshot("workspace-1").get("worker-1"));
        assertEquals(1, loads.get());

        durable.set(Map.of("worker-1", 3));
        projection.invalidate("workspace-1");
        assertEquals(3, projection.snapshot("workspace-1").get("worker-1"));
        assertEquals(2, loads.get());
    }

    @Test void capsHydratedWorkersAndSaturatesCountsWithoutOverflow() {
        LinkedHashMap<String, Integer> legacy = new LinkedHashMap<>();
        for (int index = 0; index < PendingTaskProjection.MAX_TRACKED_WORKERS_PER_WORKSPACE + 20; index++) {
            legacy.put("worker-" + index, 1);
        }
        legacy.put("saturated", Integer.MAX_VALUE);
        legacy.put("x".repeat(300), 1);
        PendingTaskProjection projection = new PendingTaskProjection(ignored -> legacy);

        Map<String, Integer> hydrated = projection.snapshot("workspace-1");

        assertEquals(1, hydrated.get("worker-0"));
        assertEquals(0, hydrated.getOrDefault("worker-256", 0));
        // Load the saturated value in its own projection so insertion order cannot place it past the cap.
        PendingTaskProjection saturated = new PendingTaskProjection(
                ignored -> Map.of("worker", Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, saturated.snapshot("workspace-2").get("worker"));
    }

    @Test void evictsAndReloadsWorkspaceCachesInsteadOfReportingFalseZeroes() {
        AtomicInteger loads = new AtomicInteger();
        PendingTaskProjection projection = new PendingTaskProjection(ignored -> {
            loads.incrementAndGet();
            return Map.of("worker", 1);
        });

        for (int index = 0; index < PendingTaskProjection.MAX_TRACKED_WORKSPACES + 10; index++) {
            projection.snapshot("workspace-" + index);
        }

        assertEquals(1, projection.snapshot("workspace-0").get("worker"));
        assertEquals(PendingTaskProjection.MAX_TRACKED_WORKSPACES + 11, loads.get());
    }

    @Test void postCommitInvalidationCannotDoubleApplyAfterAnEvictionReload() {
        AtomicInteger durableCount = new AtomicInteger();
        PendingTaskProjection projection = new PendingTaskProjection(workspaceId ->
                "workspace-1".equals(workspaceId)
                        ? Map.of("worker", durableCount.get()) : Map.of());

        assertEquals(0, projection.snapshot("workspace-1").getOrDefault("worker", 0));
        durableCount.set(1); // The SQLite mutation commits before cache invalidation.
        for (int index = 0; index < PendingTaskProjection.MAX_TRACKED_WORKSPACES; index++) {
            projection.snapshot("other-" + index);
        }
        assertEquals(1, projection.snapshot("workspace-1").get("worker"),
                "an intervening reload may already include the committed mutation");

        projection.invalidate("workspace-1");

        assertEquals(1, projection.snapshot("workspace-1").get("worker"),
                "completion invalidates and reloads; it never applies a second in-memory delta");
    }

    @Test void invalidationCannotBeOvertakenByInFlightHydration() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch hydrationEntered = new CountDownLatch(1);
        CountDownLatch releaseHydration = new CountDownLatch(1);
        PendingTaskProjection projection = new PendingTaskProjection(ignored -> {
            loads.incrementAndGet();
            hydrationEntered.countDown();
            try {
                if (!releaseHydration.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test hydration release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test hydration interrupted", interrupted);
            }
            return Map.of("worker-1", 1);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var hydration = executor.submit(() -> projection.snapshot("workspace-1"));
            org.junit.jupiter.api.Assertions.assertTrue(hydrationEntered.await(1, TimeUnit.SECONDS));
            var invalidation = executor.submit(() -> projection.invalidate("workspace-1"));

            org.junit.jupiter.api.Assertions.assertThrows(TimeoutException.class,
                    () -> invalidation.get(100, TimeUnit.MILLISECONDS));
            releaseHydration.countDown();
            hydration.get(2, TimeUnit.SECONDS);
            invalidation.get(2, TimeUnit.SECONDS);
        } finally {
            releaseHydration.countDown();
        }

        assertEquals(1, projection.snapshot("workspace-1").get("worker-1"));
        assertEquals(2, loads.get(), "the invalidated in-flight value must be reloaded");
    }
}
