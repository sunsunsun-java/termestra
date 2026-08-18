package dev.termestra.team.application.service;

import dev.termestra.team.application.port.in.RemoveWorkerUseCase;
import dev.termestra.team.application.port.in.TeamAdminUseCase;
import dev.termestra.team.application.port.out.WorkerRuntimeCleaner;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;

/**
 * Owns the cross-context ordering rule for removing a worker.
 * SQLite is authoritative, so runtime state is cleared only after the durable
 * team deletion has committed.
 */
public final class WorkerRemovalService implements RemoveWorkerUseCase {
    private final TeamAdminUseCase team;
    private final WorkerRuntimeCleaner runtime;
    private final RuntimeOperationCoordinator operations;

    public WorkerRemovalService(TeamAdminUseCase team, WorkerRuntimeCleaner runtime) {
        this(team, runtime, new RuntimeOperationCoordinator());
    }

    public WorkerRemovalService(TeamAdminUseCase team, WorkerRuntimeCleaner runtime,
                                RuntimeOperationCoordinator operations) {
        this.team = team;
        this.runtime = runtime;
        this.operations = operations;
    }

    @Override public void remove(String workspaceId, String workerId) {
        operations.withAgent(workspaceId, workerId, () -> {
            team.deleteWorker(workspaceId, workerId);
            runtime.stopAndForget(workspaceId, workerId);
        });
    }
}
