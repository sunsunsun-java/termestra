package dev.termestra.team.application.port.out;

import java.util.Set;

/** Supplies the currently active Agent ids from the process-local runtime registry. */
@FunctionalInterface
public interface WorkerRuntimeStatus {
    Set<String> activeAgentIds(String workspaceId);
}
