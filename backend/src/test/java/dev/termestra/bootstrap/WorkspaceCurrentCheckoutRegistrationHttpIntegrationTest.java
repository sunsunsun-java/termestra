package dev.termestra.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.workspace.adapter.out.filesystem.browse.NioDirectoryBrowser;
import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.application.port.out.OrchestratorStarter;
import dev.termestra.workspace.application.port.out.browse.DirectoryBrowser;
import dev.termestra.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkspaceCurrentCheckoutRegistrationHttpIntegrationTest.TestStarterConfiguration.class)
class WorkspaceCurrentCheckoutRegistrationHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = createTempDirectory(
            "termestra-current-checkout-registration-data-");

    @LocalServerPort int port;
    @Autowired SqliteDatabase database;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void configureDataDirectory(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", () -> DATA_DIRECTORY.toString());
    }

    @Test void registersTheCurrentCheckoutAndRejectsLegacyBranchSelection() throws Exception {
        Path repository = repository(DATA_DIRECTORY);
        git(repository, "switch", "feature/http");
        String originalHead = git(repository, "rev-parse", "HEAD").strip();
        String originalReflog = git(repository, "reflog", "--format=%H").strip();
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port).build();
        String cookie = uiCookie(client);

        Map<?, ?> probe = Objects.requireNonNull(client.get()
                .uri(builder -> builder.path("/api/fs/probe").queryParam("path", repository).build())
                .header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class)
                .returnResult().getResponseBody());
        assertEquals(Set.of("current_branch", "exists", "is_dir", "is_git_repository",
                "ok", "path", "suggested_name"), probe.keySet());
        assertEquals("feature/http", probe.get("current_branch"));

        Map<?, ?> rejected = Objects.requireNonNull(client.post().uri("/api/workspaces")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of(
                        "registration_id", UUID.randomUUID().toString(),
                        "path", repository.toString(),
                        "name", "Legacy branch request",
                        "autostart_orchestrator", false,
                        "revision_selection", Map.of(
                                "kind", "local_branch",
                                "name", "main",
                                "selection_token", "legacy-token")))
                .exchange().expectStatus().isBadRequest().expectBody(Map.class)
                .returnResult().getResponseBody());
        assertEquals(Set.of("error", "error_code"), rejected.keySet());
        assertEquals("WORKSPACE_REVISION_SELECTION_UNSUPPORTED", rejected.get("error_code"));

        Map<?, ?> malformed = Objects.requireNonNull(client.post().uri("/api/workspaces")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of(
                        "registration_id", UUID.randomUUID().toString(),
                        "path", repository.toString(),
                        "name", "Missing revision kind",
                        "autostart_orchestrator", false,
                        "revision_selection", Map.of()))
                .exchange().expectStatus().isBadRequest().expectBody(Map.class)
                .returnResult().getResponseBody());
        assertEquals("WORKSPACE_REVISION_SELECTION_UNSUPPORTED", malformed.get("error_code"));

        Map<?, ?> removedOptions = Objects.requireNonNull(client.get()
                .uri("/api/workspace-registrations/options?inspection_token=legacy")
                .header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isEqualTo(410).expectBody(Map.class)
                .returnResult().getResponseBody());
        assertEquals(Set.of("error", "error_code"), removedOptions.keySet());
        assertEquals("WORKSPACE_REVISION_OPTIONS_REMOVED", removedOptions.get("error_code"));

        assertEquals("feature/http", git(repository, "branch", "--show-current").strip());
        assertFalse(workspaceExists(repository.toString()));

        String registrationId = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of(
                "registration_id", registrationId,
                "path", repository.toString(),
                "name", "Current Checkout Workspace",
                "autostart_orchestrator", false,
                "revision_selection", Map.of(
                        "kind", "current",
                        "name", "ignored-by-compatibility",
                        "selection_token", "legacy-token"));
        Map<?, ?> created = Objects.requireNonNull(client.post().uri("/api/workspaces")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(request)
                .exchange().expectStatus().isCreated().expectBody(Map.class)
                .returnResult().getResponseBody());

        assertEquals("feature/http", git(repository, "branch", "--show-current").strip());
        assertEquals(originalHead, git(repository, "rev-parse", "HEAD").strip());
        assertEquals(originalReflog, git(repository, "reflog", "--format=%H").strip());
        assertEquals("Current Checkout Workspace", created.get("name"));
        assertEquals("completed", attemptState(registrationId));
        assertEquals("active", workspaceState((String) created.get("id")));

        Map<?, ?> status = Objects.requireNonNull(client.get()
                .uri("/api/workspace-registrations/{id}", registrationId)
                .header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class)
                .returnResult().getResponseBody());
        assertEquals(Set.of("registration_id", "status", "workspace_id", "error_code",
                "source_revision_changed", "observed_head"), status.keySet());
        assertEquals("completed", status.get("status"));
        assertEquals(created.get("id"), status.get("workspace_id"));
        assertEquals(false, status.get("source_revision_changed"));
        assertEquals(null, status.get("observed_head"));
        assertTrue(json.writeValueAsBytes(status).length < 16 * 1024);

        client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(request)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(created.get("id"));
    }

    private String attemptState(String registrationId) {
        return database.read("read registration state", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT state FROM workspace_registration_attempts WHERE registration_id=?")) {
                statement.setString(1, registrationId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            }
        });
    }

    private String workspaceState(String workspaceId) {
        return database.read("read workspace lifecycle", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT lifecycle_state FROM workspaces WHERE id=?")) {
                statement.setString(1, workspaceId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            }
        });
    }

    private boolean workspaceExists(String path) {
        return database.read("find workspace path", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM workspaces WHERE canonical_path=? AND deleted_at IS NULL")) {
                statement.setString(1, path);
                try (var result = statement.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    private static Path repository(Path parent) throws Exception {
        Path repository = Files.createTempDirectory(parent, "http-workspace-").toRealPath();
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.email", "test@termestra.dev");
        git(repository, "config", "user.name", "Termestra Test");
        Files.writeString(repository.resolve("README.md"), "test");
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "initial");
        git(repository, "branch", "feature/http");
        return repository;
    }

    private static String git(Path path, String... arguments)
            throws IOException, InterruptedException {
        ArrayList<String> command = new ArrayList<>(List.of("git", "-C", path.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException(String.join(" ", command) + "\n" + output);
        return output;
    }

    private static String uiCookie(WebTestClient client) {
        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders()
                .getFirst(HttpHeaders.SET_COOKIE);
        return Objects.requireNonNull(header).substring(0, header.indexOf(';'));
    }

    private static Path createTempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @TestConfiguration
    static class TestStarterConfiguration {
        @Bean @Primary DirectoryBrowser currentCheckoutDirectoryBrowser() {
            return new NioDirectoryBrowser(DATA_DIRECTORY);
        }

        @Bean @Primary OrchestratorStarter currentCheckoutStarter() {
            return new OrchestratorStarter() {
                @Override public OrchestratorStartView prepare(
                        Workspace workspace, String startupCommand, String commandPresetId,
                        String modelId, Long expectedPresetRevision, boolean autostart) {
                    return OrchestratorStartView.disabled();
                }
            };
        }
    }
}
