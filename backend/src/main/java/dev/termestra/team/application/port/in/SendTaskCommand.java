package dev.termestra.team.application.port.in;

public record SendTaskCommand(String workspaceId, String actorId, String token, String workerName,
                              String text, String runtimePort, String idempotencyKey) {
    public SendTaskCommand(String workspaceId, String actorId, String token, String workerName,
                           String text, String runtimePort) {
        this(workspaceId, actorId, token, workerName, text, runtimePort, null);
    }
}
