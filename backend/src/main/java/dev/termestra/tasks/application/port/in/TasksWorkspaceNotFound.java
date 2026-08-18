package dev.termestra.tasks.application.port.in;
public final class TasksWorkspaceNotFound extends RuntimeException {
    public TasksWorkspaceNotFound(String workspaceId) { super("Workspace not found: " + workspaceId); }
}
