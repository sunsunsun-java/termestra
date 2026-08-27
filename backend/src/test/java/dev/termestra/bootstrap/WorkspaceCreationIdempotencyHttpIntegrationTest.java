package dev.termestra.bootstrap;

import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.tasks.application.service.TeamProtocolDocument;
import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.application.port.out.OrchestratorStarter;
import dev.termestra.workspace.domain.model.Workspace;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkspaceCreationIdempotencyHttpIntegrationTest.StarterTestConfiguration.class)
class WorkspaceCreationIdempotencyHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = createTempDirectory("termestra-workspace-idempotency-data-");

    @LocalServerPort int port;
    @Autowired SqliteDatabase database;
    @Autowired CountingOrchestratorStarter starter;

    @DynamicPropertySource static void configureDataDirectory(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", () -> DATA_DIRECTORY.toString());
    }

    @BeforeEach void resetStarter() { starter.reset(); }

    @Test void concurrentPostsForTheSameRealPathReturnOneWorkspaceAndStartOneOrchestrator() throws Exception {
        Path workspace = Files.createTempDirectory("termestra-idempotent-workspace-").toRealPath();
        WebTestClient client = client();
        String cookie = uiCookie(client);
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<EntityExchangeResult<Map>> create = () -> {
            start.await();
            return client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                    .bodyValue(Map.of("path", workspace.toString(), "name", "Concurrent Lab",
                            "autostart_orchestrator", true))
                    .exchange().expectStatus().value(status -> assertTrue(status == 200 || status == 201))
                    .expectBody(Map.class).returnResult();
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<EntityExchangeResult<Map>> firstFuture = executor.submit(create);
            Future<EntityExchangeResult<Map>> secondFuture = executor.submit(create);
            EntityExchangeResult<Map> first = firstFuture.get(10, TimeUnit.SECONDS);
            EntityExchangeResult<Map> second = secondFuture.get(10, TimeUnit.SECONDS);
            Map<?, ?> firstBody = Objects.requireNonNull(first.getResponseBody());
            Map<?, ?> secondBody = Objects.requireNonNull(second.getResponseBody());

            assertEquals(firstBody.get("id"), secondBody.get("id"));
            assertEquals(Set.of(200, 201), Set.of(first.getStatus().value(), second.getStatus().value()));
            assertEquals(1, starter.calls());
            assertEquals(1, countWorkspacesAt(workspace));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void identicalNamesRemainValidForDifferentCanonicalPaths() throws IOException {
        Path firstPath = Files.createTempDirectory("termestra-same-name-a-").toRealPath();
        Path secondPath = Files.createTempDirectory("termestra-same-name-b-").toRealPath();
        WebTestClient client = client();
        String cookie = uiCookie(client);

        Map<?, ?> first = createWithoutAutostart(client, cookie, firstPath, "Shared Name");
        Map<?, ?> second = createWithoutAutostart(client, cookie, secondPath, "Shared Name");

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(List.of(firstPath.toString(), secondPath.toString()), List.of(first.get("path"), second.get("path")));
        assertEquals(2, starter.calls());
    }

    @Test void creatingAWorkspaceInitializesOnlyTermestraMetadata() throws IOException {
        Path workspace = Files.createTempDirectory("termestra-legacy-metadata-").toRealPath();
        Path legacy = Files.createDirectory(workspace.resolve(".hive"));
        Path legacyTasks = Files.writeString(legacy.resolve("tasks.md"), "legacy tasks");
        Path legacyProtocol = Files.writeString(legacy.resolve("PROTOCOL.md"), "legacy protocol");
        WebTestClient client = client();

        createWithoutAutostart(client, uiCookie(client), workspace, "Independent Workspace");

        assertEquals("", Files.readString(workspace.resolve(".termestra/tasks.md")));
        assertEquals(TeamProtocolDocument.content(), Files.readString(workspace.resolve(".termestra/PROTOCOL.md")));
        assertEquals("legacy tasks", Files.readString(legacyTasks));
        assertEquals("legacy protocol", Files.readString(legacyProtocol));
    }

    private Map<?, ?> createWithoutAutostart(WebTestClient client, String cookie, Path path, String name) {
        return Objects.requireNonNull(client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("path", path.toString(), "name", name, "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody());
    }

    private int countWorkspacesAt(Path workspace) {
        return database.read("count canonical workspaces", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM workspaces WHERE canonical_path=? AND deleted_at IS NULL")) {
                statement.setString(1, workspace.toString());
                try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
            }
        });
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
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
    static class StarterTestConfiguration {
        @Bean @Primary CountingOrchestratorStarter countingOrchestratorStarter() {
            return new CountingOrchestratorStarter();
        }
    }

    static final class CountingOrchestratorStarter implements OrchestratorStarter {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public OrchestratorStartView prepare(Workspace workspace, String startupCommand,
                                                       String commandPresetId,String modelId,
                                                       Long expectedPresetRevision,boolean autostart) {
            Path metadata = Path.of(workspace.path().value()).resolve(".termestra");
            if (!Files.isRegularFile(metadata.resolve("tasks.md"))
                    || !Files.isRegularFile(metadata.resolve("PROTOCOL.md"))) {
                throw new IllegalStateException("Workspace metadata was not initialized before startup");
            }
            calls.incrementAndGet();
            if (!autostart) return OrchestratorStartView.disabled();
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating orchestrator startup", interrupted);
            }
            return new OrchestratorStartView(true, null, "test-run-" + workspace.id());
        }

        int calls() { return calls.get(); }
        void reset() { calls.set(0); }
    }
}
