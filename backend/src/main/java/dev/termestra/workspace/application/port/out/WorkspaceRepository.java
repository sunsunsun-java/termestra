package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.domain.model.Workspace;
import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository {
    List<Workspace> findAll();
    Optional<Workspace> find(String workspaceId);
    default Optional<Workspace> findByCanonicalPath(String canonicalPath) {
        return findAll().stream().filter(workspace -> workspace.path().value().equals(canonicalPath)).findFirst();
    }
    boolean delete(String workspaceId);
}
