package dev.termestra.bootstrap;

import dev.termestra.terminal.application.port.in.TerminalChannelUseCase;
import dev.termestra.terminal.application.port.in.TerminalOutputSession;
import dev.termestra.terminal.application.port.in.TerminalRunStatusView;

import org.junit.jupiter.api.Test;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TerminalWebSocketResizeBurstIntegrationTest.ResizeBurstConfiguration.class)
class TerminalWebSocketResizeBurstIntegrationTest {
    private static final Path DATA_DIRECTORY = temp("termestra-terminal-resize-ws-");
    private static final String RUN_ID = "resize-burst-run";
    private static final int RESIZE_PAYLOAD_CHARACTERS = 32 * 1024;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA_DIRECTORY::toString);
    }

    @LocalServerPort int port;

    @Test
    void keepsIoConnectedAcrossAcknowledgedResizeOutputBursts() {
        WebTestClient http = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
        String cookie = uiCookie(http);
        HttpClient client = HttpClient.newHttpClient();
        AcknowledgingOutputListener ioListener = new AcknowledgingOutputListener();
        RecordingListener controlListener = new RecordingListener();
        WebSocket io = connect(client, cookie, "io", ioListener);
        WebSocket control = connect(client, cookie, "control", controlListener);

        try {
            ioListener.acknowledgeThrough(control);
            controlListener.awaitText("\"type\":\"restore\"");
            control.sendText("{\"type\":\"restore_complete\"}", true).join();

            control.sendText("{\"type\":\"resize\",\"cols\":121,\"rows\":41}", true).join();
            ioListener.awaitText("a".repeat(RESIZE_PAYLOAD_CHARACTERS)
                    + "__RESIZE_1_COMPLETE__\n");
            ioListener.awaitAcknowledgements();
            ioListener.assertOpenFor(Duration.ofMillis(200));

            control.sendText("{\"type\":\"resize\",\"cols\":122,\"rows\":42}", true).join();
            ioListener.awaitText("b".repeat(RESIZE_PAYLOAD_CHARACTERS)
                    + "__RESIZE_2_COMPLETE__\n");
            ioListener.awaitAcknowledgements();
            ioListener.assertOpenFor(Duration.ofMillis(200));

            control.sendText("{\"type\":\"resize\",\"cols\":123,\"rows\":43}", true).join();
            ioListener.awaitText("c".repeat(RESIZE_PAYLOAD_CHARACTERS)
                    + "__RESIZE_3_COMPLETE__\n");
            ioListener.awaitAcknowledgements();

            // Four bursts exceed the 100 KiB acknowledgement window. The last marker can
            // only arrive if the real control-channel ACKs replenish output credit.
            control.sendText("{\"type\":\"resize\",\"cols\":124,\"rows\":44}", true).join();
            ioListener.awaitText("d".repeat(RESIZE_PAYLOAD_CHARACTERS)
                    + "__RESIZE_4_COMPLETE__\n");
            ioListener.awaitAcknowledgements();
            ioListener.assertOpenFor(Duration.ofMillis(200));

            assertFalse(io.isInputClosed(), "IO websocket closed after an acknowledged resize burst");
            assertFalse(io.isOutputClosed(), "IO websocket output closed after an acknowledged resize burst");
            assertFalse(control.isInputClosed(), "control websocket closed during resize delivery");
            assertFalse(control.isOutputClosed(), "control websocket output closed during resize delivery");
        } finally {
            close(io);
            close(control);
        }
    }

    private WebSocket connect(HttpClient client, String cookie, String channel,
                              WebSocket.Listener listener) {
        return client.newWebSocketBuilder()
                .header("Cookie", cookie)
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/terminal/"
                        + RUN_ID + "/" + channel + "?clientId=resize-regression"), listener)
                .join();
    }

    private static void close(WebSocket socket) {
        if (socket.isOutputClosed()) return;
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();
        } catch (RuntimeException ignored) {
            socket.abort();
        }
    }

    private static final class AcknowledgingOutputListener implements WebSocket.Listener {
        private final StringBuilder received = new StringBuilder();
        private final StringBuilder currentMessage = new StringBuilder();
        private final Queue<CompletableFuture<WebSocket>> acknowledgements =
                new ConcurrentLinkedQueue<>();
        private final Object monitor = new Object();
        private volatile WebSocket control;
        private boolean closed;
        private Throwable failure;

        void acknowledgeThrough(WebSocket value) {
            control = Objects.requireNonNull(value);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            WebSocket controlSocket;
            String complete = null;
            synchronized (monitor) {
                currentMessage.append(data);
                if (last) {
                    complete = currentMessage.toString();
                    currentMessage.setLength(0);
                    received.append(complete);
                }
                controlSocket = control;
            }
            if (complete != null) {
                if (controlSocket == null) {
                    recordFailure(new AssertionError("Output arrived before the ACK channel was installed"));
                } else {
                    int bytes = complete.getBytes(StandardCharsets.UTF_8).length;
                    acknowledgements.add(controlSocket.sendText(
                            "{\"type\":\"output_ack\",\"bytes\":" + bytes + "}", true));
                }
                synchronized (monitor) {
                    monitor.notifyAll();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            recordFailure(new AssertionError("Terminal output unexpectedly used a binary frame"));
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (monitor) {
                closed = true;
                monitor.notifyAll();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            recordFailure(error);
        }

        void awaitText(String expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (monitor) {
                while (!received.toString().contains(expected) && !closed && failure == null
                        && System.nanoTime() < deadline) {
                    waitForSignal();
                }
                throwIfFailed();
                if (closed) {
                    throw new AssertionError("IO websocket closed before receiving " + expected);
                }
                if (!received.toString().contains(expected)) {
                    throw new AssertionError("Missing resize marker " + expected
                            + " after receiving " + received.length() + " characters");
                }
            }
        }

        void awaitAcknowledgements() {
            CompletableFuture<WebSocket> acknowledgement;
            while ((acknowledgement = acknowledgements.poll()) != null) {
                acknowledgement.orTimeout(5, TimeUnit.SECONDS).join();
            }
        }

        void assertOpenFor(Duration duration) {
            long deadline = System.nanoTime() + duration.toNanos();
            synchronized (monitor) {
                while (!closed && failure == null && System.nanoTime() < deadline) {
                    waitForSignal();
                }
                throwIfFailed();
                if (closed) throw new AssertionError("IO websocket closed after resize output was ACKed");
            }
        }

        private void recordFailure(Throwable error) {
            synchronized (monitor) {
                failure = error;
                closed = true;
                monitor.notifyAll();
            }
        }

        private void waitForSignal() {
            try {
                monitor.wait(25);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }

        private void throwIfFailed() {
            if (failure != null) throw new AssertionError("IO websocket failed", failure);
        }
    }

    private static final class RecordingListener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private final Object monitor = new Object();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (monitor) {
                text.append(data);
                monitor.notifyAll();
            }
            webSocket.request(1);
            return null;
        }

        void awaitText(String expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (monitor) {
                while (!text.toString().contains(expected) && System.nanoTime() < deadline) {
                    try {
                        monitor.wait(25);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }
                if (!text.toString().contains(expected)) {
                    throw new AssertionError("Missing websocket control message " + expected
                            + " in " + text);
                }
            }
        }
    }

    @TestConfiguration
    static class ResizeBurstConfiguration {
        @Bean
        @Primary
        ResizeBurstTerminalChannel resizeBurstTerminalChannel() {
            return new ResizeBurstTerminalChannel();
        }
    }

    static final class ResizeBurstTerminalChannel implements TerminalChannelUseCase {
        private Consumer<String> output;
        private int resizeCount;

        @Override
        public TerminalRunStatusView status(String runId) {
            return new TerminalRunStatusView("running", null);
        }

        @Override
        public void input(String runId, byte[] input) { }

        @Override
        public void resize(String runId, int columns, int rows) {
            Consumer<String> target;
            int generation;
            synchronized (this) {
                target = Objects.requireNonNull(output, "Terminal output was not opened");
                generation = ++resizeCount;
            }
            String redraw = String.valueOf((char) ('a' + generation - 1)).repeat(8 * 1024);
            for (int chunk = 0; chunk < 4; chunk++) target.accept(redraw);
            target.accept("__RESIZE_" + generation + "_COMPLETE__\n");
        }

        @Override
        public void stop(String runId) { }

        @Override
        public void pauseOutput(String runId) { }

        @Override
        public void resumeOutput(String runId) { }

        @Override
        public synchronized TerminalOutputSession open(String runId, Consumer<String> listener) {
            if (output != null) throw new IllegalStateException("Terminal output was already opened");
            output = listener;
            return new TerminalOutputSession("", () -> clear(listener));
        }

        private synchronized void clear(Consumer<String> expected) {
            if (output == expected) output = null;
        }
    }

    private static String uiCookie(WebTestClient client) {
        String header = client.get().uri("/api/ui/session")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseHeaders()
                .getFirst(HttpHeaders.SET_COOKIE);
        return Objects.requireNonNull(header).substring(0, header.indexOf(';'));
    }

    private static Path temp(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
