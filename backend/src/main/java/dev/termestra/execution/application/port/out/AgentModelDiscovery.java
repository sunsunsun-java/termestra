package dev.termestra.execution.application.port.out;

import java.util.List;

@FunctionalInterface
public interface AgentModelDiscovery {
    List<String> discover(String presetId, String command, String workspacePath);
}
