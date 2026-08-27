package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.in.AgentLaunchConfigurationQuery;
import dev.termestra.execution.application.port.in.AgentLaunchConfigurationView;
import dev.termestra.execution.application.port.in.AgentLaunchOptionsQuery;
import dev.termestra.execution.application.port.out.LaunchPresetCatalog;
import dev.termestra.execution.application.port.out.LaunchPresetDescriptor;

public final class AgentLaunchOptionsService implements AgentLaunchOptionsQuery {
    private final LaunchPresetCatalog presets;
    private final AgentLaunchConfigurationQuery configurations;

    public AgentLaunchOptionsService(LaunchPresetCatalog presets,AgentLaunchConfigurationQuery configurations){
        this.presets=presets;this.configurations=configurations;
    }

    @Override public AgentLaunchOptionsView options(String workspaceId){
        AgentLaunchConfigurationView current=configurations.find(
                workspaceId,workspaceId+":orchestrator").orElse(null);
        OrchestratorLaunchView orchestrator=current==null?null:new OrchestratorLaunchView(
                current.commandPresetId(),current.modelId(),current.revision(),current.commandPresetId()!=null);
        return new AgentLaunchOptionsView(orchestrator,presets.availablePresets().stream()
                .map(AgentLaunchOptionsService::option).toList());
    }

    private static LaunchPresetOptionView option(LaunchPresetDescriptor value){
        return new LaunchPresetOptionView(value.id(),value.displayName(),value.available(),
                value.modelArgumentTemplate()!=null&&(value.allowCustomModel()||!value.suggestedModels().isEmpty()),
                value.allowCustomModel(),value.suggestedModels(),value.revision());
    }
}
