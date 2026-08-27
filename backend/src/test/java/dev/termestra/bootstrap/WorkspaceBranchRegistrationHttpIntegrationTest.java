package dev.termestra.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.application.port.out.OrchestratorStarter;
import dev.termestra.workspace.application.port.out.browse.DirectoryBrowser;
import dev.termestra.workspace.adapter.out.filesystem.browse.NioDirectoryBrowser;
import dev.termestra.workspace.application.service.WorkspaceRegistrationTokenCodec;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkspaceBranchRegistrationHttpIntegrationTest.TestStarterConfiguration.class)
class WorkspaceBranchRegistrationHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = createTempDirectory("termestra-branch-registration-data-");

    @LocalServerPort int port;
    @Autowired SqliteDatabase database;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void configureDataDirectory(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", () -> DATA_DIRECTORY.toString());
    }

    @Test void scansAndChecksOutAnExistingLocalBranchBeforeActivatingTheWorkspace() throws Exception {
        Path repository = repository(DATA_DIRECTORY);
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port).build();
        String cookie = uiCookie(client);

        Map<?, ?> probe = Objects.requireNonNull(client.get()
                .uri(builder -> builder.path("/api/fs/probe").queryParam("path", repository).build())
                .header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody());
        String inspectionToken = (String) probe.get("git_inspection_token");
        assertNotNull(inspectionToken);

        Map<?, ?> options = Objects.requireNonNull(client.get()
                .uri(builder -> builder.path("/api/workspace-registrations/options")
                        .queryParam("inspection_token", inspectionToken).build())
                .header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody());
        assertEquals(Set.of("canonical_path", "head", "changes", "branches", "next_cursor"),
                options.keySet());
        assertEquals(Set.of("kind", "name", "oid"), ((Map<?, ?>) options.get("head")).keySet());
        assertEquals(Set.of("count", "count_accuracy", "state"),
                ((Map<?, ?>) options.get("changes")).keySet());
        assertTrue(json.writeValueAsBytes(options).length < 1024 * 1024,
                "bounded options payload should remain below one MiB");
        Map<?, ?> feature = ((List<?>) options.get("branches")).stream()
                .map(Map.class::cast).filter(branch -> "feature/http".equals(branch.get("name")))
                .findFirst().orElseThrow();
        assertEquals(Set.of("name", "current", "selectable", "blocked_reason", "selection_token"),
                feature.keySet());
        String selectionToken = (String) feature.get("selection_token");
        assertNotNull(selectionToken);
        String registrationId = UUID.randomUUID().toString();
        Map<String, Object> registrationRequest = Map.of(
                "registration_id", registrationId,
                "path", repository.toString(),
                "name", "Branch Workspace",
                "autostart_orchestrator", false,
                "revision_selection", Map.of(
                        "kind", "local_branch",
                        "name", "feature/http",
                        "selection_token", selectionToken));

        Map<?, ?> created = Objects.requireNonNull(client.post().uri("/api/workspaces")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(registrationRequest)
                .exchange().expectStatus().isCreated().expectBody(Map.class)
                .returnResult().getResponseBody());

        assertEquals("feature/http", git(repository, "branch", "--show-current").strip());
        assertEquals("Branch Workspace", created.get("name"));
        assertEquals("completed", attemptState(registrationId));
        assertEquals("active", workspaceState((String) created.get("id")));
        Map<?, ?> status = Objects.requireNonNull(client.get()
                .uri("/api/workspace-registrations/{id}", registrationId)
                .header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody());
        assertEquals(Set.of("registration_id", "status", "workspace_id", "error_code",
                "source_revision_changed", "observed_head"), status.keySet());
        assertEquals("completed", status.get("status"));
        assertEquals(true, status.get("source_revision_changed"));
        assertEquals(created.get("id"), status.get("workspace_id"));
        assertEquals(Set.of("kind", "name", "oid"),
                ((Map<?, ?>) status.get("observed_head")).keySet());
        assertTrue(json.writeValueAsBytes(status).length < 16 * 1024,
                "registration status payload should remain compact");

        client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(registrationRequest)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(created.get("id"));
    }

    private String attemptState(String registrationId) {
        return database.read("read registration state", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT state FROM workspace_registration_attempts WHERE registration_id=?")) {
                statement.setString(1, registrationId);
                try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
            }
        });
    }

    private String workspaceState(String workspaceId) {
        return database.read("read workspace lifecycle", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT lifecycle_state FROM workspaces WHERE id=?")) {
                statement.setString(1, workspaceId);
                try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
            }
        });
    }

    private static Path repository(Path parent) throws Exception {
        Path repository = Files.createTempDirectory(parent, "http-branch-workspace-").toRealPath();
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.email", "test@termestra.dev");
        git(repository, "config", "user.name", "Termestra Test");
        Files.writeString(repository.resolve("README.md"), "test");
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "initial");
        git(repository, "branch", "feature/http");
        return repository;
    }

    private static String git(Path path, String... arguments) throws IOException, InterruptedException {
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
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return Objects.requireNonNull(header).substring(0, header.indexOf(';'));
    }

    private static Path createTempDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    @TestConfiguration
    static class TestStarterConfiguration {
        @Bean @Primary DirectoryBrowser branchRegistrationDirectoryBrowser(
                WorkspaceRegistrationTokenCodec tokens) {
            return new NioDirectoryBrowser(DATA_DIRECTORY, tokens);
        }

        @Bean @Primary OrchestratorStarter branchRegistrationStarter() {
            return new OrchestratorStarter() {
                @Override public OrchestratorStartView prepare(
                        Workspace workspace, String startupCommand, String commandPresetId,
                        boolean autostart) {
                    return OrchestratorStartView.disabled();
                }
            };
        }
    }
}
