package dev.termestra.team.application.port.in;

public interface DispatchDeliveryUseCase {
    boolean processNext();
    int recoverInterrupted();
    java.util.List<ReportDeliveryIssue> reportIssues(String workspaceId, int limit);
    boolean retryReport(String workspaceId, String dispatchId, boolean confirmUncertain);
    boolean retry(String workspaceId, String dispatchId);
}
