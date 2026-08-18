package dev.termestra.workspace.application.service;

import dev.termestra.workspace.application.port.out.WorkspaceOpener;
import dev.termestra.workspace.application.port.out.WorkspaceRegistration;
import dev.termestra.workspace.application.port.out.WorkspaceRepository;
import dev.termestra.workspace.domain.model.Workspace;
import dev.termestra.workspace.domain.model.WorkspaceName;
import dev.termestra.workspace.domain.model.WorkspacePath;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenWorkspaceServiceTest {
    @Test void acceptsIntellijIdeaAndPassesItsStableTargetIdToTheOpener() {
        Workspace workspace = Workspace.create(
                new WorkspaceName("Lab"), new WorkspacePath("/workspace"), Instant.now());
        AtomicReference<String> target = new AtomicReference<>();
        WorkspaceOpener opener = (path, targetId) -> {
            target.set(targetId);
            return new WorkspaceOpener.OpenResult(true, targetId, null);
        };
        OpenWorkspaceService service = new OpenWorkspaceService(
                new SingleWorkspaceRepository(workspace), opener);

        var result = service.open(workspace.id().toString(), "intellij-idea");

        assertTrue(result.ok());
        assertEquals("intellij-idea", result.effectiveTargetId());
        assertEquals("intellij-idea", target.get());
        assertFalse(service.supports("vscode-insiders"));
    }

    private record SingleWorkspaceRepository(Workspace workspace) implements WorkspaceRepository {
        @Override public WorkspaceRegistration register(Workspace candidate) {
            return new WorkspaceRegistration(workspace, false);
        }
        @Override public List<Workspace> findAll() { return List.of(workspace); }
        @Override public Optional<Workspace> find(String workspaceId) {
            return workspace.id().toString().equals(workspaceId)
                    ? Optional.of(workspace) : Optional.empty();
        }
        @Override public boolean delete(String workspaceId) { return false; }
    }
}
