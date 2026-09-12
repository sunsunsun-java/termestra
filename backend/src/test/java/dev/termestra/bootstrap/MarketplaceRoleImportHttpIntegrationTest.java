package dev.termestra.bootstrap;

import dev.termestra.execution.adapter.out.persistence.JdbcAgentDirectory;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.team.adapter.out.persistence.JdbcTeamMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketplaceRoleImportHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = temporaryDirectory();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA_DIRECTORY::toString);
    }
    @LocalServerPort int port;
    @Autowired SqliteDatabase database;

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void importsBundledUxArchitectWithoutTruncatingItsRoleOrWorkerDescription(String language) {
        WebTestClient client = client();
        String cookie = cookie(client);
        Map<?, ?> agent = client.get().uri(builder -> builder.path("/api/marketplace/agent")
                        .queryParam("lang", language).queryParam("path", "design/design-ux-architect.md").build())
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        String body = Objects.requireNonNull(agent).get("body").toString();
        assertTrue(body.length() > 4_096, "the bundled fixture must exercise the former import limit");
        String id = createRole(client, cookie, body);
        assertRoleDetail(client, cookie, id, body);
        assertBoundedLists(client, cookie, id, body);
        assertPersistedDescription("role_templates", id, body);

        String edited = body + "\n\nImported role edit " + language;
        client.patch().uri("/api/settings/role-templates/" + id).header(HttpHeaders.COOKIE, cookie)
                .bodyValue(roleRequest(edited)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.description").isEqualTo(edited);
        assertRoleDetail(client, cookie, id, edited);
        assertBoundedLists(client, cookie, id, edited);
        assertPersistedDescription("role_templates", id, edited);

        String workspace = createWorkspace(client, cookie);
        String worker = createWorker(client, cookie, workspace, edited);
        assertPersistedDescription("workers", worker, edited);
        assertEquals(edited, new JdbcTeamMemberRepository(database).findById(workspace, worker).orElseThrow().description());
        assertEquals(edited, new JdbcAgentDirectory(database).find(workspace, worker).orElseThrow().description());
    }

    @Test void acceptsTheFullBodyLimitAndRejectsOneMoreCharacterWithoutLosingTheSavedBody() {
        WebTestClient client = client();
        String cookie = cookie(client);
        String boundary = "B".repeat(65_536);
        String excessive = boundary + "X";
        String id = createRole(client, cookie, boundary);
        assertRoleDetail(client, cookie, id, boundary);
        assertBoundedLists(client, cookie, id, boundary);
        client.post().uri("/api/settings/role-templates").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(roleRequest(excessive)).exchange().expectStatus().isBadRequest();
        client.patch().uri("/api/settings/role-templates/" + id).header(HttpHeaders.COOKIE, cookie)
                .bodyValue(roleRequest(excessive)).exchange().expectStatus().isBadRequest();
        assertRoleDetail(client, cookie, id, boundary);
        assertPersistedDescription("role_templates", id, boundary);

        String workspace = createWorkspace(client, cookie);
        String worker = createWorker(client, cookie, workspace, boundary);
        assertEquals(boundary, new JdbcTeamMemberRepository(database).findById(workspace, worker).orElseThrow().description());
        assertEquals(boundary, new JdbcAgentDirectory(database).find(workspace, worker).orElseThrow().description());
        client.post().uri("/api/workspaces/" + workspace + "/workers").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(workerRequest(excessive)).exchange().expectStatus().isBadRequest();
        assertEquals(1, new JdbcTeamMemberRepository(database).list(workspace).size());
    }

    @Test void missingRoleDetailReturnsNotFound() {
        WebTestClient client = client();
        client.get().uri("/api/settings/role-templates/" + UUID.randomUUID())
                .header(HttpHeaders.COOKIE, cookie(client)).exchange().expectStatus().isNotFound();
    }

    private String createRole(WebTestClient client, String cookie, String body) {
        Map<?, ?> created = client.post().uri("/api/settings/role-templates").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(roleRequest(body)).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertEquals(body, Objects.requireNonNull(created).get("description"));
        return created.get("id").toString();
    }

    private static Map<String, Object> roleRequest(String body) {
        return Map.of("name", "Imported UX " + UUID.randomUUID(), "role_type", "custom", "description", body,
                "default_command", "", "default_args", List.of(), "default_env", Map.of());
    }

    private void assertRoleDetail(WebTestClient client, String cookie, String id, String body) {
        client.get().uri("/api/settings/role-templates/" + id).header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.description").isEqualTo(body);
    }

    private void assertBoundedLists(WebTestClient client, String cookie, String id, String body) {
        for (String path : List.of("/api/settings/role-templates", "/api/ui/settings/role-templates")) {
            List<Map> roles = client.get().uri(path).header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                    .expectBodyList(Map.class).returnResult().getResponseBody();
            Map<?, ?> imported = Objects.requireNonNull(roles).stream().filter(role -> id.equals(role.get("id")))
                    .findFirst().orElseThrow();
            String summary = imported.get("description").toString();
            assertTrue(summary.length() <= 4_096);
            assertTrue(body.startsWith(summary));
            assertFalse(summary.isEmpty());
        }
    }

    private String createWorkspace(WebTestClient client, String cookie) {
        Map<?, ?> created = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Imported roles", "path", temporaryDirectory().toString(),
                        "autostart_orchestrator", false)).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return Objects.requireNonNull(created).get("id").toString();
    }

    private String createWorker(WebTestClient client, String cookie, String workspace, String body) {
        Map<?, ?> created = client.post().uri("/api/workspaces/" + workspace + "/workers")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(workerRequest(body)).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return Objects.requireNonNull(created).get("id").toString();
    }

    private static Map<String, Object> workerRequest(String body) {
        return Map.of("name", "UX " + UUID.randomUUID(), "role", "custom", "description", body, "autostart", false);
    }

    private void assertPersistedDescription(String table, String id, String expected) {
        database.read("verify complete imported description", connection -> {
            try (var statement = connection.prepareStatement("SELECT description FROM " + table + " WHERE id=?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(expected, rows.getString(1)); }
            }
            return null;
        });
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    private static String cookie(WebTestClient client) {
        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return Objects.requireNonNull(header).substring(0, header.indexOf(';'));
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("termestra-marketplace-import-"); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
}
