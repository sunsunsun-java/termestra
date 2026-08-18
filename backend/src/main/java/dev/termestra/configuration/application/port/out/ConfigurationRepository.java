package dev.termestra.configuration.application.port.out;
import dev.termestra.configuration.domain.model.*;import java.time.Instant;import java.util.*;
public interface ConfigurationRepository {
 int MAX_CUSTOM_COMMAND_PRESETS=128;int MAX_CUSTOM_ROLE_TEMPLATES=128;int MAX_APP_STATE_ENTRIES=1024;
 enum MutationResult { CHANGED, NOT_FOUND, READ_ONLY }
 List<CommandPreset> commandPresets();void insert(CommandPreset value,Instant at);MutationResult update(CommandPreset value,Instant at);MutationResult deleteCommandPreset(String id);List<RoleTemplate> roleTemplates();void insert(RoleTemplate value,Instant at);MutationResult update(RoleTemplate value,Instant at);MutationResult deleteRoleTemplate(String id);Optional<String> appState(String key);void setAppState(String key,String value,Instant at);
}
