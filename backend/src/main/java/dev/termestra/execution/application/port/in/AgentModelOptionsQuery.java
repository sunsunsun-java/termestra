package dev.termestra.execution.application.port.in;

import java.util.List;

public interface AgentModelOptionsQuery {
    List<String> models(String workspaceId, String presetId);
}
