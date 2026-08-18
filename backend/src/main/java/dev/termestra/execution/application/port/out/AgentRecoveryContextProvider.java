package dev.termestra.execution.application.port.out;

import java.time.Instant;
import java.util.List;

public interface AgentRecoveryContextProvider {
    boolean hasPreviousRun(String agentId, String currentRunId);
    RecoveryContext load(String workspaceId, Instant recentSince);
    long appendSystemRecoveryMessage(String workspaceId, String agentId, String text, Instant at);
    long appendUserInput(String workspaceId, String agentId, String text, Instant at);
    void deleteMessage(long sequence);

    record RecoveryContext(String tasksContent, List<RecoveryMessage> recentMessages,
                           List<RecoveryMessage> allTaskMessages, List<RecoveryWorker> workers) { }
    record RecoveryMessage(String type, String fromAgentId, String toAgentId, String text, String status) { }
    record RecoveryWorker(String id, String name, String role, int pendingTaskCount) { }
}
