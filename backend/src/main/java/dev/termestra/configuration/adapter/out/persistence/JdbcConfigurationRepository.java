package dev.termestra.configuration.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.configuration.application.port.in.ConfigurationConflict;
import dev.termestra.configuration.application.port.in.ConfigurationInputLimits;
import dev.termestra.configuration.application.port.out.ConfigurationRepository;
import dev.termestra.configuration.domain.model.CommandPreset;
import dev.termestra.configuration.domain.model.RoleTemplate;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQLite persistence with bounded projections for legacy rows created before input limits existed. */
public final class JdbcConfigurationRepository implements ConfigurationRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> ENVIRONMENT = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final int MAX_ID_CHARACTERS = 256;
    private static final int MAX_LEGACY_JSON_CHARACTERS = 65_536;
    private static final int MAX_APP_STATE_VALUE_CHARACTERS = 65_536;

    private final SqliteDatabase database;
    private final ObjectMapper json;

    public JdbcConfigurationRepository(SqliteDatabase database, ObjectMapper json) {
        this.database = database;
        this.json = json;
    }

    @Override
    public List<CommandPreset> commandPresets() {
        return database.read("list command presets", connection -> {
            List<CommandPreset> values = new ArrayList<>();
            String sql = """
                    SELECT substr(id,1,?),substr(display_name,1,?),substr(command,1,?),
                           substr(args_json,1,?),
                           substr(env,1,?),
                           substr(resume_args_template,1,?),
                           substr(session_id_capture_json,1,?),
                           substr(yolo_args_json,1,?),
                           is_builtin
                    FROM command_presets
                    ORDER BY is_builtin DESC,created_at ASC
                    LIMIT 138
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setInt(1, MAX_ID_CHARACTERS + 1);
                statement.setInt(2, ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS + 1);
                statement.setInt(3, ConfigurationInputLimits.MAX_COMMAND_CHARACTERS + 1);
                statement.setInt(4, MAX_LEGACY_JSON_CHARACTERS + 1);
                statement.setInt(5, MAX_LEGACY_JSON_CHARACTERS + 1);
                statement.setInt(6, ConfigurationInputLimits.MAX_RESUME_TEMPLATE_CHARACTERS + 1);
                statement.setInt(7, MAX_LEGACY_JSON_CHARACTERS + 1);
                statement.setInt(8, MAX_LEGACY_JSON_CHARACTERS + 1);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String id=rows.getString(1);
                        String displayName=ConfigurationInputLimits.boundedDisplayName(rows.getString(2));
                        String command=ConfigurationInputLimits.boundedCommand(rows.getString(3));
                        if(!validId(id)||displayName==null||displayName.isBlank()
                                ||command==null||command.isBlank())continue;
                        List<String> yolo = readNullableLegacy(rows.getString(8), STRINGS);
                        values.add(new CommandPreset(
                                id,
                                displayName,
                                command,
                                ConfigurationInputLimits.boundedArguments(
                                        readLegacy(rows.getString(4), STRINGS, List.of())),
                                ConfigurationInputLimits.boundedEnvironment(
                                        readLegacy(rows.getString(5), ENVIRONMENT, Map.of())),
                                ConfigurationInputLimits.boundedResumeTemplate(rows.getString(6)),
                                ConfigurationInputLimits.boundedSessionCapture(
                                        readNullableLegacy(rows.getString(7), OBJECT)),
                                yolo == null ? null : ConfigurationInputLimits.boundedArguments(yolo),
                                rows.getInt(9) == 1));
                    }
                }
            }
            return List.copyOf(values);
        });
    }

    @Override
    public void insert(CommandPreset value, Instant at) {
        database.write("insert command preset", connection -> {
            String sql = """
                    INSERT INTO command_presets(
                      id,display_name,command,args_json,env,resume_args_template,
                      session_id_capture_json,yolo_args_json,is_builtin,created_at,updated_at)
                    SELECT ?,?,?,?,?,?,?,?,0,?,?
                    WHERE (SELECT COUNT(*) FROM command_presets WHERE is_builtin=0) < ?
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, value.id());
                statement.setString(2, value.displayName());
                statement.setString(3, value.command());
                statement.setString(4, write(value.arguments()));
                statement.setString(5, write(value.environment()));
                statement.setString(6, value.resumeArgsTemplate());
                statement.setString(7, writeNullable(value.sessionIdCapture()));
                statement.setString(8, writeNullable(value.yoloArgsTemplate()));
                statement.setLong(9, at.toEpochMilli());
                statement.setLong(10, at.toEpochMilli());
                statement.setInt(11, MAX_CUSTOM_COMMAND_PRESETS);
                if (statement.executeUpdate() != 1) {
                    throw new ConfigurationConflict(
                            "Custom command preset limit reached: " + MAX_CUSTOM_COMMAND_PRESETS);
                }
            }
            return null;
        });
    }

    @Override
    public MutationResult update(CommandPreset value, Instant at) {
        return database.write("update command preset", connection -> {
            String sql = """
                    UPDATE command_presets
                    SET display_name=?,command=?,args_json=?,env=?,resume_args_template=?,
                        session_id_capture_json=?,yolo_args_json=?,updated_at=?
                    WHERE id=? AND is_builtin=0
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, value.displayName());
                statement.setString(2, value.command());
                statement.setString(3, write(value.arguments()));
                statement.setString(4, write(value.environment()));
                statement.setString(5, value.resumeArgsTemplate());
                statement.setString(6, writeNullable(value.sessionIdCapture()));
                statement.setString(7, writeNullable(value.yoloArgsTemplate()));
                statement.setLong(8, at.toEpochMilli());
                statement.setString(9, value.id());
                if (statement.executeUpdate() == 1) return MutationResult.CHANGED;
            }
            return mutationMiss(connection, "command_presets", value.id());
        });
    }

    @Override public MutationResult deleteCommandPreset(String id) {
        return delete("command_presets", id);
    }

    @Override
    public List<RoleTemplate> roleTemplates() {
        return database.read("list role templates", connection -> {
            List<RoleTemplate> values = new ArrayList<>();
            String sql = """
                    SELECT substr(id,1,?),substr(name,1,?),substr(role_type,1,?),
                           substr(description,1,?),substr(default_command,1,?),
                           substr(default_args,1,?),
                           substr(default_env,1,?),
                           is_builtin
                    FROM role_templates
                    ORDER BY is_builtin DESC,created_at ASC
                    LIMIT 132
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setInt(1, MAX_ID_CHARACTERS + 1);
                statement.setInt(2, ConfigurationInputLimits.MAX_ROLE_NAME_CHARACTERS + 1);
                statement.setInt(3, ConfigurationInputLimits.MAX_ROLE_TYPE_CHARACTERS + 1);
                statement.setInt(4, ConfigurationInputLimits.MAX_ROLE_DESCRIPTION_CHARACTERS + 1);
                statement.setInt(5, ConfigurationInputLimits.MAX_COMMAND_CHARACTERS + 1);
                statement.setInt(6, MAX_LEGACY_JSON_CHARACTERS + 1);
                statement.setInt(7, MAX_LEGACY_JSON_CHARACTERS + 1);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String id=rows.getString(1);
                        String name=ConfigurationInputLimits.boundedRoleName(rows.getString(2));
                        String roleType=ConfigurationInputLimits.boundedRoleType(rows.getString(3));
                        if(!validId(id)||name==null||name.isBlank()||roleType==null||roleType.isBlank())continue;
                        values.add(new RoleTemplate(
                                id,
                                name,
                                roleType,
                                ConfigurationInputLimits.boundedRoleDescription(rows.getString(4)),
                                ConfigurationInputLimits.boundedCommand(rows.getString(5)),
                                ConfigurationInputLimits.boundedArguments(
                                        readLegacy(rows.getString(6), STRINGS, List.of())),
                                ConfigurationInputLimits.boundedEnvironment(
                                        readLegacy(rows.getString(7), ENVIRONMENT, Map.of())),
                                rows.getInt(8) == 1));
                    }
                }
            }
            return List.copyOf(values);
        });
    }

    @Override
    public void insert(RoleTemplate value, Instant at) {
        database.write("insert role template", connection -> {
            String sql = """
                    INSERT INTO role_templates(
                      id,name,role_type,description,default_command,default_args,default_env,
                      is_builtin,created_at,updated_at)
                    SELECT ?,?,?,?,?,?,?,0,?,?
                    WHERE (SELECT COUNT(*) FROM role_templates WHERE is_builtin=0) < ?
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, value.id());
                statement.setString(2, value.name());
                statement.setString(3, value.roleType());
                statement.setString(4, value.description());
                statement.setString(5, value.defaultCommand());
                statement.setString(6, write(value.defaultArguments()));
                statement.setString(7, write(value.defaultEnvironment()));
                statement.setLong(8, at.toEpochMilli());
                statement.setLong(9, at.toEpochMilli());
                statement.setInt(10, MAX_CUSTOM_ROLE_TEMPLATES);
                if (statement.executeUpdate() != 1) {
                    throw new ConfigurationConflict(
                            "Custom role template limit reached: " + MAX_CUSTOM_ROLE_TEMPLATES);
                }
            }
            return null;
        });
    }

    @Override
    public MutationResult update(RoleTemplate value, Instant at) {
        return database.write("update role template", connection -> {
            String sql = """
                    UPDATE role_templates
                    SET name=?,role_type=?,description=?,default_command=?,default_args=?,
                        default_env=?,updated_at=?
                    WHERE id=? AND is_builtin=0
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, value.name());
                statement.setString(2, value.roleType());
                statement.setString(3, value.description());
                statement.setString(4, value.defaultCommand());
                statement.setString(5, write(value.defaultArguments()));
                statement.setString(6, write(value.defaultEnvironment()));
                statement.setLong(7, at.toEpochMilli());
                statement.setString(8, value.id());
                if (statement.executeUpdate() == 1) return MutationResult.CHANGED;
            }
            return mutationMiss(connection, "role_templates", value.id());
        });
    }

    @Override public MutationResult deleteRoleTemplate(String id) {
        return delete("role_templates", id);
    }

    private MutationResult delete(String table, String id) {
        return database.write("delete configuration", connection -> {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE id=? AND is_builtin=0")) {
                statement.setString(1, id);
                if (statement.executeUpdate() == 1) return MutationResult.CHANGED;
            }
            return mutationMiss(connection, table, id);
        });
    }

    private static MutationResult mutationMiss(Connection connection, String table, String id)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT is_builtin FROM " + table + " WHERE id=?")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return MutationResult.NOT_FOUND;
                return result.getInt(1) == 1 ? MutationResult.READ_ONLY : MutationResult.NOT_FOUND;
            }
        }
    }

    @Override
    public Optional<String> appState(String key) {
        return database.read("get app state", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT substr(value,1,?) FROM app_state WHERE key=?")) {
                statement.setInt(1, MAX_APP_STATE_VALUE_CHARACTERS + 1);
                statement.setString(2, key);
                try (var result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.ofNullable(boundedText(result.getString(1),MAX_APP_STATE_VALUE_CHARACTERS))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public void setAppState(String key, String value, Instant at) {
        database.write("set app state", connection -> {
            String sql = """
                    INSERT INTO app_state(key,value,updated_at)
                    SELECT ?,?,?
                    WHERE EXISTS(SELECT 1 FROM app_state WHERE key=?)
                       OR (SELECT COUNT(*) FROM app_state) < ?
                    ON CONFLICT(key) DO UPDATE
                    SET value=excluded.value,updated_at=excluded.updated_at
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.setLong(3, at.toEpochMilli());
                statement.setString(4, key);
                statement.setInt(5, MAX_APP_STATE_ENTRIES);
                if (statement.executeUpdate() != 1) {
                    throw new ConfigurationConflict(
                            "Application state entry limit reached: " + MAX_APP_STATE_ENTRIES);
                }
            }
            return null;
        });
    }

    private String write(Object value) throws SQLException {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new SQLException("Invalid configuration JSON", error);
        }
    }

    private String writeNullable(Object value) throws SQLException {
        return value == null ? null : write(value);
    }

    private <T> T readLegacy(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.length() > MAX_LEGACY_JSON_CHARACTERS) return fallback;
        try {
            T parsed = json.readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(value);
            return parsed == null ? fallback : parsed;
        } catch (JsonProcessingException error) {
            return fallback;
        }
    }

    private <T> T readNullableLegacy(String value, TypeReference<T> type) {
        return value == null ? null : readLegacy(value, type, null);
    }

    private static boolean validId(String value){
        return value!=null&&!value.isBlank()&&value.length()<=MAX_ID_CHARACTERS;
    }

    private static String boundedText(String value,int maximum){
        if(value==null||value.length()<=maximum)return value;
        int end=maximum;
        if(Character.isHighSurrogate(value.charAt(end-1))
                &&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.substring(0,end);
    }
}
