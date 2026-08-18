package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.domain.model.Workspace;

import java.util.Objects;

public record WorkspaceRegistration(Workspace workspace, boolean created) {
    public WorkspaceRegistration {
        Objects.requireNonNull(workspace, "workspace must not be null");
    }
}
