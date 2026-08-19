package dev.termestra.bootstrap;

import dev.termestra.auth.application.AgentCredentialService;
import dev.termestra.bootstrap.support.PtyTestFixture;
import dev.termestra.bootstrap.support.TestJavaCommand;
import dev.termestra.execution.application.port.in.AgentExecutionUseCase;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentExecutionHttpIntegrationTest {
    private static final Path DATA_DIRECTORY = temp("termestra-execution-http-");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA_DIRECTORY::toString);
    }

    @LocalServerPort int port;
    @Autowired AgentCredentialService credentials;
    @Autowired AgentExecutionUseCase execution;
    @Autowired SqliteDatabase database;
    @Autowired RuntimeOperationCoordinator operations;

    @Test void returnsATypedRetryableConflictWhenTheWorkspaceRuntimeIsBusy() throws Exception {
        WebTestClient client = WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(10))
                .baseUrl("http://127.0.0.1:" + port).build();
        String cookie = uiCookie(client);
        Path workspacePath = temp("termestra-runtime-busy-workspace-");
        Map<?, ?> workspace = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Runtime Busy Lab", "path", workspacePath.toString(),
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId = Objects.requireNonNull(workspace).get("id").toString();
        String orchestratorId = workspaceId + ":orchestrator";
        configure(client, cookie, workspaceId, orchestratorId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> operations.exclusivelyWithWorkspace(workspaceId, () -> {
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(1, TimeUnit.SECONDS));
            try {
                client.post().uri("/api/workspaces/" + workspaceId + "/agents/" + orchestratorId + "/start")
                        .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of()).exchange()
                        .expectStatus().isEqualTo(409)
                        .expectBody()
                        .jsonPath("$.error_code").isEqualTo("RUNTIME_OPERATION_BUSY")
                        .jsonPath("$.resource_type").isEqualTo("workspace")
                        .jsonPath("$.retryable").isEqualTo(true)
                        .jsonPath("$.retry_after_ms").isEqualTo(1000);
                assertEquals(0, countRuns(workspaceId, orchestratorId));
            } finally {
                release.countDown();
                holder.get(1, TimeUnit.SECONDS);
            }
        }
    }

    @Test void listsOnlyLightweightRunSummariesWhileRunDetailsKeepOutput() {
        WebTestClient client = WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(10))
                .baseUrl("http://127.0.0.1:" + port).build();
        String cookie = uiCookie(client);
        Path workspacePath = temp("termestra-run-summary-workspace-");
        Map<?, ?> workspace = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Run Summary Lab", "path", workspacePath.toString(), "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId = Objects.requireNonNull(workspace).get("id").toString();
        String orchestratorId = workspaceId + ":orchestrator";
        configure(client, cookie, workspaceId, orchestratorId);
        String runId = start(client, cookie, workspaceId, orchestratorId);

        execution.write(runId, "large-history-must-stay-out-of-the-list\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        awaitOutput(client, cookie, runId, "large-history-must-stay-out-of-the-list");

        List<Map> summaries = client.get().uri("/api/ui/workspaces/" + workspaceId + "/runs")
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        Map<?, ?> summary = Objects.requireNonNull(summaries).stream()
                .filter(item -> runId.equals(item.get("run_id"))).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(
                Set.of("run_id", "agent_id", "agent_name", "status", "terminal_input_profile"), summary.keySet());
        org.junit.jupiter.api.Assertions.assertEquals(orchestratorId, summary.get("agent_id"));
        byte[] summaryPayload = client.get().uri("/api/ui/workspaces/" + workspaceId + "/runs")
                .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        org.junit.jupiter.api.Assertions.assertTrue(Objects.requireNonNull(summaryPayload).length < 1_024,
                "one-run summary response must stay below its fixed payload budget");

        Map<?, ?> detail = client.get().uri("/api/runtime/runs/" + runId).header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        org.junit.jupiter.api.Assertions.assertTrue(Objects.requireNonNull(detail).get("output").toString()
                .contains("large-history-must-stay-out-of-the-list"));
        org.junit.jupiter.api.Assertions.assertTrue(detail.containsKey("pid"));
        org.junit.jupiter.api.Assertions.assertTrue(detail.containsKey("exit_code"));

        client.post().uri("/api/runtime/runs/" + runId + "/stop").header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isAccepted();
        awaitStopped(client, cookie, runId);
    }

    @Test void drivesRealPtyAndForwardsTeamMessagesBetweenAgents() {
        WebTestClient client = WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(10))
                .baseUrl("http://127.0.0.1:" + port).build();
        String cookie = uiCookie(client);
        Path workspacePath = temp("termestra-execution-workspace-");
        Map<?, ?> workspace = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Execution Lab", "path", workspacePath.toString(), "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId = Objects.requireNonNull(workspace).get("id").toString();
        Map<?, ?> worker = client.post().uri("/api/workspaces/" + workspaceId + "/workers").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Alice", "role", "coder", "description", "Implement tasks"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workerId = Objects.requireNonNull(worker).get("id").toString();
        String orchestratorId = workspaceId + ":orchestrator";

        configure(client, cookie, workspaceId, orchestratorId);
        configure(client, cookie, workspaceId, workerId);
        String orchestratorRun = start(client, cookie, workspaceId, orchestratorId);
        String workerRun = start(client, cookie, workspaceId, workerId);
        String orchestratorToken = credentials.currentToken(orchestratorId).orElseThrow();
        String workerToken = credentials.currentToken(workerId).orElseThrow();

        execution.write(workerRun,"hello-from-pty\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        awaitOutput(client,cookie,workerRun,"hello-from-pty");
        client.get().uri("/api/ui/workspaces/"+workspaceId+"/team").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].last_pty_line").isEqualTo("hello-from-pty");

        Map<?, ?> sent = client.post().uri("/api/team/send").bodyValue(Map.of(
                        "project_id", workspaceId, "from_agent_id", orchestratorId,
                        "token", orchestratorToken, "to", "Alice", "text", "Build the feature", "runtime_port", Integer.toString(port)))
                .exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult().getResponseBody();
        String dispatchId = Objects.requireNonNull(sent).get("dispatch_id").toString();
        awaitOutput(client, cookie, workerRun, dispatchId);

        client.post().uri("/api/team/report").bodyValue(Map.of(
                        "project_id", workspaceId, "from_agent_id", workerId, "token", workerToken,
                        "dispatch_id", dispatchId, "result", "Feature complete", "artifacts", java.util.List.of("src/Feature.java")))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.forwarded").isEqualTo(true);
        awaitOutput(client, cookie, orchestratorRun, "Feature complete");

        client.post().uri("/api/runtime/runs/" + workerRun + "/stop").header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isAccepted();
        awaitStopped(client, cookie, workerRun);
        client.get().uri("/api/workspaces/" + workspaceId + "/team")
                .header("x-termestra-agent-id", orchestratorId).header("x-termestra-agent-token", orchestratorToken)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].status").isEqualTo("stopped");
    }

    @Test void mapsOnlyTheTermestraRuntimePortRequestToTheAgentEnvironment() {
        WebTestClient client=WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(10))
                .baseUrl("http://127.0.0.1:"+port).build();
        String cookie=uiCookie(client);
        Path workspacePath=temp("termestra-runtime-port-workspace-");
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Runtime Port Lab","path",workspacePath.toString(),"autostart_orchestrator",false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();

        String currentWorker=createWorker(client,cookie,workspaceId,"Current Port Worker");
        configureEnvironmentProbe(client,cookie,workspaceId,currentWorker);
        String currentRun=startWithPort(client,cookie,workspaceId,currentWorker,"runtime_port","41234");
        awaitOutput(client,cookie,currentRun,"port=41234");
        client.post().uri("/api/runtime/runs/"+currentRun+"/stop").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isAccepted();
        awaitStopped(client,cookie,currentRun);

        String foreignWorker=createWorker(client,cookie,workspaceId,"Foreign Port Worker");
        configureEnvironmentProbe(client,cookie,workspaceId,foreignWorker);
        String foreignRun=startWithPort(client,cookie,workspaceId,foreignWorker,"hive_port","41235");
        awaitOutput(client,cookie,foreignRun,"port="+port);
        client.post().uri("/api/runtime/runs/"+foreignRun+"/stop").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isAccepted();
        awaitStopped(client,cookie,foreignRun);
    }

    @Test void fallsBackToPersistedRecoverySummaryOnSecondRunWithoutSession() {
        WebTestClient client=WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(10))
                .baseUrl("http://127.0.0.1:"+port).build();
        String cookie=uiCookie(client);Path workspacePath=temp("termestra-recovery-workspace-");
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Recovery Lab","path",workspacePath.toString(),"autostart_orchestrator",false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();String orchestratorId=workspaceId+":orchestrator";
        configure(client,cookie,workspaceId,orchestratorId);
        String first=start(client,cookie,workspaceId,orchestratorId);
        client.post().uri("/api/workspaces/"+workspaceId+"/user-input").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("text","继续完成 Layer B recovery"))
                .exchange().expectStatus().isAccepted();
        awaitOutput(client,cookie,first,"继续完成 Layer B recovery");
        client.post().uri("/api/runtime/runs/"+first+"/stop").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isAccepted();
        awaitStopped(client,cookie,first);

        String second=start(client,cookie,workspaceId,orchestratorId);
        awaitOutput(client,cookie,second,"无法通过原生 session resume 恢复");
        awaitOutput(client,cookie,second,"继续完成 Layer B recovery");
        int summaries=database.read("count recovery summaries",connection->{try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM messages WHERE workspace_id=? AND worker_id=? AND type='system_recovery_summary'")){statement.setString(1,workspaceId);statement.setString(2,orchestratorId);try(var result=statement.executeQuery()){result.next();return result.getInt(1);}}});
        org.junit.jupiter.api.Assertions.assertEquals(1,summaries);
        client.post().uri("/api/runtime/runs/"+second+"/stop").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isAccepted();
    }

    @Test void retainsOnlyTheMostRecentSixteenCompletedRuns() {
        WebTestClient client=WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(10))
                .baseUrl("http://127.0.0.1:"+port).build();
        String cookie=uiCookie(client);Path workspacePath=temp("termestra-run-retention-workspace-");
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Run Retention Lab","path",workspacePath.toString(),"autostart_orchestrator",false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();
        String shellAgentId=workspaceId+":shell";
        TestJavaCommand command = TestJavaCommand.fixture(PtyTestFixture.class, "exit");
        client.post().uri("/api/workspaces/"+workspaceId+"/agents/"+shellAgentId+"/config")
                .header(HttpHeaders.COOKIE,cookie).bodyValue(Map.of(
                        "command", command.command(), "args", command.arguments()))
                .exchange().expectStatus().isNoContent();

        List<String> completed=new java.util.ArrayList<>();
        for(int index=0;index<17;index++){
            String run=start(client,cookie,workspaceId,shellAgentId);
            awaitSummaryStopped(run);
            completed.add(run);
        }

        org.junit.jupiter.api.Assertions.assertThrows(
                dev.termestra.execution.application.exception.RunNotFound.class,
                ()->execution.getSummary(completed.getFirst()));
        org.junit.jupiter.api.Assertions.assertEquals("exited",execution.getSummary(completed.getLast()).status());
    }

    private static void configure(WebTestClient client, String cookie, String workspace, String agent) {
        TestJavaCommand command = TestJavaCommand.fixture(PtyTestFixture.class, "echo");
        client.post().uri("/api/workspaces/" + workspace + "/agents/" + agent + "/config")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of(
                        "command", command.command(), "args", command.arguments()))
                .exchange().expectStatus().isNoContent();
    }

    private int countRuns(String workspaceId, String agentId) {
        return database.read("count runs after busy start", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM agent_runs WHERE workspace_id=? AND agent_id=?")) {
                statement.setString(1, workspaceId);
                statement.setString(2, agentId);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test lock holder was interrupted", interrupted);
        }
    }

    private static String createWorker(WebTestClient client,String cookie,String workspace,String name){
        Map<?,?> worker=client.post().uri("/api/workspaces/"+workspace+"/workers")
                .header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name",name,"role","coder","description","Probe launch environment"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        return Objects.requireNonNull(worker).get("id").toString();
    }

    private static void configureEnvironmentProbe(WebTestClient client,String cookie,String workspace,String agent){
        TestJavaCommand command = TestJavaCommand.fixture(PtyTestFixture.class, "runtime-port");
        client.post().uri("/api/workspaces/"+workspace+"/agents/"+agent+"/config")
                .header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("command", command.command(), "args", command.arguments()))
                .exchange().expectStatus().isNoContent();
    }

    private static String startWithPort(WebTestClient client,String cookie,String workspace,String agent,
                                        String field,String runtimePort){
        Map<?,?> body=client.post().uri("/api/workspaces/"+workspace+"/agents/"+agent+"/start")
                .header(HttpHeaders.COOKIE,cookie).bodyValue(Map.of(field,runtimePort))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        return Objects.requireNonNull(body).get("run_id").toString();
    }

    private static String start(WebTestClient client, String cookie, String workspace, String agent) {
        Map<?, ?> body = client.post().uri("/api/workspaces/" + workspace + "/agents/" + agent + "/start")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of()).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return Objects.requireNonNull(body).get("run_id").toString();
    }

    private static void awaitOutput(WebTestClient client, String cookie, String run, String expected) {
        AssertionError last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                client.get().uri("/api/runtime/runs/" + run).header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                        .expectBody().jsonPath("$.output").value(value -> {
                            if (!value.toString().contains(expected)) throw new AssertionError("missing output: " + expected);
                        });
                return;
            } catch (AssertionError error) {
                last = error;
                pause();
            }
        }
        throw Objects.requireNonNull(last);
    }

    private static void awaitStopped(WebTestClient client, String cookie, String run) {
        AssertionError last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                client.get().uri("/api/runtime/runs/" + run).header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                        .expectBody().jsonPath("$.status").value(value -> {
                            if (!value.equals("exited") && !value.equals("error")) throw new AssertionError("still active");
                        });
                return;
            } catch (AssertionError error) {
                last = error;
                pause();
            }
        }
        throw Objects.requireNonNull(last);
    }

    private void awaitSummaryStopped(String run) {
        for(int attempt=0;attempt<100;attempt++){
            // A summary intentionally projects a failed/stopping process immediately while its
            // terminal database write is still in flight. Retention is allowed only after that
            // durable transition, so this assertion must wait on the authoritative run view.
            String status=execution.get(run).status();
            if(status.equals("exited")||status.equals("error"))return;
            pause();
        }
        throw new AssertionError("run did not stop: "+run);
    }

    private static void pause() {
        try { Thread.sleep(50); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private static String uiCookie(WebTestClient client) {
        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return Objects.requireNonNull(header).substring(0, header.indexOf(';'));
    }
    private static Path temp(String prefix) {
        try { return Files.createTempDirectory(prefix).toRealPath(); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
}
