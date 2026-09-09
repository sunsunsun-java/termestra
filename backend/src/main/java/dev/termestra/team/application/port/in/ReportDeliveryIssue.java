package dev.termestra.team.application.port.in;

/** Bounded recovery projection; deliberately excludes report bodies and artifacts. */
public record ReportDeliveryIssue(String dispatchId, String workerId, String state,
                                  int attemptCount, String error, long updatedAt) { }
