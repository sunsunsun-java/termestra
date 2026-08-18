package dev.termestra.team.application.port.in;

public interface DispatchDeliveryUseCase {
    boolean processNext();
    int recoverInterrupted();
    boolean retry(String workspaceId, String dispatchId);
}
