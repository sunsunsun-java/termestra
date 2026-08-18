package dev.termestra.team.application.port.out;

import java.time.Instant;
import java.util.List;
public record TeamMessage(String workspaceId, String workerId, String type, String fromAgentId,
                          String toAgentId, String text, String status, List<String> artifacts,
                          Instant createdAt, String dispatchId) {
    public TeamMessage(String workspaceId, String workerId, String type, String fromAgentId,
                       String toAgentId, String text, String status, List<String> artifacts,
                       Instant createdAt) {
        this(workspaceId, workerId, type, fromAgentId, toAgentId, text, status, artifacts, createdAt, null);
    }
}
