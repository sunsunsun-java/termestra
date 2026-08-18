package dev.termestra.configuration.application.port.in;
import dev.termestra.configuration.domain.model.*;
import java.util.*;
public interface ConfigurationUseCase {
 List<CommandPreset> commandPresets();CommandPreset createCommandPreset(CommandPreset value);CommandPreset updateCommandPreset(String id,CommandPreset value);void deleteCommandPreset(String id);
 List<RoleTemplate> roleTemplates();RoleTemplate createRoleTemplate(RoleTemplate value);RoleTemplate updateRoleTemplate(String id,RoleTemplate value);void deleteRoleTemplate(String id);
 Optional<String> appState(String key);void setAppState(String key,String value);
}
