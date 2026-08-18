package dev.termestra.configuration.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.configuration.application.port.in.ConfigurationConflict;
import dev.termestra.configuration.application.port.in.ConfigurationInputLimits;
import dev.termestra.configuration.application.port.out.ConfigurationRepository;
import dev.termestra.configuration.domain.model.CommandPreset;
import dev.termestra.configuration.domain.model.RoleTemplate;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConfigurationRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void rejectsNewCustomCollectionsAtTheirLimitsAndStillAllowsExistingAppStateUpdates() {
        SqliteDatabase database = database("configuration-limits.db");
        database.write("seed configuration limits", connection -> {
            long now = System.currentTimeMillis();
            try (var commands = connection.prepareStatement("""
                    INSERT INTO command_presets(id,display_name,command,args_json,env,is_builtin,created_at,updated_at)
                    VALUES(?,?,?,'[]','{}',0,?,?)
                    """)) {
                for (int index = 0; index < ConfigurationRepository.MAX_CUSTOM_COMMAND_PRESETS; index++) {
                    commands.setString(1, "custom-command-" + index);commands.setString(2, "Command " + index);
                    commands.setString(3, "tool");commands.setLong(4, now);commands.setLong(5, now);commands.addBatch();
                }
                commands.executeBatch();
            }
            try (var roles = connection.prepareStatement("""
                    INSERT INTO role_templates(id,name,role_type,description,default_command,default_args,default_env,is_builtin,created_at,updated_at)
                    VALUES(?,?,?,'','tool','[]','{}',0,?,?)
                    """)) {
                for (int index = 0; index < ConfigurationRepository.MAX_CUSTOM_ROLE_TEMPLATES; index++) {
                    roles.setString(1, "custom-role-" + index);roles.setString(2, "Role " + index);
                    roles.setString(3, "custom");roles.setLong(4, now);roles.setLong(5, now);roles.addBatch();
                }
                roles.executeBatch();
            }
            int existing;
            try (var count = connection.createStatement();var rows = count.executeQuery("SELECT COUNT(*) FROM app_state")) {
                rows.next();existing = rows.getInt(1);
            }
            try (var states = connection.prepareStatement("INSERT INTO app_state(key,value,updated_at) VALUES(?,?,?)")) {
                for (int index = existing; index < ConfigurationRepository.MAX_APP_STATE_ENTRIES; index++) {
                    states.setString(1, "bounded-state-" + index);states.setString(2, "value");states.setLong(3, now);states.addBatch();
                }
                states.executeBatch();
            }
            return null;
        });
        JdbcConfigurationRepository repository = new JdbcConfigurationRepository(database, new ObjectMapper());

        assertThrows(ConfigurationConflict.class, () -> repository.insert(new CommandPreset(
                UUID.randomUUID().toString(), "Extra", "tool", List.of(), Map.of(), null, null, null, false), Instant.now()));
        assertThrows(ConfigurationConflict.class, () -> repository.insert(new RoleTemplate(
                UUID.randomUUID().toString(), "Extra", "custom", "", "tool", List.of(), Map.of(), false), Instant.now()));
        assertThrows(ConfigurationConflict.class,
                () -> repository.setAppState("one-too-many", "value", Instant.now()));
        repository.setAppState("active_workspace_id", "existing-update", Instant.now());
        assertEquals("existing-update", repository.appState("active_workspace_id").orElseThrow());
    }

    @Test void updateAndDeleteReturnAtomicExistenceAndReadOnlyOutcomes() {
        SqliteDatabase database = database("configuration-mutations.db");
        JdbcConfigurationRepository repository = new JdbcConfigurationRepository(database, new ObjectMapper());
        Instant now = Instant.now();
        CommandPreset builtinReplacement = new CommandPreset("claude", "Changed", "tool", List.of(), Map.of(), null, null, null, false);
        CommandPreset absent = new CommandPreset("missing", "Missing", "tool", List.of(), Map.of(), null, null, null, false);

        assertEquals(ConfigurationRepository.MutationResult.READ_ONLY, repository.update(builtinReplacement, now));
        assertEquals(ConfigurationRepository.MutationResult.READ_ONLY, repository.deleteCommandPreset("claude"));
        assertEquals(ConfigurationRepository.MutationResult.NOT_FOUND, repository.update(absent, now));
        assertEquals(ConfigurationRepository.MutationResult.NOT_FOUND, repository.deleteCommandPreset("missing"));
    }

    @Test void legacyOversizedRowsAreBoundedBeforeTheyReachTheJvmProjection() {
        SqliteDatabase database = database("configuration-legacy-bounds.db");
        String oversized = "x".repeat(70_000);
        database.write("seed oversized legacy configuration", connection -> {
            long now = System.currentTimeMillis();
            try (var command = connection.prepareStatement("""
                    INSERT INTO command_presets(
                      id,display_name,command,args_json,env,is_builtin,created_at,updated_at)
                    VALUES('legacy-huge',?,?,?, ?,0,?,?)
                    """)) {
                command.setString(1, oversized);
                command.setString(2, oversized);
                command.setString(3, "[\"" + oversized + "\"]");
                command.setString(4, "{\"PATH\":\"" + oversized + "\"}");
                command.setLong(5, now);
                command.setLong(6, now);
                command.executeUpdate();
            }
            try (var state = connection.prepareStatement(
                    "INSERT INTO app_state(key,value,updated_at) VALUES('legacy-large-state',?,?)")) {
                state.setString(1, oversized);
                state.setLong(2, now);
                state.executeUpdate();
            }
            return null;
        });

        JdbcConfigurationRepository repository = new JdbcConfigurationRepository(database, new ObjectMapper());
        CommandPreset legacy = repository.commandPresets().stream()
                .filter(value -> value.id().equals("legacy-huge"))
                .findFirst().orElseThrow();

        assertEquals(ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS,
                legacy.displayName().length());
        assertEquals(ConfigurationInputLimits.MAX_COMMAND_CHARACTERS, legacy.command().length());
        assertEquals(List.of(), legacy.arguments());
        assertEquals(Map.of(), legacy.environment());
        assertEquals(65_536, repository.appState("legacy-large-state").orElseThrow().length());
    }

    @Test void malformedLegacyJsonAndInvalidIdsCannotBreakOrAliasTheSettingsProjection() {
        SqliteDatabase database = database("configuration-poisoned-rows.db");
        String invalidId="valid-prefix-"+"i".repeat(2_000_000);
        database.write("seed poisoned legacy configuration",connection->{
            long now=System.currentTimeMillis();
            try(var malformed=connection.prepareStatement("""
                    INSERT INTO command_presets(id,display_name,command,args_json,env,
                      session_id_capture_json,yolo_args_json,is_builtin,created_at,updated_at)
                    VALUES('malformed','Malformed','tool','[', '{} {}',
                      '{"pattern":null}','{}',0,?,?)
                    """);
                var invalid=connection.prepareStatement("""
                    INSERT INTO command_presets(id,display_name,command,args_json,env,
                      is_builtin,created_at,updated_at) VALUES(?,?,?,'[]','{}',0,?,?)
                    """)){
                malformed.setLong(1,now);malformed.setLong(2,now);malformed.executeUpdate();
                invalid.setString(1,invalidId);invalid.setString(2,"Invalid identity");
                invalid.setString(3,"tool");invalid.setLong(4,now+1);invalid.setLong(5,now+1);
                invalid.executeUpdate();
            }
            return null;
        });

        JdbcConfigurationRepository repository=new JdbcConfigurationRepository(database,new ObjectMapper());
        CommandPreset malformed=repository.commandPresets().stream()
                .filter(value->value.id().equals("malformed")).findFirst().orElseThrow();

        assertEquals(List.of(),malformed.arguments());
        assertEquals(Map.of(),malformed.environment());
        assertEquals(Map.of(),malformed.sessionIdCapture());
        assertEquals(null,malformed.yoloArgsTemplate());
        assertTrue(repository.commandPresets().stream().noneMatch(value->
                value.id().equals(invalidId.substring(0,256))));
    }

    @Test void appStateUnicodeProjectionUsesTheSameJavaCharacterLimitWithoutBrokenPairs() {
        SqliteDatabase database=database("configuration-unicode-state.db");
        String value="s".repeat(65_535)+"😀tail";
        database.write("seed unicode app state",connection->{
            try(var statement=connection.prepareStatement(
                    "INSERT INTO app_state(key,value,updated_at) VALUES('unicode-state',?,1)")){
                statement.setString(1,value);statement.executeUpdate();
            }
            return null;
        });

        String projected=new JdbcConfigurationRepository(database,new ObjectMapper())
                .appState("unicode-state").orElseThrow();

        assertTrue(projected.length()<=65_536);
        assertEquals(false,Character.isHighSurrogate(projected.charAt(projected.length()-1)));
    }

    private SqliteDatabase database(String name) {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve(name));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        return database;
    }
}
