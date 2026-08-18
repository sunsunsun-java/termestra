package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.domain.model.WorkspacePath;

/** Ensures the workspace-local files required by an agent exist before runtime startup. */
public interface WorkspaceMetadataInitializer {
    void initialize(WorkspacePath workspacePath);
}
