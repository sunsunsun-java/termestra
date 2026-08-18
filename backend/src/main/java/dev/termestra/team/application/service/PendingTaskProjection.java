package dev.termestra.team.application.service;

import dev.termestra.team.application.port.out.OpenDispatchCountSource;
import dev.termestra.team.application.port.in.TeamInputLimits;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Process-local projection of durable open dispatches.
 *
 * <p>This cache never attempts to replay database mutations as in-memory deltas. A concurrent
 * eviction/reload can already observe a just-committed mutation, so applying a later {@code +1}
 * or {@code -1} would double-count it. Writers therefore invalidate after commit and readers
 * rebuild from the durable ledger.</p>
 */
public final class PendingTaskProjection {
    public static final int MAX_TRACKED_WORKSPACES = 256;
    public static final int MAX_TRACKED_WORKERS_PER_WORKSPACE =
            OpenDispatchCountSource.MAX_TRACKED_WORKERS_PER_WORKSPACE;
    public static final int MAX_PENDING_TASKS_PER_WORKER =
            OpenDispatchCountSource.MAX_PENDING_TASKS_PER_WORKER;
    private final OpenDispatchCountSource source;
    private final Object monitor = new Object();
    private final LinkedHashMap<String, LinkedHashMap<String, Integer>> workspaces =
            new LinkedHashMap<>(16, 0.75f, true);

    public PendingTaskProjection(OpenDispatchCountSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public Map<String, Integer> snapshot(String workspaceId) {
        synchronized (monitor) {
            return Map.copyOf(loadedWorkspace(workspaceId));
        }
    }

    /** Must be called only after the owning SQLite mutation commits. */
    public void invalidate(String workspaceId) {
        synchronized (monitor) {
            workspaces.remove(workspaceId);
        }
    }

    private LinkedHashMap<String, Integer> loadedWorkspace(String workspaceId) {
        LinkedHashMap<String, Integer> counts = workspaces.get(workspaceId);
        if (counts != null) return counts;
        if (workspaces.size() == MAX_TRACKED_WORKSPACES) {
            var eldest = workspaces.entrySet().iterator();
            if (eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
        counts = loadWorkspace(workspaceId);
        workspaces.put(workspaceId, counts);
        return counts;
    }

    private LinkedHashMap<String, Integer> loadWorkspace(String workspaceId) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.loadOpenCounts(workspaceId).entrySet()) {
            if (counts.size() == MAX_TRACKED_WORKERS_PER_WORKSPACE) break;
            if (boundedWorkerId(entry.getKey()) && entry.getValue() != null && entry.getValue() > 0) {
                counts.put(entry.getKey(), Math.min(entry.getValue(), MAX_PENDING_TASKS_PER_WORKER));
            }
        }
        return counts;
    }

    private static boolean boundedWorkerId(String workerId) {
        return workerId != null && !workerId.isBlank()
                && workerId.length() <= TeamInputLimits.MAX_MEMBER_ID_CHARACTERS;
    }
}
