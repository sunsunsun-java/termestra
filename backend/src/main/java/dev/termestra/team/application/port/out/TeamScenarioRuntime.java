package dev.termestra.team.application.port.out;

import java.util.List;

public interface TeamScenarioRuntime {
    boolean hasActiveOrchestrator(String workspaceId);
    String resolveAndStoreLocale(String workspaceId, String requestedLocale);
    WorkerLaunchPlan resolveDefaultWorkerLaunch(String workspaceId);
    StartedRun startWorker(String workspaceId, String workerId, String runtimePort);
    DeliveryResult deliverUserInput(String workspaceId, String text);

    record StartedRun(String runId, String status) { }
    record DeliveryResult(boolean delivered, String error) { }
}
