package dev.termestra.workspace.application.port.in;

import dev.termestra.workspace.domain.model.Workspace;

public record WorkspaceView(String id, String name, String path) {
    public static WorkspaceView from(Workspace workspace) {
        return new WorkspaceView(workspace.id().toString(), workspace.name().value(), workspace.path().value());
    }
}
