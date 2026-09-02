package dev.termestra.workspace.application.exception;

public final class WorkspaceRegistrationFailure extends RuntimeException {
    private final String registrationId;
    private final String errorCode;
    private final boolean retryable;

    public WorkspaceRegistrationFailure(
            String registrationId, String errorCode, String message, boolean retryable) {
        super(message);
        this.registrationId = registrationId;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String registrationId() { return registrationId; }
    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
}
