package dev.termestra.bootstrap;

import dev.termestra.auth.application.AgentCredentialService;
import dev.termestra.configuration.application.port.in.ConfigurationInputLimits;
import dev.termestra.configuration.application.port.in.ConfigurationUseCase;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.team.application.exception.InvalidTeamMemberRecord;
import dev.termestra.team.application.port.in.TeamAdminUseCase;
import dev.termestra.team.application.port.in.TeamInputLimits;
import dev.termestra.team.application.port.out.TeamMemberRepository;
import dev.termestra.workspace.application.port.in.WorkspaceInputLimits;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InputLimitsHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = temporaryDirectory("termestra-input-limits-");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA_DIRECTORY::toString);
    }

    @LocalServerPort int port;
    @Autowired SqliteDatabase database;
    @Autowired AgentCredentialService credentials;
    @Autowired ConfigurationUseCase configuration;
    @Autowired TeamAdminUseCase team;
    @Autowired TeamMemberRepository members;

    @Test void rejectsOversizedWorkspaceNamesAndBoundsLegacyRowsAtTheHttpProjection() {
        WebTestClient client = client();
        String cookie = uiCookie(client);
        Path path = temporaryDirectory("termestra-workspace-name-limit-");

        client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("path", path.toString(),
                        "name", "W".repeat(WorkspaceInputLimits.MAX_NAME_CHARACTERS + 1),
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error").isEqualTo("Workspace name exceeds 256 characters");
        client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("path", "/" + "p".repeat(WorkspaceInputLimits.MAX_PATH_CHARACTERS),
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error").isEqualTo("Workspace path exceeds 4096 characters");

        String legacyId = UUID.randomUUID().toString();
        String invalidPathId = UUID.randomUUID().toString();
        String legacyName = "L".repeat(2 * 1_024 * 1_024);
        String oversizedPath = "/" + "x".repeat(2 * 1_024 * 1_024);
        database.write("seed oversized legacy workspace", connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO workspaces(id,name,path,created_at,canonical_path,canonical_path_owner)
                    VALUES(?,?,?,?,?,1)
                    """)) {
                String legacyPath = "/tmp/legacy-workspace-" + legacyId;
                statement.setString(1, legacyId);
                statement.setString(2, legacyName);
                statement.setString(3, legacyPath);
                statement.setLong(4, System.currentTimeMillis());
                statement.setString(5, legacyPath);
                statement.executeUpdate();
                statement.setString(1, invalidPathId);
                statement.setString(2, "Invalid legacy path");
                statement.setString(3, oversizedPath);
                statement.setLong(4, System.currentTimeMillis() + 1);
                statement.setString(5, oversizedPath);
                statement.executeUpdate();
            }
            return null;
        });

        List<Map> response = client.get().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody();
        Map<?, ?> legacy = find(response, legacyId);
        assertEquals(WorkspaceInputLimits.MAX_NAME_CHARACTERS, legacy.get("name").toString().length());
        org.junit.jupiter.api.Assertions.assertFalse(Objects.requireNonNull(response).stream()
                .anyMatch(value -> invalidPathId.equals(value.get("id"))));
        client.post().uri("/api/workspaces/{id}/open", invalidPathId)
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of("target_id", "finder"))
                .exchange().expectStatus().isEqualTo(422).expectBody()
                .jsonPath("$.error_code").isEqualTo("WORKSPACE_RECORD_INVALID");
        assertEquals(legacyName.length(), database.<Integer>read("read legacy workspace name length", connection -> {
            try (var statement = connection.prepareStatement("SELECT length(name) FROM workspaces WHERE id=?")) {
                statement.setString(1, legacyId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }).intValue());
    }

    @Test void enforcesTeamWriteLimitsBeforeSqliteAndBoundsLegacyMemberSummaries() {
        WebTestClient client = client();
        String cookie = uiCookie(client);
        String workspaceId = createWorkspace(client, cookie, "Team Limits");
        Map<?, ?> worker = client.post().uri("/api/workspaces/{id}/workers", workspaceId)
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of(
                        "name", "M".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS),
                        "description", "D".repeat(TeamInputLimits.MAX_MEMBER_DESCRIPTION_CHARACTERS),
                        "role", "coder"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workerId = Objects.requireNonNull(worker).get("id").toString();

        client.post().uri("/api/workspaces/{id}/workers", workspaceId).header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "N".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS + 1),
                        "role", "coder"))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();
        client.post().uri("/api/workspaces/{id}/workers", workspaceId).header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Description too large",
                        "description", "D".repeat(TeamInputLimits.MAX_MEMBER_DESCRIPTION_CHARACTERS + 1),
                        "role", "coder"))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();
        client.patch().uri("/api/workspaces/{workspace}/workers/{worker}", workspaceId, workerId)
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "R".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS + 1)))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();

        String orchestratorId = workspaceId + ":orchestrator";
        String orchestratorToken = credentials.issue(orchestratorId);
        client.post().uri("/api/team/send").bodyValue(Map.of(
                        "project_id", workspaceId, "from_agent_id", orchestratorId,
                        "token", orchestratorToken,
                        "to", "M".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS),
                        "text", "bounded task",
                        "idempotency_key", "I".repeat(TeamInputLimits.MAX_IDEMPOTENCY_KEY_CHARACTERS + 1)))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();
        client.post().uri("/api/team/send").bodyValue(Map.of(
                        "project_id", workspaceId, "from_agent_id", orchestratorId,
                        "token", orchestratorToken,
                        "to", "M".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS),
                        "text", "T".repeat(TeamInputLimits.MAX_TASK_TEXT_CHARACTERS + 1)))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();

        String workerToken = credentials.issue(workerId);
        client.post().uri("/api/team/report").bodyValue(Map.of(
                        "project_id", workspaceId, "from_agent_id", workerId, "token", workerToken,
                        "result", "R".repeat(TeamInputLimits.MAX_REPORT_TEXT_CHARACTERS + 1),
                        "artifacts", List.of()))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();
        client.post().uri("/api/team/status").bodyValue(Map.of(
                        "project_id", workspaceId, "from_agent_id", workerId, "token", workerToken,
                        "result", "status",
                        "artifacts", java.util.Collections.nCopies(TeamInputLimits.MAX_ARTIFACTS + 1, "file")))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();

        assertEquals(0, count("dispatches", workspaceId));
        assertEquals(0, count("messages", workspaceId));

        String legacyId = UUID.randomUUID().toString();
        String legacyName = "L".repeat(2 * 1_024 * 1_024);
        String legacyDescription = "D".repeat(2 * 1_024 * 1_024);
        String legacyPresetId = "P".repeat(2 * 1_024 * 1_024);
        database.write("seed oversized legacy member", connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                statement.setString(1, legacyId);
                statement.setString(2, workspaceId);
                statement.setString(3, legacyName);
                statement.setString(4, legacyDescription);
                statement.setString(5, "custom");
                statement.setLong(6, System.currentTimeMillis());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO agent_launch_configs(
                      workspace_id,agent_id,command,args_json,command_preset_id,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                long now = System.currentTimeMillis();
                statement.setString(1, workspaceId);
                statement.setString(2, legacyId);
                statement.setString(3, "tool");
                statement.setString(4, "[]");
                statement.setString(5, legacyPresetId);
                statement.setLong(6, now);
                statement.setLong(7, now);
                statement.executeUpdate();
            }
            return null;
        });

        List<Map> response = client.get().uri("/api/ui/workspaces/{id}/team", workspaceId)
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        assertFalse(response.stream().anyMatch(value -> legacyId.equals(value.get("id"))));
        assertFalse(team.listForUi(workspaceId).stream().anyMatch(value -> value.id().equals(legacyId)));
        assertThrows(InvalidTeamMemberRecord.class, () -> members.findById(workspaceId, legacyId));
        assertEquals(legacyDescription.length(), database.<Integer>read(
                "read legacy member description length", connection -> {
                    try (var statement = connection.prepareStatement(
                            "SELECT length(description) FROM workers WHERE id=?")) {
                        statement.setString(1, legacyId);
                        try (var result = statement.executeQuery()) {
                            result.next();
                            return result.getInt(1);
                        }
                    }
                }).intValue());

        String legacyDispatchId = UUID.randomUUID().toString();
        String legacyArtifacts = "[" + String.join(",",
                java.util.Collections.nCopies(TeamInputLimits.MAX_ARTIFACTS + 10,
                        "\"artifact-value\"")) + "]";
        database.write("seed oversized legacy artifact collection", connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO dispatches(id,workspace_id,from_agent_id,to_agent_id,text,status,created_at,artifacts)
                    VALUES(?,?,?,?,?,'queued',?,?)
                    """)) {
                statement.setString(1, legacyDispatchId);
                statement.setString(2, workspaceId);
                statement.setString(3, orchestratorId);
                statement.setString(4, workerId);
                statement.setString(5, "legacy task");
                statement.setLong(6, System.currentTimeMillis());
                statement.setString(7, legacyArtifacts);
                statement.executeUpdate();
            }
            return null;
        });
        List<Map> dispatches = client.get().uri(
                        "/api/ui/workspaces/{id}/dispatches?limit=100", workspaceId)
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        Map<?, ?> legacyDispatch = find(dispatches, legacyDispatchId);
        assertEquals(TeamInputLimits.MAX_ARTIFACTS,
                ((List<?>) legacyDispatch.get("artifacts")).size());
        assertEquals(true, legacyDispatch.get("truncated"));
    }

    @Test void enforcesConfigurationLimitsAndBoundsLegacySettingsResponsesWithoutChangingStoredValues() {
        WebTestClient client = client();
        String cookie = uiCookie(client);

        client.post().uri("/api/settings/command-presets").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("display_name", "P".repeat(
                                ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS + 1),
                        "command", "tool", "args", List.of(), "env", Map.of()))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();
        client.post().uri("/api/settings/command-presets").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("display_name", "Too many args", "command", "tool",
                        "args", java.util.Collections.nCopies(
                                ConfigurationInputLimits.MAX_ARGUMENTS + 1, "x"), "env", Map.of()))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();
        client.post().uri("/api/settings/role-templates").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Large role", "role_type", "custom",
                        "description", "D".repeat(
                                ConfigurationInputLimits.MAX_ROLE_DESCRIPTION_CHARACTERS + 1),
                        "default_command", "tool", "default_args", List.of(), "default_env", Map.of()))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").exists();

        String presetId = "legacy-preset-" + UUID.randomUUID();
        String roleId = "legacy-role-" + UUID.randomUUID();
        String legacyDisplay = "P".repeat(ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS + 300);
        String legacyCommand = "/" + "c".repeat(ConfigurationInputLimits.MAX_COMMAND_CHARACTERS + 300);
        String legacyDescription = "D".repeat(ConfigurationInputLimits.MAX_ROLE_DESCRIPTION_CHARACTERS + 300);
        database.write("seed oversized legacy settings", connection -> {
            long now = System.currentTimeMillis();
            try (var preset = connection.prepareStatement("""
                    INSERT INTO command_presets(id,display_name,command,args_json,env,resume_args_template,
                                                session_id_capture_json,yolo_args_json,is_builtin,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,0,?,?)
                    """)) {
                preset.setString(1, presetId);
                preset.setString(2, legacyDisplay);
                preset.setString(3, legacyCommand);
                preset.setString(4, "[\"" + "a".repeat(6_000) + "\"]");
                preset.setString(5, "{\"TOKEN\":\"" + "e".repeat(10_000) + "\"}");
                preset.setString(6, "r".repeat(6_000));
                preset.setString(7, "{\"pattern\":\"" + "s".repeat(10_000) + "\"}");
                preset.setString(8, "[\"" + "y".repeat(6_000) + "\"]");
                preset.setLong(9, now);
                preset.setLong(10, now);
                preset.executeUpdate();
            }
            try (var role = connection.prepareStatement("""
                    INSERT INTO role_templates(id,name,role_type,description,default_command,default_args,
                                               default_env,is_builtin,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,0,?,?)
                    """)) {
                role.setString(1, roleId);
                role.setString(2, "N".repeat(ConfigurationInputLimits.MAX_ROLE_NAME_CHARACTERS + 300));
                role.setString(3, "T".repeat(ConfigurationInputLimits.MAX_ROLE_TYPE_CHARACTERS + 300));
                role.setString(4, legacyDescription);
                role.setString(5, legacyCommand);
                role.setString(6, "[\"" + "a".repeat(6_000) + "\"]");
                role.setString(7, "{\"TOKEN\":\"" + "e".repeat(10_000) + "\"}");
                role.setLong(8, now);
                role.setLong(9, now);
                role.executeUpdate();
            }
            return null;
        });

        List<Map> commandResponses = client.get().uri("/api/settings/command-presets")
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        Map<?, ?> preset = find(commandResponses, presetId);
        assertEquals(ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS,
                preset.get("display_name").toString().length());
        assertEquals(ConfigurationInputLimits.MAX_COMMAND_CHARACTERS,
                preset.get("command").toString().length());
        List<?> boundedArguments = (List<?>) preset.get("args");
        assertEquals(1, boundedArguments.size());
        assertEquals(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS,
                boundedArguments.getFirst().toString().length());
        Map<?, ?> boundedEnvironment = (Map<?, ?>) preset.get("env");
        assertEquals(1, boundedEnvironment.size());
        assertEquals(ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS,
                boundedEnvironment.get("TOKEN").toString().length());
        assertEquals(ConfigurationInputLimits.MAX_RESUME_TEMPLATE_CHARACTERS,
                preset.get("resume_args_template").toString().length());
        assertEquals(ConfigurationInputLimits.MAX_SESSION_CAPTURE_STRING_CHARACTERS,
                ((Map<?, ?>) preset.get("session_id_capture")).get("pattern").toString().length());
        assertEquals(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS,
                ((List<?>) preset.get("yolo_args_template")).getFirst().toString().length());

        List<Map> roleResponses = client.get().uri("/api/settings/role-templates")
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        Map<?, ?> role = find(roleResponses, roleId);
        assertEquals(ConfigurationInputLimits.MAX_ROLE_NAME_CHARACTERS,
                role.get("name").toString().length());
        assertEquals(ConfigurationInputLimits.MAX_ROLE_TYPE_CHARACTERS,
                role.get("role_type").toString().length());
        assertEquals(ConfigurationInputLimits.MAX_ROLE_DESCRIPTION_CHARACTERS,
                role.get("description").toString().length());
        assertEquals(ConfigurationInputLimits.MAX_COMMAND_CHARACTERS,
                role.get("default_command").toString().length());
        assertEquals(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS,
                ((List<?>) role.get("default_args")).getFirst().toString().length());
        assertEquals(ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS,
                ((Map<?, ?>) role.get("default_env")).get("TOKEN").toString().length());

        var storedPreset = configuration.commandPresets().stream()
                .filter(value -> value.id().equals(presetId)).findFirst().orElseThrow();
        assertEquals(ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS,
                storedPreset.displayName().length());
        assertEquals(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS,
                storedPreset.arguments().getFirst().length());
        assertEquals(ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS,
                storedPreset.environment().get("TOKEN").length());
        assertEquals(ConfigurationInputLimits.MAX_SESSION_CAPTURE_STRING_CHARACTERS,
                storedPreset.sessionIdCapture().get("pattern").toString().length());
        var storedRole = configuration.roleTemplates().stream()
                .filter(value -> value.id().equals(roleId)).findFirst().orElseThrow();
        assertEquals(ConfigurationInputLimits.MAX_ROLE_DESCRIPTION_CHARACTERS,
                storedRole.description().length());
        assertEquals(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS,
                storedRole.defaultArguments().getFirst().length());
        assertEquals(ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS,
                storedRole.defaultEnvironment().get("TOKEN").length());
        assertEquals(legacyDisplay.length(), database.<Integer>read(
                "verify complete legacy settings remain durable", connection -> {
                    try (var statement = connection.prepareStatement(
                            "SELECT length(display_name) FROM command_presets WHERE id=?")) {
                        statement.setString(1, presetId);
                        try (var result = statement.executeQuery()) {
                            result.next();
                            return result.getInt(1);
                        }
                    }
                }).intValue());
    }

    @Test void mapsInvalidLegacyDispatchDetailsToTypedUnprocessableEntity() {
        WebTestClient client = client();
        String cookie = uiCookie(client);
        String workspaceId = createWorkspace(client, cookie, "Invalid Dispatch Projection");
        String dispatchId = UUID.randomUUID().toString();
        database.write("seed malformed legacy dispatch", connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO dispatches(
                      id,workspace_id,to_agent_id,text,status,created_at,artifacts)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, dispatchId);
                statement.setString(2, workspaceId);
                statement.setString(3, UUID.randomUUID().toString());
                statement.setString(4, "legacy task");
                statement.setString(5, "queued");
                statement.setLong(6, System.currentTimeMillis());
                statement.setString(7, "{");
                statement.executeUpdate();
            }
            return null;
        });

        client.get().uri("/api/ui/workspaces/{workspace}/dispatches/{dispatch}",
                        workspaceId, dispatchId)
                .header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isEqualTo(422).expectBody()
                .jsonPath("$.error_code").isEqualTo("TEAM_DISPATCH_RECORD_INVALID");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1_024 * 1_024)).build();
    }

    private String createWorkspace(WebTestClient client, String cookie, String name) {
        Map<?, ?> workspace = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", name, "path", temporaryDirectory("termestra-limit-workspace-").toString(),
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        return Objects.requireNonNull(workspace).get("id").toString();
    }

    private int count(String table, String workspaceId) {
        return database.read("count bounded " + table, connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE workspace_id=?")) {
                statement.setString(1, workspaceId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    private static Map<?, ?> find(List<Map> values, String id) {
        return Objects.requireNonNull(values).stream()
                .filter(value -> id.equals(value.get("id"))).findFirst().orElseThrow();
    }

    private static String uiCookie(WebTestClient client) {
        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return Objects.requireNonNull(header).substring(0, header.indexOf(';'));
    }

    private static Path temporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
