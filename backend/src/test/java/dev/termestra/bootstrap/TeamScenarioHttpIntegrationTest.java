package dev.termestra.bootstrap;

import dev.termestra.execution.application.port.in.AgentExecutionUseCase;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeamScenarioHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = temp("termestra-scenario-http-");
    private static final Map<String,String> BUILTIN_COMMANDS = Map.ofEntries(
            Map.entry("claude","claude"),Map.entry("codex","codex"),
            Map.entry("opencode","opencode"),Map.entry("gemini","gemini"),
            Map.entry("hermes","hermes"),Map.entry("qwen","qwen"),Map.entry("pi","pi"),
            Map.entry("agy","agy"),Map.entry("cursor","cursor-agent"),Map.entry("grok","grok"));

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA_DIRECTORY::toString);
    }

    @LocalServerPort int port;
    @Autowired SqliteDatabase database;
    @Autowired AgentExecutionUseCase execution;
    private final List<String> workspaces = new ArrayList<>();

    @BeforeEach void preparePresets() { restorePresets(); }

    @AfterEach void stopRunsAndRestorePresets() {
        workspaces.forEach(execution::forgetWorkspace);
        workspaces.clear();
        restorePresets();
    }

    @Test void assemblesStartsAndHandsGoalToTheOrchestrator() {
        WebTestClient client = client();
        String cookie = uiCookie(client);
        useCatForClaude();
        String workspaceId = activeWorkspace(client, cookie);

        Map<?,?> response = client.post()
                .uri("/api/workspaces/" + workspaceId + "/scenarios/build_review_test/apply")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("goal", "实现一键组队并验证真实链路", "locale", "zh"))
                .exchange().expectStatus().isCreated().expectBody(Map.class)
                .returnResult().getResponseBody();
        List<?> created = (List<?>) Objects.requireNonNull(response).get("created_workers");
        assertEquals(3, created.size());
        assertEquals(List.of("coder", "reviewer", "tester"), created.stream()
                .map(value -> Objects.toString(((Map<?, ?>) value).get("role"))).toList());
        assertEquals(Boolean.TRUE, response.get("injected"));

        client.get().uri("/api/ui/workspaces/" + workspaceId + "/team")
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[0].role").isEqualTo("coder")
                .jsonPath("$[1].role").isEqualTo("reviewer")
                .jsonPath("$[2].role").isEqualTo("tester")
                .jsonPath("$[0].command_preset_id").isEqualTo("claude");

        String orchestratorRun = execution.listActiveSummaries(workspaceId).stream()
                .filter(run -> run.agentId().equals(workspaceId + ":orchestrator"))
                .findFirst().orElseThrow().runId();
        awaitOutput(client, cookie, orchestratorRun, "实现一键组队并验证真实链路");
        awaitOutput(client, cookie, orchestratorRun, "team list");
        assertEquals("zh", appState("workspace." + workspaceId + ".ui_language"));
    }

    @Test void rejectsInactiveOrchestratorBeforeCreatingMembersAndPersistsLocale() {
        WebTestClient client = client();
        String cookie = uiCookie(client);
        String workspaceId = createWorkspace(client, cookie);

        client.post().uri("/api/workspaces/" + workspaceId + "/scenarios/docs_pipeline/apply")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("goal", "写一份迁移说明", "locale", "zh"))
                .exchange().expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.error").isEqualTo(
                        "Start the Orchestrator first — the scenario goal is handed to its terminal");
        client.get().uri("/api/ui/workspaces/" + workspaceId + "/team")
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(0);
        assertEquals("zh", appState("workspace." + workspaceId + ".ui_language"));
    }

    @Test void validatesWorkspaceAndScenarioBeforeTheGoal() {
        WebTestClient client = client();
        String cookie = uiCookie(client);
        client.post().uri("/api/workspaces/missing/scenarios/build_review_test/apply")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of("goal", "x"))
                .exchange().expectStatus().isNotFound().expectBody()
                .jsonPath("$.error").isEqualTo("Workspace not found");

        String workspaceId = createWorkspace(client, cookie);
        client.post().uri("/api/workspaces/" + workspaceId + "/scenarios/unknown/apply")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of("goal", "x"))
                .exchange().expectStatus().isNotFound().expectBody()
                .jsonPath("$.error").isEqualTo("Unknown scenario: unknown");
        client.post().uri("/api/workspaces/" + workspaceId + "/scenarios/build_review_test/apply")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of("goal", "   "))
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error").isEqualTo("Missing goal");
    }

    @Test void rollsBackTheCurrentMemberWhenItsLaunchConfigurationCannotPersist() {
        WebTestClient client = client();
        String cookie = uiCookie(client);
        useCatForClaude();
        String workspaceId = activeWorkspace(client, cookie);
        database.write("block scenario launch", connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER block_scenario_launch BEFORE INSERT ON agent_launch_configs BEGIN SELECT RAISE(ABORT, 'blocked scenario launch'); END");
            }
            return null;
        });
        try {
            client.post().uri("/api/workspaces/" + workspaceId + "/scenarios/research_factcheck/apply")
                    .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of("goal", "调查事务边界"))
                    .exchange().expectStatus().is5xxServerError();
        } finally {
            database.write("unblock scenario launch", connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP TRIGGER block_scenario_launch");
                }
                return null;
            });
        }
        client.get().uri("/api/ui/workspaces/" + workspaceId + "/team")
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test void keepsCreatedMembersWhenStartingTheScenarioFails() {
        WebTestClient client = client();
        String cookie = uiCookie(client);
        useCatForClaude();
        String workspaceId = activeWorkspace(client, cookie);
        database.write("make scenario CLIs unavailable", connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE command_presets SET env='{\"PATH\":\"\"}' WHERE is_builtin=1");
                statement.executeUpdate("UPDATE command_presets SET command='/definitely/missing/termestra-agent' WHERE id='claude'");
            }
            return null;
        });

        client.post().uri("/api/workspaces/" + workspaceId + "/scenarios/build_review_test/apply")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of("goal", "保留部分成功语义"))
                .exchange().expectStatus().is5xxServerError();
        client.get().uri("/api/ui/workspaces/" + workspaceId + "/team")
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(3);
    }

    private String activeWorkspace(WebTestClient client, String cookie) {
        String workspaceId = createWorkspace(client, cookie);
        String orchestratorId = workspaceId + ":orchestrator";
        client.post().uri("/api/workspaces/" + workspaceId + "/agents/" + orchestratorId + "/config")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of(
                        "command", "/bin/cat", "args", List.of(),
                        "command_preset_id", "claude", "interactive_command", "/bin/cat"))
                .exchange().expectStatus().isNoContent();
        client.post().uri("/api/workspaces/" + workspaceId + "/agents/" + orchestratorId + "/start")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of())
                .exchange().expectStatus().isCreated();
        return workspaceId;
    }

    private String createWorkspace(WebTestClient client, String cookie) {
        Map<?,?> workspace = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("path", temp("termestra-scenario-workspace-").toString(),
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class)
                .returnResult().getResponseBody();
        String id = Objects.requireNonNull(workspace).get("id").toString();
        workspaces.add(id);
        return id;
    }

    private void useCatForClaude() {
        database.write("configure test scenario CLI", connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE command_presets SET command='/bin/cat',args_json='[]',env='{}',yolo_args_json='[]' WHERE id='claude'")) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void restorePresets() {
        database.write("restore built-in scenario CLIs", connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE command_presets SET command=?,env='{}' WHERE id=?")) {
                for (Map.Entry<String,String> preset : BUILTIN_COMMANDS.entrySet()) {
                    statement.setString(1, preset.getValue());
                    statement.setString(2, preset.getKey());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    private String appState(String key) {
        return database.read("read scenario locale", connection -> {
            try (var statement = connection.prepareStatement("SELECT value FROM app_state WHERE key=?")) {
                statement.setString(1, key);
                try (var result = statement.executeQuery()) { return result.next() ? result.getString(1) : null; }
            }
        });
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(15))
                .baseUrl("http://127.0.0.1:" + port).build();
    }

    private static String uiCookie(WebTestClient client) {
        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return Objects.requireNonNull(header).substring(0, header.indexOf(';'));
    }

    private static void awaitOutput(WebTestClient client, String cookie, String runId, String expected) {
        AssertionError last = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            try {
                client.get().uri("/api/runtime/runs/" + runId).header(HttpHeaders.COOKIE, cookie)
                        .exchange().expectStatus().isOk().expectBody()
                        .jsonPath("$.output").value(value -> {
                            if (!value.toString().contains(expected)) throw new AssertionError("missing " + expected);
                        });
                return;
            } catch (AssertionError failure) {
                last = failure;
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        throw Objects.requireNonNull(last);
    }

    private static Path temp(String prefix) {
        try { return Files.createTempDirectory(prefix).toRealPath(); }
        catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
