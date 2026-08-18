package dev.termestra.execution.application.port.in;

import java.util.Optional;

@FunctionalInterface
public interface AgentLaunchConfigurationQuery {
    Optional<AgentLaunchConfigurationView> find(String workspaceId, String agentId);
}
