package dev.termestra.execution.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.execution.application.port.in.AgentLaunchOptionsQuery;
import dev.termestra.execution.application.port.in.AgentModelOptionsQuery;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
public final class AgentLaunchOptionsController {
    private final AgentLaunchOptionsQuery options;
    private final AgentModelOptionsQuery models;

    public AgentLaunchOptionsController(AgentLaunchOptionsQuery options,AgentModelOptionsQuery models){
        this.options=options;this.models=models;
    }

    @GetMapping("/api/ui/workspaces/{workspaceId}/agent-launch-options")
    Mono<LaunchOptionsResponse> worker(@PathVariable String workspaceId){
        return blocking(()->LaunchOptionsResponse.from(options.options(workspaceId)));
    }

    @GetMapping("/api/ui/workspaces/{workspaceId}/agent-launch-options/{presetId}/models")
    Mono<ModelOptionsResponse> models(@PathVariable String workspaceId,@PathVariable String presetId){
        return blocking(()->new ModelOptionsResponse(models.models(workspaceId,presetId)));
    }

    private static <T>Mono<T> blocking(java.util.concurrent.Callable<T> action){
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record LaunchOptionsResponse(OrchestratorProfile orchestrator,List<PresetOption> presets){
        static LaunchOptionsResponse from(AgentLaunchOptionsQuery.AgentLaunchOptionsView value){
            return new LaunchOptionsResponse(OrchestratorProfile.from(value.orchestrator()),
                    value.presets().stream().map(PresetOption::from).toList());
        }
    }
    public record OrchestratorProfile(@JsonProperty("preset_id")String presetId,
                                      @JsonProperty("model_id")String modelId,long revision,
                                      boolean inheritable){
        static OrchestratorProfile from(AgentLaunchOptionsQuery.OrchestratorLaunchView value){if(value==null)return null;
            return new OrchestratorProfile(value.presetId(),value.modelId(),value.revision(),value.inheritable());}
    }
    public record PresetOption(String id,@JsonProperty("display_name")String displayName,
                               boolean available,@JsonProperty("model_picker")ModelPicker modelPicker,
                               long revision){
        static PresetOption from(AgentLaunchOptionsQuery.LaunchPresetOptionView value){return new PresetOption(value.id(),
                value.displayName(),value.available(),ModelPicker.from(value),value.revision());}
    }
    public record ModelPicker(boolean supported,@JsonProperty("allow_custom")boolean allowCustom,
                              @JsonProperty("suggested_models")List<String> suggestedModels){
        static ModelPicker from(AgentLaunchOptionsQuery.LaunchPresetOptionView value){return new ModelPicker(
                value.modelSelectionSupported(),value.allowCustomModel(),value.suggestedModels());}
    }
    public record ModelOptionsResponse(List<String> models){
        public ModelOptionsResponse{models=List.copyOf(models);}
    }
}
