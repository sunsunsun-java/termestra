package dev.termestra.team.application.port.out;

public interface WorkerRuntimeCleaner {
    void stopAndForget(String workspaceId, String workerId);
}
