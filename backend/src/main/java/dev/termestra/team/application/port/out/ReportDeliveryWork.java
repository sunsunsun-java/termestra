package dev.termestra.team.application.port.out;

/** One leased notification of an already committed report. */
public record ReportDeliveryWork(StoredDispatch dispatch, String attemptId, int attemptCount) {
    public enum Outcome { SUBMITTED, DEFERRED, RETRY, FAILED, UNCERTAIN }
}
