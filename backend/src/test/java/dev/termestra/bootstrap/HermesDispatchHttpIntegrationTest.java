package dev.termestra.bootstrap;

import dev.termestra.auth.application.AgentCredentialService;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HermesDispatchHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = temp("termestra-hermes-dispatch-http-");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA_DIRECTORY::toString);
    }

    @LocalServerPort int port;
    @Autowired AgentCredentialService credentials;
    @Autowired SqliteDatabase database;

    @Test
    void coldStartDeliversStartupBeforeTaskAndOnlyThenAcknowledgesTheDispatch() {
        WebTestClient client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(20))
                .baseUrl("http://127.0.0.1:" + port)
                .build();
        String cookie = uiCookie(client);
        Map<?, ?> workspace = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Hermes Lab", "path", temp("termestra-hermes-workspace-").toString(),
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId = Objects.requireNonNull(workspace).get("id").toString();
        Map<?, ?> worker = client.post().uri("/api/workspaces/" + workspaceId + "/workers")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Hermes Worker", "role", "custom", "description", "Check delivery"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workerId = Objects.requireNonNull(worker).get("id").toString();

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String fixture = "stty raw -echo; exec \"" + java + "\" -cp \"" + classPath
                + "\" dev.termestra.bootstrap.support.HermesPtyFixture";
        client.post().uri("/api/workspaces/" + workspaceId + "/agents/" + workerId + "/config")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("command", "/bin/sh", "args", List.of("-c", fixture),
                        "interactive_command", "hermes"))
                .exchange().expectStatus().isNoContent();

        String orchestratorId = workspaceId + ":orchestrator";
        String token = credentials.issue(orchestratorId);
        Map<?, ?> result = client.post().uri("/api/team/send")
                .bodyValue(Map.of("project_id", workspaceId, "from_agent_id", orchestratorId,
                        "token", token, "to", "Hermes Worker", "text", "HERMES_DELIVERY_TOKEN",
                        "idempotency_key", UUID.randomUUID().toString()))
                .exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult().getResponseBody();
        String dispatchId = Objects.requireNonNull(result).get("dispatch_id").toString();

        String runId = awaitRun(client, cookie, workspaceId);
        String output = awaitOutput(client, cookie, runId, "FIXTURE_RECEIVED_TASK");
        int startup = output.indexOf("FIXTURE_RECEIVED_STARTUP");
        int task = output.indexOf("FIXTURE_RECEIVED_TASK");
        assertTrue(startup >= 0, output);
        assertTrue(task > startup, output);

        Map<String, Object> stored = database.read("read Hermes dispatch acknowledgement", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT status,submitted_at,delivered_at FROM dispatches WHERE id=?")) {
                statement.setString(1, dispatchId);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    return Map.of("status", rows.getString(1), "submitted_at", rows.getLong(2),
                            "delivered_at", rows.getLong(3));
                }
            }
        });
        assertEquals("submitted", stored.get("status"));
        assertTrue((long) stored.get("submitted_at") > 0);
        assertTrue((long) stored.get("delivered_at") > 0);
    }

    private static String uiCookie(WebTestClient client) {
        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return Objects.requireNonNull(header).substring(0, header.indexOf(';'));
    }

    private static String awaitOutput(WebTestClient client, String cookie, String runId, String expected) {
        String output = "";
        for (int attempt = 0; attempt < 100; attempt++) {
            Map<?, ?> body = client.get().uri("/api/runtime/runs/" + runId)
                    .header(HttpHeaders.COOKIE, cookie)
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            output = Objects.requireNonNull(body).get("output").toString();
            if (output.contains(expected)) return output;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("Missing " + expected + " in PTY output: " + output);
    }

    private static String awaitRun(WebTestClient client, String cookie, String workspaceId) {
        for (int attempt = 0; attempt < 200; attempt++) {
            List<?> runs = client.get().uri("/api/ui/workspaces/" + workspaceId + "/runs")
                    .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                    .expectBody(List.class).returnResult().getResponseBody();
            if (runs != null && !runs.isEmpty()) {
                return ((Map<?, ?>) runs.getFirst()).get("run_id").toString();
            }
            try { Thread.sleep(25); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("Worker run was not started by dispatch delivery");
    }

    private static Path temp(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
