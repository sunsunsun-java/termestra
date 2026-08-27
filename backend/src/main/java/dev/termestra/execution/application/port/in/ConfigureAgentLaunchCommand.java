package dev.termestra.execution.application.port.in;

import java.util.Objects;

public record ConfigureAgentLaunchCommand(String workspaceId,String agentId,LaunchSource source) {
    public ConfigureAgentLaunchCommand {
        if(workspaceId==null||workspaceId.isBlank())throw new IllegalArgumentException("workspace_id is required");
        if(agentId==null||agentId.isBlank())throw new IllegalArgumentException("agent_id is required");
        source=Objects.requireNonNull(source,"launch source is required");
    }
}
