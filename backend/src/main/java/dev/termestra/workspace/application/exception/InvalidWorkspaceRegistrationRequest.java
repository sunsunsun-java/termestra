package dev.termestra.workspace.application.exception;

public final class InvalidWorkspaceRegistrationRequest extends RuntimeException {
    private final String errorCode;

    public InvalidWorkspaceRegistrationRequest(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
