package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.in.AgentModelOptionsQuery;
import dev.termestra.execution.application.port.in.ExecutionInputLimits;
import dev.termestra.execution.application.port.out.AgentDirectory;
import dev.termestra.execution.application.port.out.AgentModelDiscovery;
import dev.termestra.execution.application.port.out.LaunchPresetCatalog;

import java.util.LinkedHashSet;
import java.util.List;

public final class AgentModelOptionsService implements AgentModelOptionsQuery {
    public static final int MAX_MODELS = 256;
    private final LaunchPresetCatalog presets;
    private final AgentDirectory agents;
    private final AgentModelDiscovery discovery;

    public AgentModelOptionsService(LaunchPresetCatalog presets, AgentDirectory agents,
                                    AgentModelDiscovery discovery) {
        this.presets = presets;
        this.agents = agents;
        this.discovery = discovery;
    }

    @Override public List<String> models(String workspaceId, String presetId) {
        var preset = presets.require(presetId);
        if (!preset.available() || preset.modelArgumentTemplate() == null) return List.of();
        String workspacePath = agents.find(workspaceId, workspaceId + ":orchestrator")
                .map(value -> value.workspacePath()).orElse(null);
        if (workspacePath == null) return List.of();
        List<String> source = discovery.discover(preset.id(), preset.command(), workspacePath);
        LinkedHashSet<String> bounded = new LinkedHashSet<>();
        for (String candidate : source) {
            if (bounded.size() == MAX_MODELS) break;
            try {
                String model = ExecutionInputLimits.optionalModelId(candidate);
                if (model != null) bounded.add(model);
            } catch (IllegalArgumentException ignored) {
                // A malformed CLI output entry must not make member creation unavailable.
            }
        }
        return List.copyOf(bounded);
    }
}
