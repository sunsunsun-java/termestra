package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentDirectory;
import dev.termestra.execution.application.port.out.LaunchPresetCatalog;
import dev.termestra.execution.application.port.out.LaunchPresetDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AgentModelOptionsServiceTest {
    @Test void returnsDeduplicatedDiscoveredModelsForTheSelectedWorkspacePreset() {
        LaunchPresetCatalog presets = mock(LaunchPresetCatalog.class);
        when(presets.require("codex")).thenReturn(preset(List.of()));
        AgentDirectory agents = (workspaceId, agentId) -> Optional.of(new AgentDescriptor(
                workspaceId, "Workspace", "/work/one", agentId, "Orchestrator", "", "orchestrator"));
        AgentModelOptionsService service = new AgentModelOptionsService(presets, agents,
                (presetId, command, workspacePath) -> List.of("gpt-a", "gpt-a", "invalid model", "gpt-b"));

        assertEquals(List.of("gpt-a", "gpt-b"), service.models("workspace", "codex"));
    }

    @Test void ignoresConfiguredSuggestionsWhenTheCliHasNoDiscoveryContract() {
        LaunchPresetCatalog presets = mock(LaunchPresetCatalog.class);
        when(presets.require("custom")).thenReturn(preset(List.of("model-a", "model-b")));
        AgentDirectory agents = (workspaceId, agentId) -> Optional.of(new AgentDescriptor(
                workspaceId, "Workspace", "/work/one", agentId, "Orchestrator", "", "orchestrator"));
        AgentModelOptionsService service = new AgentModelOptionsService(presets, agents,
                (presetId, command, workspacePath) -> List.of());

        assertEquals(List.of(), service.models("workspace", "custom"));
    }

    private static LaunchPresetDescriptor preset(List<String> suggestions) {
        return new LaunchPresetDescriptor("codex", "Codex", "codex", List.of(), Map.of(), null,
                null, List.of(), List.of("--model", "{model_id}"), suggestions, true, true, 1);
    }
}
