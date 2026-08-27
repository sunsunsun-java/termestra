package dev.termestra.team.application.port.out;

import dev.termestra.team.application.port.in.WorkerLaunchIntent;

public interface WorkerExecution {
    void configure(String workspaceId,String workerId,WorkerLaunchIntent launch);
    StartedWorker start(String workspaceId,String workerId,String runtimePort);

    record StartedWorker(String runId){ }
}
