package dev.termestra.workspace.application.exception;

import dev.termestra.workspace.application.port.in.registration.RegistrationOptionsView;

public final class GitRegistrationFailure extends RuntimeException {
    private final String registrationId;
    private final String errorCode;
    private final boolean retryable;
    private final Boolean sourceRevisionChanged;
    private final RegistrationOptionsView.HeadView observedHead;

    public GitRegistrationFailure(
            String registrationId,
            String errorCode,
            String message,
            boolean retryable,
            Boolean sourceRevisionChanged,
            RegistrationOptionsView.HeadView observedHead) {
        super(message);
        this.registrationId = registrationId;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.sourceRevisionChanged = sourceRevisionChanged;
        this.observedHead = observedHead;
    }

    public String registrationId() { return registrationId; }
    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
    public Boolean sourceRevisionChanged() { return sourceRevisionChanged; }
    public RegistrationOptionsView.HeadView observedHead() { return observedHead; }
}
