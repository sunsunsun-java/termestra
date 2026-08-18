package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Atomic global/per-workspace active-process budget. */
final class RunCapacityBudget {
    private final int globalMaximum;
    private final int workspaceMaximum;
    private final Map<String, Integer> workspaces = new HashMap<>();
    private int global;

    RunCapacityBudget(int globalMaximum, int workspaceMaximum) {
        if (globalMaximum <= 0) throw new IllegalArgumentException("globalMaximum must be positive");
        if (workspaceMaximum <= 0) throw new IllegalArgumentException("workspaceMaximum must be positive");
        if (workspaceMaximum > globalMaximum) throw new IllegalArgumentException("workspaceMaximum cannot exceed globalMaximum");
        this.globalMaximum = globalMaximum;
        this.workspaceMaximum = workspaceMaximum;
    }

    synchronized Lease reserve(String workspaceId) {
        int workspaceCount = workspaces.getOrDefault(workspaceId, 0);
        if (global >= globalMaximum) throw new ExecutionConflict("Global active agent run capacity exceeded");
        if (workspaceCount >= workspaceMaximum) {
            throw new ExecutionConflict("Workspace active agent run capacity exceeded");
        }
        global++;
        workspaces.put(workspaceId, workspaceCount + 1);
        return new Lease(workspaceId);
    }

    final class Lease implements AutoCloseable {
        private final String workspaceId;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(String workspaceId) { this.workspaceId = workspaceId; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (RunCapacityBudget.this) {
                global--;
                int remaining = workspaces.getOrDefault(workspaceId, 1) - 1;
                if (remaining == 0) workspaces.remove(workspaceId); else workspaces.put(workspaceId, remaining);
            }
        }
    }
}
