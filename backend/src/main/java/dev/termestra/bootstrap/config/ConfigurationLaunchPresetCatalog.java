package dev.termestra.bootstrap.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.configuration.application.port.in.CommandAvailabilityUseCase;
import dev.termestra.configuration.application.port.in.ConfigurationUseCase;
import dev.termestra.configuration.domain.model.CommandPreset;
import dev.termestra.execution.application.port.out.LaunchPresetCatalog;
import dev.termestra.execution.application.port.out.LaunchPresetDescriptor;
import dev.termestra.execution.application.exception.InvalidLaunchRequest;

import java.util.List;
import java.util.Map;

final class ConfigurationLaunchPresetCatalog implements LaunchPresetCatalog {
    private final ConfigurationUseCase configuration;
    private final CommandAvailabilityUseCase availability;
    private final ObjectMapper json;

    ConfigurationLaunchPresetCatalog(ConfigurationUseCase configuration,
                                     CommandAvailabilityUseCase availability,ObjectMapper json){
        this.configuration=configuration;this.availability=availability;this.json=json;
    }

    @Override public LaunchPresetDescriptor require(String presetId){
        return configuration.commandPresets().stream().filter(value->value.id().equals(presetId)).findFirst()
                .map(this::descriptor).orElseThrow(()->new InvalidLaunchRequest(
                        "COMMAND_PRESET_NOT_FOUND",presetId));
    }

    @Override public LaunchPresetDescriptor roleDefault(String roleType){
        var role=configuration.roleTemplates().stream().filter(value->value.roleType().equals(roleType)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Role template not found: "+roleType));
        LaunchPresetDescriptor preset=configuration.commandPresets().stream()
                .filter(value->value.id().equals(role.defaultCommand())
                        ||value.command().equals(role.defaultCommand()))
                .findFirst().map(this::descriptor).orElse(null);
        if(preset!=null)return preset;
        return new LaunchPresetDescriptor(null,role.name(),role.defaultCommand(),role.defaultArguments(),
                role.defaultEnvironment(),null,null,List.of(),null,List.of(),false,true,1);
    }

    @Override public List<LaunchPresetDescriptor> availablePresets(){
        return configuration.commandPresets().stream().map(this::descriptor).toList();
    }

    private LaunchPresetDescriptor descriptor(CommandPreset preset){
        var capability=preset.modelCapability();
        return new LaunchPresetDescriptor(preset.id(),preset.displayName(),preset.command(),preset.arguments(),
                preset.environment(),preset.resumeArgsTemplate(),capture(preset),
                preset.yoloArgsTemplate()==null?List.of():preset.yoloArgsTemplate(),
                capability==null?null:capability.argumentTemplate(),
                capability==null?List.of():capability.suggestedModels(),
                capability!=null&&capability.allowCustom(),availability.available(preset),preset.revision());
    }

    private String capture(CommandPreset preset){
        if(preset.sessionIdCapture()==null)return null;
        try{return json.writeValueAsString(preset.sessionIdCapture());}
        catch(JsonProcessingException error){throw new IllegalStateException(
                "Invalid session capture configuration",error);}
    }
}
