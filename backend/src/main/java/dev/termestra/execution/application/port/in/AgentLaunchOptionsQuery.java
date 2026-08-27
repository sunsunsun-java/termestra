package dev.termestra.execution.application.port.in;

import java.util.List;

public interface AgentLaunchOptionsQuery {
    AgentLaunchOptionsView options(String workspaceId);

    record AgentLaunchOptionsView(OrchestratorLaunchView orchestrator,List<LaunchPresetOptionView> presets){
        public AgentLaunchOptionsView{presets=List.copyOf(presets);}
    }
    record OrchestratorLaunchView(String presetId,String modelId,long revision,boolean inheritable){ }
    record LaunchPresetOptionView(String id,String displayName,boolean available,
                                  boolean modelSelectionSupported,boolean allowCustomModel,
                                  List<String> suggestedModels,long revision){
        public LaunchPresetOptionView{suggestedModels=List.copyOf(suggestedModels);}
    }
}
