package dev.termestra.workspace.application.exception;

public final class WorkspaceNotFound extends RuntimeException {
    public WorkspaceNotFound(String workspaceId) { super("Workspace not found: " + workspaceId); }
}
