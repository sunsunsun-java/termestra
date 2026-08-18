package dev.termestra.bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.workspace.application.port.out.browse.NativeFolderPicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkspaceHttpIntegrationTest.PickerTestConfiguration.class)
class WorkspaceHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = createTempDirectory("termestra-http-data-");
    private static final Path PICKED_DIRECTORY = createTempDirectory("termestra-picked-workspace-");
    private static Path workspaceDirectory;
    @LocalServerPort int port;
    @Autowired SqliteDatabase database;

    @TestConfiguration
    static class PickerTestConfiguration {
        @Bean
        @Primary
        NativeFolderPicker testNativeFolderPicker() {
            return () -> NativeFolderPicker.NativePickResult.selected(PICKED_DIRECTORY.toString());
        }
    }

    @BeforeAll static void createWorkspaceDirectory() throws IOException {
        workspaceDirectory = Files.createTempDirectory("termestra-workspace-").toRealPath();
    }

    @DynamicPropertySource static void configureDataDirectory(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", () -> DATA_DIRECTORY.toString());
    }

    private static Path createTempDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    @Test void createsAndListsAWorkspaceThroughTheRealHttpAndSqliteBoundaries() throws IOException {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        String cookie = client.get().uri("/api/ui/session").exchange()
                .expectStatus().isOk().expectHeader().exists(HttpHeaders.SET_COOKIE)
                .returnResult(Map.class).getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String token = cookie.substring(0, cookie.indexOf(';'));

        byte[] createdBody = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, token)
                .bodyValue(Map.of("path", workspaceDirectory.toString(), "name", "Learning Lab",
                        "startup_command", "sleep 1", "command_preset_id", "claude",
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.name").isEqualTo("Learning Lab")
                .jsonPath("$.path").isEqualTo(workspaceDirectory.toString())
                .jsonPath("$.orchestrator_start.ok").isEqualTo(false)
                .jsonPath("$.orchestrator_start.error").doesNotExist()
                .jsonPath("$.orchestrator_start.run_id").doesNotExist()
                .returnResult().getResponseBody();

        Map<?,?> created = new com.fasterxml.jackson.databind.ObjectMapper().readValue(createdBody, Map.class);
        String workspaceId = created.get("id").toString();
        client.post().uri("/api/workspaces/{workspaceId}/agents/{agentId}/start",
                        workspaceId, workspaceId + ":orchestrator")
                .header(HttpHeaders.COOKIE, token).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.error").value(value -> assertTrue(value.toString().contains("input prompt")));

        client.get().uri("/api/workspaces").header(HttpHeaders.COOKIE, token).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[0].name").isEqualTo("Learning Lab");

        client.post().uri("/api/workspaces/missing/open").header(HttpHeaders.COOKIE, token)
                .bodyValue(Map.of("target_id", "intellij-idea")).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("Workspace not found");
        client.post().uri("/api/workspaces/{workspaceId}/open", workspaceId)
                .header(HttpHeaders.COOKIE, token).bodyValue(Map.of("target_id", "unknown"))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Unknown open target")
                .jsonPath("$.target_id").isEqualTo("unknown");
    }

    @Test void configuresTheDefaultOrchestratorWhenNoCommandOverrideIsProvided() throws IOException {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie = Objects.requireNonNull(header).substring(0, header.indexOf(';'));
        Path path = Files.createTempDirectory("termestra-default-orchestrator-").toRealPath();

        byte[] createdBody = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Default Orchestrator", "path", path.toString(),
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.orchestrator_start.ok").isEqualTo(false)
                .jsonPath("$.orchestrator_start.error").doesNotExist()
                .returnResult().getResponseBody();

        Map<?, ?> workspace = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(Objects.requireNonNull(createdBody), Map.class);
        String workspaceId = workspace.get("id").toString();
        database.read("verify default orchestrator configuration", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT command,args_json,env_json FROM agent_launch_configs WHERE workspace_id=? AND agent_id=?")) {
                statement.setString(1, workspaceId);
                statement.setString(2, workspaceId + ":orchestrator");
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertFalse(result.getString("command").isBlank());
                    assertNotNull(result.getString("args_json"));
                    assertNotNull(result.getString("env_json"));
                }
            }
            return null;
        });
    }

    @Test void rejectsWorkspaceAccessWithoutAUiSession() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        client.get().uri("/api/workspaces")
                .header(HttpHeaders.COOKIE, "foreign_ui_token=invalid-token")
                .exchange().expectStatus().isForbidden().expectBody()
                .jsonPath("$.error_code").isEqualTo("UI_SESSION_INVALID")
                .jsonPath("$.error").isEqualTo("UI endpoint requires valid UI token");

        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie = Objects.requireNonNull(header).substring(0, header.indexOf(';'));
        assertTrue(cookie.startsWith("termestra_ui_token="));
        client.get().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk();
    }

    @Test void acceptsANativePickerDirectoryOutsideTheBrowseSandboxThroughHttp() throws IOException {
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port).build();
        String header = client.get().uri("/api/ui/session").exchange()
                .expectStatus().isOk().expectBody(Map.class).returnResult()
                .getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie = Objects.requireNonNull(header).substring(0, header.indexOf(';'));

        client.post().uri("/api/fs/pick-folder").header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.canceled").isEqualTo(false)
                .jsonPath("$.error").doesNotExist()
                .jsonPath("$.path").isEqualTo(PICKED_DIRECTORY.toRealPath().toString())
                .jsonPath("$.probe.ok").isEqualTo(true)
                .jsonPath("$.probe.is_dir").isEqualTo(true)
                .jsonPath("$.probe.suggested_name")
                .isEqualTo(PICKED_DIRECTORY.getFileName().toString());
    }

    @Test void rejectsOversizedJsonWithTheStableErrorContract() {
        WebTestClient client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();
        String header=client.get().uri("/api/ui/session").exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie=Objects.requireNonNull(header).substring(0,header.indexOf(';'));
        String body="{\"path\":\""+"x".repeat(1024*1024)+"\"}";
        client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.error").isEqualTo("Request body too large");
    }

    @Test void hardDeletesTheCompleteWorkspaceGraphThroughHttp() throws IOException {
        WebTestClient client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();
        String header=client.get().uri("/api/ui/session").exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie=Objects.requireNonNull(header).substring(0,header.indexOf(';'));
        Path path=Files.createTempDirectory("termestra-delete-workspace-").toRealPath();
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Delete Lab","path",path.toString(),"autostart_orchestrator",false)).exchange()
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();
        Map<?,?> worker=client.post().uri("/api/workspaces/"+workspaceId+"/workers").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Alice","role","coder")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String workerId=Objects.requireNonNull(worker).get("id").toString();long now=System.currentTimeMillis();
        database.write("seed workspace lifecycle",connection->{try(var statement=connection.createStatement()){
            statement.executeUpdate("INSERT INTO messages(workspace_id,worker_id,type,text,artifacts,created_at) VALUES('"+workspaceId+"','"+workerId+"','send','delete','[]',"+now+")");
            statement.executeUpdate("INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at,artifacts) VALUES('"+UUID.randomUUID()+"','"+workspaceId+"','"+workerId+"','delete','queued',"+now+",'[]')");
            statement.executeUpdate("INSERT INTO agent_launch_configs(workspace_id,agent_id,command,args_json,created_at,updated_at) VALUES('"+workspaceId+"','"+workerId+"','cat','[]',"+now+","+now+")");
            statement.executeUpdate("INSERT INTO agent_sessions(agent_id,workspace_id,last_session_id,updated_at) VALUES('"+workerId+"','"+workspaceId+"','session-delete',"+now+")");
            statement.executeUpdate("INSERT INTO agent_runs(run_id,agent_id,status,started_at,created_at,updated_at) VALUES('"+UUID.randomUUID()+"','"+workerId+"','running',"+now+","+now+","+now+")");
            statement.executeUpdate("UPDATE app_state SET value='"+workspaceId+"' WHERE key='active_workspace_id'");
        }return null;});

        client.delete().uri("/api/workspaces/"+workspaceId).header(HttpHeaders.COOKIE,cookie).exchange().expectStatus().isNoContent();
        for(String table:List.of("workspaces","workers","messages","dispatches","agent_launch_configs","agent_sessions"))assertEquals(0,countByWorkspace(table,workspaceId),table);
        int remainingRuns=database.read("count deleted runs",connection->{try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM agent_runs WHERE agent_id=?")){statement.setString(1,workerId);try(var result=statement.executeQuery()){result.next();return result.getInt(1);}}});
        assertEquals(0,remainingRuns);
        database.read("verify cleared active workspace",connection->{try(var statement=connection.prepareStatement("SELECT value FROM app_state WHERE key='active_workspace_id'");var result=statement.executeQuery()){assertTrue(result.next());assertNull(result.getString(1));}return null;});
    }

    private int countByWorkspace(String table,String workspaceId){return database.read("count deleted "+table,connection->{String column=table.equals("workspaces")?"id":"workspace_id";try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?")){statement.setString(1,workspaceId);try(var result=statement.executeQuery()){result.next();return result.getInt(1);}}});}
}
