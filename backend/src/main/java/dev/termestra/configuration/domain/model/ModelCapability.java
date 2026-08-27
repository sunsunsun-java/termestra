package dev.termestra.configuration.domain.model;

import java.util.List;
import java.util.Objects;

/** Structured model selection metadata owned by a Command Preset. */
public record ModelCapability(List<String> argumentTemplate,
                              List<String> suggestedModels,
                              boolean allowCustom) {
    public static final String MODEL_PLACEHOLDER = "{model_id}";

    public ModelCapability {
        argumentTemplate = copy(argumentTemplate,"model_args_template");
        suggestedModels = copy(suggestedModels,"suggested_models");
    }

    private static List<String> copy(List<String> values,String field){
        List<String> safe=Objects.requireNonNullElse(values,List.of());
        if(safe.stream().anyMatch(Objects::isNull)){
            throw new IllegalArgumentException(field+" entries must not be null");
        }
        return List.copyOf(safe);
    }
}
