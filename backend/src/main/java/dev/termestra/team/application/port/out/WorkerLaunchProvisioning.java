package dev.termestra.team.application.port.out;

/** Durable provisioning input; source snapshots remain unresolved until the owning SQLite transaction. */
public sealed interface WorkerLaunchProvisioning {
    record Resolved(WorkerLaunchPlan plan) implements WorkerLaunchProvisioning { }
    record SourceSnapshot(String sourceAgentId, Long expectedSourceRevision)
            implements WorkerLaunchProvisioning { }
}
