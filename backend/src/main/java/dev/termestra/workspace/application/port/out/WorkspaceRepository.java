package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.domain.model.Workspace;
import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository {
    WorkspaceRegistration register(Workspace workspace);
    List<Workspace> findAll();
    Optional<Workspace> find(String workspaceId);
    boolean delete(String workspaceId);
}
