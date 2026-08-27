package dev.termestra.workspace.application.service;

import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.workspace.application.port.out.WorkspaceRepository;
import dev.termestra.workspace.application.port.out.WorkspaceRuntimeCleaner;
import dev.termestra.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceApplicationServiceTest {
    @Test void commitsWorkspaceDeletionBeforeClearingRuntimeState() {
        List<String> events = new ArrayList<>();
        WorkspaceApplicationService service = service(new DeleteRepository(events, true, null),
                workspaceId -> events.add("runtime"));

        service.delete("workspace-1");

        assertEquals(List.of("database", "runtime"), events);
    }

    @Test void leavesRuntimeStateIntactWhenWorkspaceDeletionDoesNotCommit() {
        List<String> events = new ArrayList<>();
        IllegalStateException databaseFailure = new IllegalStateException("database unavailable");
        WorkspaceApplicationService service = service(
                new DeleteRepository(events, false, databaseFailure),
                workspaceId -> events.add("runtime"));

        assertEquals(databaseFailure, assertThrows(IllegalStateException.class,
                () -> service.delete("workspace-1")));
        assertEquals(List.of("database"), events);
    }

    private static WorkspaceApplicationService service(WorkspaceRepository repository,
                                                         WorkspaceRuntimeCleaner cleaner) {
        return new WorkspaceApplicationService(repository, cleaner, new RuntimeOperationCoordinator());
    }

    private static final class DeleteRepository implements WorkspaceRepository {
        private final List<String> events;
        private final boolean deleted;
        private final RuntimeException failure;

        private DeleteRepository(List<String> events, boolean deleted, RuntimeException failure) {
            this.events = events;
            this.deleted = deleted;
            this.failure = failure;
        }

        @Override public List<Workspace> findAll() { return List.of(); }

        @Override public Optional<Workspace> find(String workspaceId) { return Optional.empty(); }

        @Override public boolean delete(String workspaceId) {
            events.add("database");
            if (failure != null) throw failure;
            return deleted;
        }
    }
}
