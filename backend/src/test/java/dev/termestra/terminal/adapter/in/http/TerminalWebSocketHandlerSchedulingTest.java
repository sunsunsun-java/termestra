package dev.termestra.terminal.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.terminal.application.port.in.TerminalChannelUseCase;
import dev.termestra.terminal.application.port.in.TerminalOutputSession;
import dev.termestra.terminal.application.port.in.TerminalRunStatusView;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerminalWebSocketHandlerSchedulingTest {
    @Test void bindingAndOrderedInputRunOutsideTheNettyEventLoop() {
        RecordingTerminal terminal = new RecordingTerminal();
        TerminalWebSocketHandler handler = new TerminalWebSocketHandler(terminal, new ObjectMapper());
        WebSocketSession session = ioSession("first", "second");
        Thread caller = Thread.currentThread();
        String originalName = caller.getName();

        try {
            caller.setName("reactor-http-nio-test");
            handler.handle(session).block(Duration.ofSeconds(5));

            assertEquals(List.of("first", "second"), terminal.inputs);
            assertFalse(terminal.openThread.get().startsWith("reactor-http-nio"));
            assertFalse(terminal.inputThreads.stream()
                    .anyMatch(name -> name.startsWith("reactor-http-nio")));
        } finally {
            caller.setName(originalName);
            handler.close();
        }
    }

    @Test void acceptsExactly256KiBOfInputAndRejectsTheNextByte() {
        RecordingTerminal terminal = new RecordingTerminal();
        TerminalWebSocketHandler handler = new TerminalWebSocketHandler(terminal, new ObjectMapper());
        String atLimit = "x".repeat(256 * 1024);

        try {
            handler.handle(ioSession(atLimit)).block(Duration.ofSeconds(5));
            assertEquals(256 * 1024, terminal.inputs.getFirst().getBytes(StandardCharsets.UTF_8).length);

            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> handler.handle(ioSession(atLimit + "x")).block(Duration.ofSeconds(5)));

            assertTrue(causedBy(rejected, "Terminal input message exceeds 262144 bytes"));
            assertEquals(1, terminal.inputs.size(), "oversized input must not reach the PTY");
        } finally {
            handler.close();
        }
    }

    private static WebSocketSession ioSession(String... payloads) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshake = mock(HandshakeInfo.class);
        when(handshake.getUri()).thenReturn(
                URI.create("ws://127.0.0.1/ws/terminal/run-1/io?clientId=client-1"));
        when(session.getHandshakeInfo()).thenReturn(handshake);
        List<WebSocketMessage> messages = java.util.Arrays.stream(payloads)
                .map(TerminalWebSocketHandlerSchedulingTest::message)
                .toList();
        when(session.receive()).thenReturn(Flux.fromIterable(messages));
        when(session.send(any())).thenReturn(Mono.never());
        return session;
    }

    private static WebSocketMessage message(String value) {
        WebSocketMessage message = mock(WebSocketMessage.class);
        when(message.getPayload()).thenReturn(DefaultDataBufferFactory.sharedInstance.wrap(
                value.getBytes(StandardCharsets.UTF_8)));
        return message;
    }

    private static boolean causedBy(Throwable failure, String message) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (message.equals(current.getMessage())) return true;
        }
        return false;
    }

    private static final class RecordingTerminal implements TerminalChannelUseCase {
        private final AtomicReference<String> openThread = new AtomicReference<>();
        private final List<String> inputThreads = new CopyOnWriteArrayList<>();
        private final List<String> inputs = new CopyOnWriteArrayList<>();

        @Override public TerminalRunStatusView status(String runId) {
            return new TerminalRunStatusView("running", null);
        }
        @Override public void input(String runId, byte[] input) {
            inputThreads.add(Thread.currentThread().getName());
            inputs.add(new String(input, StandardCharsets.UTF_8));
        }
        @Override public void resize(String runId, int columns, int rows) { }
        @Override public void stop(String runId) { }
        @Override public void pauseOutput(String runId) { }
        @Override public void resumeOutput(String runId) { }
        @Override public TerminalOutputSession open(String runId,
                                                     java.util.function.Consumer<String> output) {
            openThread.set(Thread.currentThread().getName());
            return new TerminalOutputSession("", () -> { });
        }
    }
}
