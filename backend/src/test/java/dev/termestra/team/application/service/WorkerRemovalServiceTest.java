package dev.termestra.team.application.service;

import dev.termestra.team.application.port.in.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerRemovalServiceTest {
    @Test void persistsDeletionBeforeClearingTheLiveRuntime() {
        DeletionState state = new DeletionState();
        WorkerRemovalService service = new WorkerRemovalService(
                new RecordingTeam(state, null),
                (workspaceId, workerId) -> {
                    if (state.persisted) {
                        throw new IllegalStateException("runtime was cleared before SQLite committed");
                    }
                    state.runtime = false;
                });

        service.remove("workspace-1", "worker-1");

        assertFalse(state.persisted);
        assertFalse(state.runtime);
    }

    @Test void leavesTheLiveRuntimeIntactWhenPersistenceFails() {
        DeletionState state = new DeletionState();
        IllegalStateException databaseFailure = new IllegalStateException("database unavailable");
        WorkerRemovalService service = new WorkerRemovalService(
                new RecordingTeam(state, databaseFailure),
                (workspaceId, workerId) -> state.runtime = false);

        assertSame(databaseFailure, assertThrows(IllegalStateException.class,
                () -> service.remove("workspace-1", "worker-1")));
        assertTrue(state.runtime);
    }

    private static final class RecordingTeam implements TeamAdminUseCase {
        private final DeletionState state;
        private final RuntimeException failure;

        private RecordingTeam(DeletionState state, RuntimeException failure) {
            this.state = state;
            this.failure = failure;
        }

        @Override public void deleteWorker(String workspaceId, String workerId) {
            if (failure != null) throw failure;
            state.persisted = false;
        }

        @Override public TeamMemberView addWorker(AddWorkerCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override public List<TeamMemberView> listForUi(String workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override public TeamMemberView renameWorker(String workspaceId, String workerId, String name) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class DeletionState {
        private boolean persisted = true;
        private boolean runtime = true;
    }
}
