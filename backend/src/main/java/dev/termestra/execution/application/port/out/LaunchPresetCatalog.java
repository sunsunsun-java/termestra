package dev.termestra.execution.application.port.out;

import java.util.List;

public interface LaunchPresetCatalog {
    LaunchPresetDescriptor require(String presetId);
    LaunchPresetDescriptor roleDefault(String roleType);
    List<LaunchPresetDescriptor> availablePresets();
}
