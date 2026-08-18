package dev.termestra.workspace.application.exception;

public final class WorkspaceLimitReached extends RuntimeException {
    public WorkspaceLimitReached(int limit) { super("Workspace limit reached: " + limit); }
}
