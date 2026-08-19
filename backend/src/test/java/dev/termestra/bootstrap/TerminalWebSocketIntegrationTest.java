package dev.termestra.bootstrap;

import dev.termestra.bootstrap.support.PtyTestFixture;
import dev.termestra.bootstrap.support.TestJavaCommand;
import dev.termestra.terminal.adapter.in.http.TerminalWebSocketConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalWebSocketIntegrationTest {
    private static final Path DATA_DIRECTORY = temp("termestra-terminal-ws-");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA_DIRECTORY::toString);
    }
    @LocalServerPort int port;

    @Test void acceptsBinaryInputStreamsTextOutputAndProcessesControlMessages() throws Exception {
        WebTestClient http = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        String cookie = uiCookie(http);
        Map<?, ?> workspace = http.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("path", temp("termestra-terminal-workspace-").toString(), "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId = Objects.requireNonNull(workspace).get("id").toString();
        Map<?, ?> worker = http.post().uri("/api/workspaces/" + workspaceId + "/workers").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("name", "Terminal Agent", "role", "coder"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workerId = Objects.requireNonNull(worker).get("id").toString();
        TestJavaCommand fixture = TestJavaCommand.rawTerminalFixture(PtyTestFixture.class, "echo");
        http.post().uri("/api/workspaces/" + workspaceId + "/agents/" + workerId + "/config")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of(
                        "command", fixture.command(), "args", fixture.arguments()))
                .exchange().expectStatus().isNoContent();
        Map<?, ?> started = http.post().uri("/api/workspaces/" + workspaceId + "/agents/" + workerId + "/start")
                .header(HttpHeaders.COOKIE, cookie).bodyValue(Map.of()).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String runId = Objects.requireNonNull(started).get("run_id").toString();

        RecordingListener ioListener = new RecordingListener();
        RecordingListener controlListener = new RecordingListener();
        HttpClient client = HttpClient.newHttpClient();
        WebSocket io = connect(client, cookie, runId, "io", ioListener);
        WebSocket control = connect(client, cookie, runId, "control", controlListener);
        controlListener.awaitText("\"type\":\"restore\"");
        RecordingListener duplicateIoListener = new RecordingListener();
        RecordingListener duplicateControlListener = new RecordingListener();
        connect(client, cookie, runId, "io", duplicateIoListener);
        connect(client, cookie, runId, "control", duplicateControlListener);
        duplicateIoListener.awaitClosed();
        duplicateControlListener.awaitClosed();
        io.sendBinary(ByteBuffer.wrap("hello websocket\n".getBytes()), true).join();
        awaitRunOutput(http, cookie, runId, "hello websocket");
        ioListener.awaitText("hello websocket");
        control.sendText("{\"type\":\"output_ack\",\"bytes\":16}", true).join();
        control.sendText("{\"type\":\"output_ack\",\"bytes\":2147483647}", true).join();
        controlListener.awaitText("Invalid terminal control message");
        control.sendText("{\"type\":\"resize\",\"cols\":120,\"rows\":40}", true).join();
        control.sendText("{\"type\":\"unknown\"}",true).join();
        controlListener.awaitText("Invalid terminal control message");
        io.sendBinary(ByteBuffer.wrap("progress 10%\rprogress 90%\033[K\n".getBytes()),true).join();
        ioListener.awaitText("progress 90%");
        RecordingListener restoredListener=new RecordingListener();
        RecordingListener restoredIoListener=new RecordingListener();
        WebSocket restoredIo=connect(client,cookie,runId,"io",restoredIoListener,"restored-viewer");
        WebSocket restored=connect(client,cookie,runId,"control",restoredListener,"restored-viewer");
        restoredListener.awaitText("progress 90%");
        restored.sendClose(WebSocket.NORMAL_CLOSURE,"reconnect").join();
        restoredListener.awaitClosed();
        restoredIoListener.awaitClosed();
        RecordingListener reconnectedIoListener=new RecordingListener();
        RecordingListener reconnectedControlListener=new RecordingListener();
        WebSocket reconnectedIo=connect(client,cookie,runId,"io",reconnectedIoListener,"restored-viewer");
        WebSocket reconnectedControl=connect(client,cookie,runId,"control",reconnectedControlListener,"restored-viewer");
        reconnectedControlListener.awaitText("progress 90%");
        reconnectedIo.sendClose(WebSocket.NORMAL_CLOSURE,"done").join();
        reconnectedControl.sendClose(WebSocket.NORMAL_CLOSURE,"done").join();
        String largeInput = ("x".repeat(512) + "\n").repeat(160) + "large-frame-ok\n";
        io.sendBinary(ByteBuffer.wrap(largeInput.getBytes()), true).join();
        awaitRunOutput(http, cookie, runId, "large-frame-ok");
        RecordingListener oversizedListener = new RecordingListener();
        WebSocket oversized = connect(client, cookie, runId, "io", oversizedListener, "oversized-viewer");
        try {
            oversized.sendBinary(ByteBuffer.wrap(
                    new byte[TerminalWebSocketConfiguration.MAX_WEBSOCKET_FRAME_BYTES + 1]), true).join();
        } catch (CompletionException rejectedDuringWrite) {
            // Reactor Netty may close the transport before the JDK client finishes the oversized frame.
        }
        oversizedListener.awaitClosed();
        control.sendText("{\"type\":\"stop\"}", true).join();
        awaitRunStopped(http, cookie, runId);
        controlListener.awaitText("\"type\":\"exit\"");
        io.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        control.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    private WebSocket connect(HttpClient client, String cookie, String runId, String channel, RecordingListener listener) {
        return connect(client,cookie,runId,channel,listener,"legacy");
    }
    private WebSocket connect(HttpClient client,String cookie,String runId,String channel,RecordingListener listener,String clientId) {
        return client.newWebSocketBuilder().header("Cookie", cookie).connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/terminal/" + runId + "/" + channel+"?clientId="+clientId), listener).join();
    }
    private static void awaitRunOutput(WebTestClient client, String cookie, String runId, String expected) {
        AssertionError last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                client.get().uri("/api/runtime/runs/" + runId).header(HttpHeaders.COOKIE, cookie).exchange()
                        .expectStatus().isOk().expectBody().jsonPath("$.output").value(value -> {
                            if (!value.toString().contains(expected)) throw new AssertionError("PTY did not receive websocket input");
                        });
                return;
            } catch (AssertionError error) {
                last = error;
                try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
        throw Objects.requireNonNull(last);
    }
    private static void awaitRunStopped(WebTestClient client, String cookie, String runId) {
        AssertionError last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                client.get().uri("/api/runtime/runs/" + runId).header(HttpHeaders.COOKIE, cookie)
                        .exchange().expectStatus().isOk().expectBody()
                        .jsonPath("$.status").value(value -> {
                            if (!java.util.Set.of("exited", "error").contains(value.toString())) {
                                throw new AssertionError("PTY was still active after the stop command: " + value);
                            }
                        });
                return;
            } catch (AssertionError error) {
                last = error;
                try { Thread.sleep(50); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        throw Objects.requireNonNull(last);
    }
    private static final class RecordingListener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder binary = new StringBuilder();
        private final Object monitor = new Object();
        private boolean closed;
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (monitor) { text.append(data); monitor.notifyAll(); } webSocket.request(1); return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes);
            synchronized (monitor) { binary.append(new String(bytes)); monitor.notifyAll(); } webSocket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (monitor) { closed = true; monitor.notifyAll(); }
            return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            synchronized (monitor) { closed = true; monitor.notifyAll(); }
        }
        void awaitText(String expected) { await(text, expected); }
        void awaitBinary(String expected) { await(binary, expected); }
        private void await(StringBuilder buffer, String expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (monitor) {
                while (!buffer.toString().contains(expected) && System.nanoTime() < deadline) {
                    try { monitor.wait(50); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                }
                if (!buffer.toString().contains(expected)) throw new AssertionError("Missing websocket message " + expected + " in " + buffer);
            }
        }
        private void awaitClosed() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (monitor) {
                while (!closed && System.nanoTime() < deadline) {
                    try { monitor.wait(50); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                }
                if (!closed) throw new AssertionError("Oversized websocket frame was not rejected");
            }
        }
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
