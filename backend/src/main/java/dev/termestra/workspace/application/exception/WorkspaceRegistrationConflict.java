package dev.termestra.workspace.application.exception;

public final class WorkspaceRegistrationConflict extends RuntimeException {
    private final String errorCode;
    private final String workspaceId;

    public WorkspaceRegistrationConflict(String errorCode, String message, String workspaceId) {
        super(message);
        this.errorCode = errorCode;
        this.workspaceId = workspaceId;
    }

    public String errorCode() { return errorCode; }
    public String workspaceId() { return workspaceId; }
}
