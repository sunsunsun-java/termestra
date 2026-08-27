package dev.termestra.workspace.application.exception;

public final class WorkspaceRegistrationNotFound extends RuntimeException {
    public WorkspaceRegistrationNotFound(String registrationId) {
        super("Workspace registration not found: " + registrationId);
    }
}
