package dev.termestra.team.application.port.in;

public interface RemoveWorkerUseCase {
    void remove(String workspaceId, String workerId);
}
