package dev.termestra.bootstrap;

import dev.termestra.terminal.application.port.in.TerminalChannelUseCase;
import dev.termestra.terminal.application.port.in.TerminalOutputSession;
import dev.termestra.terminal.application.port.in.TerminalRunStatusView;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TerminalWebSocketExitDrainIntegrationTest.ExitConfiguration.class)
class TerminalWebSocketExitDrainIntegrationTest {
    private static final Path DATA_DIRECTORY = temporaryDirectory();
    private static final String FINAL_OUTPUT = "x".repeat(110 * 1024) + "\r\nFATAL: operation failed\r\n";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA_DIRECTORY::toString);
    }
    @LocalServerPort int port;
    @Autowired ExitTerminal terminal;

    @Test void exitWaitsForTheFinalRenderingAckAcrossTheRealWebSocketPair() throws Exception {
        try (Pair pair = connect(false)) {
            pair.io.sendText("large", true).join();
            pair.output.awaitLength(100 * 1024);
            assertThrows(TimeoutException.class, () -> pair.controlMessages.exit.get(400, TimeUnit.MILLISECONDS));
            pair.ack(100 * 1024);
            pair.output.awaitLength(FINAL_OUTPUT.length());
            assertEquals(FINAL_OUTPUT, pair.output.text());
            assertThrows(TimeoutException.class, () -> pair.controlMessages.exit.get(400, TimeUnit.MILLISECONDS));
            pair.ack(FINAL_OUTPUT.length() - 100 * 1024);
            assertTrue(pair.controlMessages.exit.get(5, TimeUnit.SECONDS).contains("\"code\":1"));
        }
    }

    @Test void evenOneFinalByteNeedsAnAckButEmptyAndAlreadyExitedRunsDoNot() throws Exception {
        try (Pair pair = connect(false)) {
            pair.io.sendText("small", true).join();
            pair.output.awaitLength(1);
            assertThrows(TimeoutException.class, () -> pair.controlMessages.exit.get(400, TimeUnit.MILLISECONDS));
            pair.ack(1);
            pair.controlMessages.exit.get(5, TimeUnit.SECONDS);
        }
        try (Pair pair = connect(false)) {
            pair.io.sendText("empty", true).join();
            pair.controlMessages.exit.get(5, TimeUnit.SECONDS);
            assertEquals("", pair.output.text());
        }
        try (Pair pair = connect(true)) {
            pair.controlMessages.exit.get(5, TimeUnit.SECONDS);
            assertTrue(pair.controlMessages.restore.get().contains("retained final output"));
        }
    }

    private Pair connect(boolean alreadyExited) throws Exception {
        String runId = UUID.randomUUID().toString();
        Run run = new Run();
        run.exited = alreadyExited;
        terminal.runs.put(runId, run);
        WebTestClient http = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        String header = http.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie = Objects.requireNonNull(header).substring(0, header.indexOf(';'));
        RecordingListener output = new RecordingListener();
        RecordingListener messages = new RecordingListener();
        HttpClient client = HttpClient.newHttpClient();
        WebSocket io = socket(client, cookie, runId, "io", output);
        WebSocket control = socket(client, cookie, runId, "control", messages);
        Pair pair = new Pair(runId, io, control, output, messages, run);
        try {
            messages.restore.get(5, TimeUnit.SECONDS);
            return pair;
        } catch (Exception failure) {
            pair.close();
            throw failure;
        }
    }

    private WebSocket socket(HttpClient client, String cookie, String runId, String channel, WebSocket.Listener listener) {
        return client.newWebSocketBuilder().header("Cookie", cookie).buildAsync(
                URI.create("ws://127.0.0.1:" + port + "/ws/terminal/" + runId + "/" + channel
                        + "?clientId=exit-drain"), listener).join();
    }

    private final class Pair implements AutoCloseable {
        final String runId;
        final WebSocket io, control;
        final RecordingListener output, controlMessages;
        final Run run;
        Pair(String runId, WebSocket io, WebSocket control, RecordingListener output,
             RecordingListener messages, Run run) {
            this.runId = runId; this.io = io; this.control = control;
            this.output = output; this.controlMessages = messages; this.run = run;
        }
        void ack(int bytes) { control.sendText("{\"type\":\"output_ack\",\"bytes\":" + bytes + "}", true).join(); }
        @Override public void close() throws Exception {
            io.abort();
            control.abort();
            try { run.closed.get(5, TimeUnit.SECONDS); }
            finally { terminal.runs.remove(runId); }
        }
    }

    private static final class RecordingListener implements WebSocket.Listener {
        final CompletableFuture<String> restore = new CompletableFuture<>();
        final CompletableFuture<String> exit = new CompletableFuture<>();
        private final StringBuilder received = new StringBuilder();
        private final StringBuilder message = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public synchronized CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            message.append(data);
            if (last) {
                String complete = message.toString();
                received.append(complete);
                message.setLength(0);
                if (complete.contains("\"type\":\"restore\"")) restore.complete(complete);
                if (complete.contains("\"type\":\"exit\"")) exit.complete(complete);
                notifyAll();
            }
            socket.request(1);
            return null;
        }
        synchronized String text() { return received.toString(); }
        synchronized void awaitLength(int length) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (received.length() < length && System.nanoTime() < deadline) wait(25);
            assertEquals(length, received.length());
        }
    }

    static final class Run {
        volatile boolean exited;
        volatile Consumer<String> output;
        final CompletableFuture<Void> closed = new CompletableFuture<>();
    }
    @TestConfiguration static class ExitConfiguration {
        @Bean @Primary ExitTerminal exitTerminal() { return new ExitTerminal(); }
    }
    static final class ExitTerminal implements TerminalChannelUseCase {
        final Map<String, Run> runs = new ConcurrentHashMap<>();
        @Override public TerminalRunStatusView status(String runId) {
            return new TerminalRunStatusView(runs.get(runId).exited ? "exited" : "running", 1);
        }
        @Override public void input(String runId, byte[] bytes) {
            Run run = runs.get(runId);
            String command = new String(bytes, StandardCharsets.UTF_8);
            if (command.equals("large")) run.output.accept(FINAL_OUTPUT);
            else if (command.equals("small")) run.output.accept("!");
            run.exited = true;
        }
        @Override public void resize(String runId, int columns, int rows) { }
        @Override public void stop(String runId) { runs.get(runId).exited = true; }
        @Override public void pauseOutput(String runId) { }
        @Override public void resumeOutput(String runId) { }
        @Override public TerminalOutputSession open(String runId, Consumer<String> output) {
            Run run = runs.get(runId);
            run.output = output;
            return new TerminalOutputSession(run.exited ? "retained final output" : "", () -> {
                run.output = null;
                run.closed.complete(null);
            });
        }
    }
    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("termestra-terminal-exit-ws-").toRealPath(); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
