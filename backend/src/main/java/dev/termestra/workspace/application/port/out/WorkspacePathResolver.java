package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.domain.model.WorkspacePath;

public interface WorkspacePathResolver {
    WorkspacePath resolveDirectory(String rawPath);
}
