package dev.termestra.terminal.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.terminal.application.port.in.TerminalChannelUseCase;
import dev.termestra.terminal.application.port.in.TerminalOutputSession;
import dev.termestra.terminal.application.port.in.TerminalRunStatusView;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerminalWebSocketHandlerLifecycleTest {
    @Test void multipleViewerPressureLeasesPauseOnceAndResumeAfterTheLastRelease() {
        List<String> transitions = new CopyOnWriteArrayList<>();
        TerminalWebSocketHandler.OutputPressureCoordinator pressure =
                new TerminalWebSocketHandler.OutputPressureCoordinator(
                        () -> transitions.add("pause"),
                        () -> transitions.add("resume"),
                        Runnable::run);
        Object firstViewerFlow = new Object();
        Object secondViewerFlow = new Object();

        pressure.pressure(firstViewerFlow, true);
        pressure.pressure(secondViewerFlow, true);
        pressure.pressure(firstViewerFlow, false);

        assertEquals(List.of("pause"), transitions,
                "releasing one viewer must not resume output needed by another viewer");

        pressure.pressure(secondViewerFlow, false);

        assertEquals(List.of("pause", "resume"), transitions);
        pressure.close();
    }

    @Test void staleFlowReleaseCannotResumeAReplacementFlowThatIsStillPressured() throws Exception {
        List<String> transitions = new CopyOnWriteArrayList<>();
        CountDownLatch pauseStarted = new CountDownLatch(1);
        CountDownLatch releasePause = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            TerminalWebSocketHandler.OutputPressureCoordinator pressure =
                    new TerminalWebSocketHandler.OutputPressureCoordinator(() -> {
                        transitions.add("pause");
                        pauseStarted.countDown();
                        await(releasePause);
                    }, () -> transitions.add("resume"), executor);
            Object oldFlow = new Object();
            Object replacementFlow = new Object();

            pressure.pressure(oldFlow, true);
            assertTrue(pauseStarted.await(5, TimeUnit.SECONDS), "pause transition should begin");
            pressure.pressure(replacementFlow, true);
            pressure.pressure(oldFlow, false);
            releasePause.countDown();
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertEquals(List.of("pause"), transitions,
                    "a late release from the old flow must not resume the replacement flow");

            pressure.pressure(replacementFlow, false);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertEquals(List.of("pause", "resume"), transitions);
            pressure.close();
        }
    }

    @Test void cleanupWaitsForAnInFlightPauseAndRestoresTerminalOutput() throws Exception {
        List<String> transitions = new CopyOnWriteArrayList<>();
        CountDownLatch pauseStarted = new CountDownLatch(1);
        CountDownLatch releasePause = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            TerminalWebSocketHandler.OutputPressureCoordinator pressure =
                    new TerminalWebSocketHandler.OutputPressureCoordinator(() -> {
                        transitions.add("pause");
                        pauseStarted.countDown();
                        await(releasePause);
                    }, () -> transitions.add("resume"), executor);

            pressure.pressure(new Object(), true);
            assertTrue(pauseStarted.await(5, TimeUnit.SECONDS), "pause transition should begin");
            CompletableFuture<Void> cleanup = CompletableFuture.runAsync(pressure::close);
            releasePause.countDown();
            cleanup.get(5, TimeUnit.SECONDS);

            assertEquals(List.of("pause", "resume"), transitions,
                    "cleanup must not return while the terminal remains paused");
        }
    }

    @Test void controlViewersOfOneRunShareOneBlockedStatusPoll() throws Exception {
        BlockingStatusTerminal terminal = new BlockingStatusTerminal();
        TerminalWebSocketHandler handler = new TerminalWebSocketHandler(terminal, new ObjectMapper());
        CountDownLatch restored = new CountDownLatch(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Disposable> connections = List.of(
                handler.handle(streamingSession("io", "viewer-1", null)).subscribe(ignored -> { }, failures::add),
                handler.handle(streamingSession("io", "viewer-2", null)).subscribe(ignored -> { }, failures::add),
                handler.handle(streamingSession("control", "viewer-1", restored)).subscribe(ignored -> { }, failures::add),
                handler.handle(streamingSession("control", "viewer-2", restored)).subscribe(ignored -> { }, failures::add));
        try {
            assertTrue(restored.await(5, TimeUnit.SECONDS), "both control channels should restore");
            assertTrue(terminal.firstStatus.await(5, TimeUnit.SECONDS), "status polling should begin");

            Thread.sleep(350);

            assertEquals(1, terminal.statusCalls.get(),
                    "one run must have one in-flight status query regardless of viewer count");
            assertTrue(failures.isEmpty(), () -> "unexpected websocket failure: " + failures);
        } finally {
            terminal.releaseStatus.countDown();
            connections.forEach(Disposable::dispose);
            handler.close();
        }
    }

    @Test void closeWaitsForAnInFlightBindThenClosesItAndRejectsNewBindings() throws Exception {
        BlockingOpenTerminal terminal = new BlockingOpenTerminal();
        TerminalWebSocketHandler handler = new TerminalWebSocketHandler(terminal, new ObjectMapper());
        AtomicReference<Throwable> connectionFailure = new AtomicReference<>();
        Disposable connection = handler.handle(streamingSession("io", "viewer-1", null))
                .subscribe(ignored -> { }, connectionFailure::set);
        assertTrue(terminal.openStarted.await(5, TimeUnit.SECONDS), "terminal open should enter");

        CountDownLatch closeStarted = new CountDownLatch(1);
        CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
            closeStarted.countDown();
            handler.close();
        });
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
        terminal.releaseOpen.countDown();
        close.get(5, TimeUnit.SECONDS);

        assertEquals(1, terminal.subscriptionCloses.get(),
                "a run bound during shutdown must not escape cleanup");
        RuntimeException rejected = assertThrows(RuntimeException.class,
                () -> handler.handle(streamingSession("io", "viewer-2", null))
                        .block(Duration.ofSeconds(5)));
        assertTrue(causedBy(rejected, "Terminal websocket handler is closed"));
        connection.dispose();
    }

    @Test void snapshotCursorSuppressesTheSameOutputFromTheLiveStream() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        TerminalWebSocketHandler.Viewer viewer = new TerminalWebSocketHandler.Viewer();
        Object lease = viewer.reserve(TerminalWebSocketHandler.Channel.IO);
        TerminalOutputFlow flow = new TerminalOutputFlow(delivered::add, ignored -> { });
        viewer.installFlow(lease, flow);
        assertTrue(viewer.startStreaming(7));

        viewer.emit(7, "already-in-snapshot");
        viewer.emit(8, "new-live-output");

        assertEquals(List.of("new-live-output"), delivered);
        viewer.releaseIo(lease, flow);
    }

    @Test void rejectsInitializationThatCannotBeLosslesslyJoinedToTheSnapshot() {
        InitializationOverflowTerminal terminal = new InitializationOverflowTerminal();
        TerminalWebSocketHandler handler = new TerminalWebSocketHandler(terminal, new ObjectMapper());
        try {
            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> handler.handle(streamingSession("io", "viewer-1", null))
                            .block(Duration.ofSeconds(5)));

            assertTrue(causedBy(rejected,
                    "Terminal initialization exceeded its bounded snapshot handoff window"));
            assertEquals(1, terminal.subscriptionCloses.get());
        } finally {
            handler.close();
        }
    }

    private static WebSocketSession streamingSession(String channel, String clientId,
                                                       CountDownLatch restored) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshake = mock(HandshakeInfo.class);
        when(handshake.getUri()).thenReturn(URI.create(
                "ws://127.0.0.1/ws/terminal/run-1/" + channel + "?clientId=" + clientId));
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.receive()).thenReturn(Flux.never());
        when(session.textMessage(any())).thenAnswer(invocation -> message(invocation.getArgument(0)));
        when(session.send(any())).thenAnswer(invocation -> {
            Publisher<WebSocketMessage> messages = invocation.getArgument(0);
            return Flux.from(messages).doOnNext(message -> {
                if (restored != null && message.getPayloadAsText(StandardCharsets.UTF_8)
                        .contains("\"type\":\"restore\"")) restored.countDown();
            }).then();
        });
        return session;
    }

    private static WebSocketMessage message(String value) {
        WebSocketMessage message = mock(WebSocketMessage.class);
        when(message.getPayload()).thenReturn(DefaultDataBufferFactory.sharedInstance.wrap(
                value.getBytes(StandardCharsets.UTF_8)));
        when(message.getPayloadAsText(StandardCharsets.UTF_8)).thenReturn(value);
        return message;
    }

    private static boolean causedBy(Throwable failure, String message) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (message.equals(current.getMessage())) return true;
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release pressure transition");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static class NoOpTerminal implements TerminalChannelUseCase {
        @Override public TerminalRunStatusView status(String runId) {
            return new TerminalRunStatusView("running", null);
        }
        @Override public void input(String runId, byte[] input) { }
        @Override public void resize(String runId, int columns, int rows) { }
        @Override public void stop(String runId) { }
        @Override public void pauseOutput(String runId) { }
        @Override public void resumeOutput(String runId) { }
        @Override public TerminalOutputSession open(String runId,
                                                     java.util.function.Consumer<String> output) {
            return new TerminalOutputSession("", () -> { });
        }
    }

    private static final class BlockingStatusTerminal extends NoOpTerminal {
        private final AtomicInteger statusCalls = new AtomicInteger();
        private final CountDownLatch firstStatus = new CountDownLatch(1);
        private final CountDownLatch releaseStatus = new CountDownLatch(1);

        @Override public TerminalRunStatusView status(String runId) {
            statusCalls.incrementAndGet();
            firstStatus.countDown();
            try {
                if (!releaseStatus.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release status query");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return super.status(runId);
        }
    }

    private static final class BlockingOpenTerminal extends NoOpTerminal {
        private final CountDownLatch openStarted = new CountDownLatch(1);
        private final CountDownLatch releaseOpen = new CountDownLatch(1);
        private final AtomicInteger subscriptionCloses = new AtomicInteger();

        @Override public TerminalOutputSession open(String runId,
                                                     java.util.function.Consumer<String> output) {
            openStarted.countDown();
            try {
                if (!releaseOpen.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release terminal open");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new TerminalOutputSession("", subscriptionCloses::incrementAndGet);
        }
    }

    private static final class InitializationOverflowTerminal extends NoOpTerminal {
        private final AtomicInteger subscriptionCloses = new AtomicInteger();

        @Override public TerminalOutputSession open(String runId,
                                                     java.util.function.Consumer<String> output) {
            output.accept("x".repeat(1024 * 1024 + 1));
            return new TerminalOutputSession("", subscriptionCloses::incrementAndGet);
        }
    }
}
