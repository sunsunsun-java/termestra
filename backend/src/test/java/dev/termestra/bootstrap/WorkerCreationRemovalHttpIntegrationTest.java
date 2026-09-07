package dev.termestra.bootstrap;

import dev.termestra.execution.application.port.in.AgentExecutionUseCase;
import dev.termestra.execution.application.port.in.AgentLaunchPlanningUseCase;
import dev.termestra.team.adapter.out.runtime.ExecutionWorkerExecution;
import dev.termestra.team.application.port.in.WorkerLaunchIntent;
import dev.termestra.team.application.port.out.WorkerExecution;
import dev.termestra.team.application.port.out.WorkerLaunchProvisioning;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkerCreationRemovalHttpIntegrationTest.StartBoundary.class)
class WorkerCreationRemovalHttpIntegrationTest {
    private static final Path DATA = temp();
    @LocalServerPort int port;
    @Autowired DelayedStart workerExecution;
    @Autowired AgentExecutionUseCase execution;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA::toString);
    }

    @TestConfiguration static class StartBoundary {
        @Bean @Primary DelayedStart delayedWorkerExecution(AgentLaunchPlanningUseCase launches, AgentExecutionUseCase execution) {
            return new DelayedStart(new ExecutionWorkerExecution(launches, execution));
        }
    }

    @Test void removalBetweenStartupAndResponseRefreshReturnsConflictInsteadOfServerError() throws Exception {
        var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
        String header = client.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .returnResult(Map.class).getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie = Objects.requireNonNull(header).split(";")[0];
        Map<?,?> workspace = client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Create/remove race", "path", temp().toString(), "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId = Objects.requireNonNull(workspace).get("id").toString();
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var create = requests.submit(() -> client.post().uri("/api/workspaces/" + workspaceId + "/workers")
                    .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of("name", "Worker", "role", "coder", "autostart", true,
                            "launch", Map.of("type", "startup", "startup_command", "/bin/cat")))
                    .exchange().expectStatus().isEqualTo(409).expectBody(Map.class).returnResult().getResponseBody());
            try {
                assertTrue(workerExecution.started.await(5, TimeUnit.SECONDS));
                List<Map> workers = client.get().uri("/api/ui/workspaces/" + workspaceId + "/team")
                        .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk()
                        .expectBodyList(Map.class).returnResult().getResponseBody();
                String workerId = Objects.requireNonNull(workers).getFirst().get("id").toString();
                client.delete().uri("/api/workspaces/" + workspaceId + "/workers/" + workerId)
                        .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isNoContent();
                workerExecution.release.countDown();
                assertEquals(Map.of("error", "Worker was removed during creation: " + workerId), create.get(5, TimeUnit.SECONDS));
                assertTrue(execution.listActiveSummaries(workspaceId).isEmpty());
                client.get().uri("/api/ui/workspaces/" + workspaceId + "/team")
                        .header(HttpHeaders.COOKIE, cookie).exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
            } finally {
                workerExecution.release.countDown();
                execution.forgetWorkspace(workspaceId);
            }
        }
    }

    static final class DelayedStart implements WorkerExecution {
        private final WorkerExecution delegate;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        DelayedStart(WorkerExecution delegate) { this.delegate = delegate; }
        @Override public WorkerLaunchProvisioning plan(String workspace, WorkerLaunchIntent intent) { return delegate.plan(workspace, intent); }
        @Override public StartedWorker start(String workspace, String worker, String port) {
            StartedWorker run = delegate.start(workspace, worker, port);
            started.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("test did not release startup response");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return run;
        }
    }

    private static Path temp() {
        try { return Files.createTempDirectory("termestra-create-remove-"); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
