package dev.termestra.configuration.domain.model;

import java.util.List;
import java.util.Objects;

/** Structured model selection metadata owned by a Command Preset. */
public record ModelCapability(List<String> argumentTemplate,
                              List<String> suggestedModels,
                              boolean allowCustom) {
    public static final String MODEL_PLACEHOLDER = "{model_id}";

    public ModelCapability {
        argumentTemplate = List.copyOf(Objects.requireNonNullElse(argumentTemplate, List.of()));
        suggestedModels = List.copyOf(Objects.requireNonNullElse(suggestedModels, List.of()));
    }
}
