package dev.termestra.team.application.exception;

/** The caller tried to complete a delivery attempt that no longer owns the durable lease. */
public final class InactiveDeliveryAttempt extends IllegalStateException {
    public InactiveDeliveryAttempt(String attemptId) {
        super("Dispatch delivery attempt is no longer active: " + attemptId);
    }
}
