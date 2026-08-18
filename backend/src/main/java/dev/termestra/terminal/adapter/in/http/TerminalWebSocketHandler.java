package dev.termestra.terminal.adapter.in.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.termestra.terminal.application.port.in.*;
import dev.termestra.terminal.application.service.HeadlessTerminalMirror;
import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.execution.application.exception.RunNotFound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.socket.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class TerminalWebSocketHandler implements WebSocketHandler, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TerminalWebSocketHandler.class);
    private static final Duration IO_READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STATUS_POLL_INTERVAL = Duration.ofMillis(200);
    private static final int MAX_COLUMNS = 400;
    private static final int MAX_ROWS = 150;
    private static final int MAX_TRACKED_RUNS = 64;
    private static final int MAX_VIEWERS_PER_RUN = 8;
    private static final int MAX_CONNECTIONS_PER_RUN = MAX_VIEWERS_PER_RUN * 2;
    private static final int MAX_CLIENT_ID_CHARACTERS = 128;
    private static final int MAX_CONTROL_MESSAGE_BYTES = 16 * 1024;
    private static final int MAX_IO_MESSAGE_BYTES = 256 * 1024;
    private static final int MAX_PENDING_PROTOCOL_MESSAGES = 16;
    private static final int MAX_PENDING_INITIALIZATION_BYTES = 1024 * 1024;
    private static final int MAX_PENDING_INITIALIZATION_MESSAGES = 256;

    private final TerminalChannelUseCase terminal;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();
    private boolean closed;

    public TerminalWebSocketHandler(TerminalChannelUseCase terminal, ObjectMapper json) {
        this.terminal = terminal;
        this.json = json;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String[] parts = session.getHandshakeInfo().getUri().getPath().split("/");
        if (parts.length != 5) return session.close(CloseStatus.NOT_ACCEPTABLE);
        String runId = parts[3];
        String clientId = query(session, "clientId", "legacy");
        if (runId.length() > 256 || clientId.isBlank()
                || clientId.length() > MAX_CLIENT_ID_CHARACTERS) {
            return session.close(CloseStatus.NOT_ACCEPTABLE);
        }
        int columns = boundedPositive(query(session, "cols", null), 80, MAX_COLUMNS);
        int rows = boundedPositive(query(session, "rows", null), 24, MAX_ROWS);
        if ("io".equals(parts[4])) return Mono.defer(
                () -> io(session, runId, clientId, columns, rows))
                .subscribeOn(Schedulers.boundedElastic());
        if ("control".equals(parts[4])) return Mono.defer(
                () -> control(session, runId, clientId, columns, rows))
                .subscribeOn(Schedulers.boundedElastic());
        return session.close(CloseStatus.NOT_ACCEPTABLE);
    }

    private Mono<Void> io(WebSocketSession session, String runId, String clientId, int columns, int rows) {
        RunBinding binding = bind(runId, clientId, columns, rows, Channel.IO);
        RunState state = binding.state();
        Viewer viewer = binding.viewer();
        AtomicReference<TerminalOutputFlow> installed = new AtomicReference<>();
        Flux<WebSocketMessage> output = Flux.<String>create(sink -> {
            TerminalOutputFlow flow = new TerminalOutputFlow(sink::next, pressured -> {
                TerminalOutputFlow current = installed.get();
                if (current != null) state.pressure(current, pressured);
            }, () -> sink.error(new SlowTerminalViewerException()), sink::complete);
            installed.set(flow);
            viewer.installFlow(binding.lease(), flow);
            sink.onDispose(() -> viewer.releaseIo(binding.lease(), flow));
        }, FluxSink.OverflowStrategy.ERROR).map(session::textMessage);
        Mono<Void> input = session.receive().map(message -> {
            DataBuffer payload = message.getPayload();
            if (payload.readableByteCount() > MAX_IO_MESSAGE_BYTES) {
                throw new ExecutionConflict("Terminal input message exceeds "
                        + MAX_IO_MESSAGE_BYTES + " bytes");
            }
            byte[] bytes = new byte[payload.readableByteCount()];
            payload.read(bytes);
            return bytes;
        }).concatMap(bytes -> Mono.fromRunnable(() -> terminal.input(runId, bytes))
                .subscribeOn(Schedulers.boundedElastic()), 1).then();
        return Mono.usingWhen(Mono.just(binding),
                ignored -> Mono.firstWithSignal(session.send(output), input),
                ignored -> releaseIo(runId, state, clientId, binding.lease(), installed.get()),
                (ignored, failure) -> releaseIo(runId, state, clientId,
                        binding.lease(), installed.get()),
                ignored -> releaseIo(runId, state, clientId, binding.lease(), installed.get()));
    }

    private Mono<Void> control(WebSocketSession session, String runId, String clientId, int columns, int rows) {
        RunBinding binding = bind(runId, clientId, columns, rows, Channel.CONTROL);
        RunState state = binding.state();
        Viewer viewer = binding.viewer();
        Sinks.Many<WebSocketMessage> protocol = Sinks.many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(MAX_PENDING_PROTOCOL_MESSAGES));
        Flux<WebSocketMessage> lifecycle = state.terminalExit()
                .map(run -> text(session, new ExitMessage("exit", run.exitCode())))
                .take(1);
        Mono<WebSocketMessage> restore = waitForIo(viewer)
                .then(Mono.fromCallable(() -> text(session,
                        Map.of("type", "restore", "snapshot", state.snapshotAndStart(viewer))))
                        .subscribeOn(Schedulers.boundedElastic()));
        Flux<WebSocketMessage> messages = Flux.concat(restore, Flux.merge(lifecycle, protocol.asFlux()));
        Mono<Void> commands = session.receive().map(message -> {
            if (message.getPayload().readableByteCount() > MAX_CONTROL_MESSAGE_BYTES) {
                throw new InvalidTerminalControlMessage();
            }
            return message.getPayloadAsText(StandardCharsets.UTF_8);
        }).concatMap(payload -> Mono.fromRunnable(
                () -> processControlMessage(session, protocol, runId, state, viewer, payload))
                .subscribeOn(Schedulers.boundedElastic()), 1).then();
        return Mono.usingWhen(Mono.just(binding),
                ignored -> Mono.firstWithSignal(session.send(messages), commands),
                ignored -> releaseControl(runId, state, clientId, binding.lease(), protocol),
                (ignored, failure) -> releaseControl(runId, state, clientId,
                        binding.lease(), protocol),
                ignored -> releaseControl(runId, state, clientId, binding.lease(), protocol));
    }

    private void processControlMessage(WebSocketSession session,
                                       Sinks.Many<WebSocketMessage> protocol,
                                       String runId, RunState state, Viewer viewer, String payload) {
        try {
            controlMessage(runId, state, viewer, payload);
        } catch (InvalidTerminalControlMessage error) {
            emitProtocol(protocol, text(session,
                    Map.of("type", "error", "message", "Invalid terminal control message")));
        } catch (ExecutionConflict | RunNotFound error) {
            emitProtocol(protocol, text(session, Map.of("type", "error",
                    "code", "operation_rejected", "message", error.getMessage())));
        }
    }

    private Mono<Void> waitForIo(Viewer viewer) {
        return Flux.interval(Duration.ZERO, Duration.ofMillis(10))
                .filter(ignored -> viewer.hasFlow())
                .next()
                .timeout(IO_READY_TIMEOUT)
                .then();
    }

    private void controlMessage(String runId, RunState state, Viewer viewer, String payload) {
        try {
            JsonNode node = json.readTree(payload);
            String type = node.path("type").asText();
            switch (type) {
                case "resize" -> {
                    int columns = boundedRequiredInteger(node, "cols", MAX_COLUMNS);
                    int rows = boundedRequiredInteger(node, "rows", MAX_ROWS);
                    state.resize(columns, rows);
                    terminal.resize(runId, columns, rows);
                }
                case "stop" -> terminal.stop(runId);
                case "output_ack" -> {
                    int bytes = requiredInteger(node, "bytes");
                    if (bytes < 0) throw new InvalidTerminalControlMessage();
                    TerminalOutputFlow flow = viewer.flow();
                    if (flow == null || !flow.acknowledge(bytes)) {
                        throw new InvalidTerminalControlMessage();
                    }
                }
                case "restore_complete" -> { }
                default -> throw new InvalidTerminalControlMessage();
            }
        } catch (JsonProcessingException error) {
            throw new InvalidTerminalControlMessage(error);
        }
    }

    private RunBinding bind(String runId, String clientId, int columns, int rows,
                            Channel channel) {
        synchronized (runs) {
            if (closed) throw new IllegalStateException("Terminal websocket handler is closed");
            RunState existing = runs.get(runId);
            if (existing != null) {
                if (existing.connections >= MAX_CONNECTIONS_PER_RUN) {
                    throw new IllegalStateException("Terminal connection capacity exceeded");
                }
                existing.resize(columns, rows);
                Viewer viewer=existing.viewer(clientId);
                Object lease=viewer.reserve(channel);
                existing.connections++;
                return new RunBinding(existing,viewer,lease);
            }
            if (runs.size() >= MAX_TRACKED_RUNS) {
                throw new IllegalStateException("Terminal run capacity exceeded");
            }
            RunState created = new RunState(runId, new HeadlessTerminalMirror(columns, rows));
            try {
                TerminalOutputSession session = terminal.open(runId, created::output);
                created.initialize(session.snapshot(), session.subscription());
                Viewer viewer=created.viewer(clientId);
                Object lease=viewer.reserve(channel);
                runs.put(runId, created);
                created.connections++;
                return new RunBinding(created,viewer,lease);
            } catch (RuntimeException error) {
                created.close();
                throw error;
            }
        }
    }

    private Mono<Void> releaseIo(String runId, RunState state, String clientId,
                                 Object lease, TerminalOutputFlow flow) {
        return Mono.fromRunnable(() -> {
            state.viewers.getOrDefault(clientId, Viewer.CLOSED).releaseIo(lease, flow);
            cleanup(runId, state, clientId);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> releaseControl(String runId, RunState state, String clientId,
                                      Object lease, Sinks.Many<WebSocketMessage> protocol) {
        return Mono.fromRunnable(() -> {
            protocol.tryEmitComplete();
            state.viewers.getOrDefault(clientId, Viewer.CLOSED).releaseControl(lease);
            cleanup(runId, state, clientId);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void cleanup(String runId, RunState state, String clientId) {
        boolean close = false;
        synchronized(runs){
            Viewer viewer = state.viewers.get(clientId);
            if (viewer != null && viewer.idle()) state.viewers.remove(clientId, viewer);
            state.connections=Math.max(0,state.connections-1);
            if(state.connections!=0)return;
            close = runs.remove(runId,state);
        }
        if (close) state.close();
    }

    private static void emitProtocol(Sinks.Many<WebSocketMessage> protocol,
                                     WebSocketMessage message) {
        Sinks.EmitResult result = protocol.tryEmitNext(message);
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_TERMINATED
                && result != Sinks.EmitResult.FAIL_CANCELLED) {
            throw new SlowTerminalViewerException();
        }
    }

    private static int requiredInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new InvalidTerminalControlMessage();
        }
        return value.intValue();
    }

    private static int boundedRequiredInteger(JsonNode node, String field, int maximum) {
        int value = requiredInteger(node, field);
        if (value <= 0) throw new InvalidTerminalControlMessage();
        return Math.min(value, maximum);
    }

    private WebSocketMessage text(WebSocketSession session, Object value) {
        try {
            return session.textMessage(json.writeValueAsString(value));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize terminal message", error);
        }
    }

    private static String query(WebSocketSession session, String key, String fallback) {
        String raw = session.getHandshakeInfo().getUri().getRawQuery();
        if (raw == null) return fallback;
        for (String part : raw.split("&", -1)) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key)) {
                return pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return fallback;
    }

    private static int boundedPositive(String value, int fallback, int maximum) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? Math.min(parsed, maximum) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    public void close() {
        List<RunState> states;
        synchronized (runs) {
            if (closed) return;
            closed = true;
            states = List.copyOf(runs.values());
            runs.clear();
        }
        for (RunState state : states) state.close();
    }

    private record ExitMessage(String type, Integer code) { }
    enum Channel { IO, CONTROL }
    private record RunBinding(RunState state,Viewer viewer,Object lease) { }

    private final class RunState implements AutoCloseable {
        final String runId;
        final HeadlessTerminalMirror mirror;
        final ConcurrentHashMap<String, Viewer> viewers = new ConcurrentHashMap<>();
        private final Sinks.Empty<Void> closedSignal = Sinks.empty();
        private final Flux<TerminalRunStatusView> terminalExit;
        private final List<String> pendingInitialization = new ArrayList<>();
        private final Object outputDeliveryGate = new Object();
        private final OutputPressureCoordinator outputPressure;
        private int pendingInitializationBytes;
        private long outputSequence;
        private boolean initializationOverflow;
        private TerminalSubscription subscription;
        private boolean initialized;
        private boolean closed;
        private int connections;

        RunState(String runId, HeadlessTerminalMirror mirror) {
            this.runId = runId;
            this.mirror = mirror;
            outputPressure = new OutputPressureCoordinator(
                    () -> terminal.pauseOutput(runId),
                    () -> terminal.resumeOutput(runId),
                    task -> Thread.ofVirtual()
                            .name("termestra-terminal-pressure-" + runId)
                            .start(task));
            terminalExit = Flux.defer(() -> Mono.fromCallable(() -> terminal.status(runId))
                            .subscribeOn(Schedulers.boundedElastic()))
                    // Delay after each completed probe instead of feeding a timer into a
                    // queue. A slow status source therefore cannot accumulate stale ticks.
                    .repeatWhen(completed -> completed.delayElements(STATUS_POLL_INTERVAL))
                    .takeUntilOther(closedSignal.asMono().thenReturn(Boolean.TRUE))
                    .distinctUntilChanged(TerminalRunStatusView::status)
                    .filter(run -> !run.active())
                    .take(1)
                    .replay(1)
                    .refCount(1);
        }

        Flux<TerminalRunStatusView> terminalExit() { return terminalExit; }

        void pressure(TerminalOutputFlow flow, boolean pressured) {
            outputPressure.pressure(flow, pressured);
        }

        Viewer viewer(String id) {
            Viewer existing = viewers.get(id);
            if (existing != null) return existing;
            synchronized (viewers) {
                existing = viewers.get(id);
                if (existing != null) return existing;
                if (viewers.size() >= MAX_VIEWERS_PER_RUN) {
                    throw new IllegalStateException("Terminal viewer capacity exceeded");
                }
                Viewer created = new Viewer();
                viewers.put(id, created);
                return created;
            }
        }

        void initialize(String snapshot, TerminalSubscription value) {
            synchronized (outputDeliveryGate) {
                synchronized (this) {
                    if (closed || initializationOverflow) {
                        try { value.close(); }
                        finally {
                            pendingInitialization.clear();
                            pendingInitializationBytes = 0;
                        }
                        throw new TerminalInitializationOverflowException();
                    }
                    subscription = value;
                    mirror.write(snapshot);
                    for (String text : pendingInitialization) mirror.write(text);
                    pendingInitialization.clear();
                    pendingInitializationBytes = 0;
                    initialized = true;
                }
            }
        }

        synchronized String snapshotAndStart(Viewer viewer) {
            if (closed) throw new IllegalStateException("Terminal run is closed");
            if (!viewer.startStreaming(outputSequence)) throw new IllegalStateException("Terminal IO channel is not connected");
            return mirror.snapshot();
        }

        void output(String text) {
            synchronized (outputDeliveryGate) {
                List<Viewer> targets;
                long sequence;
                synchronized (this) {
                    if (closed) return;
                    sequence=++outputSequence;
                    if (!initialized) {
                        int bytes=text.getBytes(StandardCharsets.UTF_8).length;
                        if(initializationOverflow
                                ||pendingInitialization.size()>=MAX_PENDING_INITIALIZATION_MESSAGES
                                ||bytes>MAX_PENDING_INITIALIZATION_BYTES-pendingInitializationBytes){
                            initializationOverflow=true;
                            pendingInitialization.clear();
                            pendingInitializationBytes=0;
                            return;
                        }
                        pendingInitialization.add(text);
                        pendingInitializationBytes+=bytes;
                        return;
                    }
                    mirror.write(text);
                    targets = List.copyOf(viewers.values());
                }
                // Delivery remains ordered, but callbacks run without the RunState monitor.
                // A concurrent snapshot records this sequence as its cursor and the Viewer
                // suppresses the now-duplicate live publication.
                for (Viewer viewer : targets) viewer.emit(sequence,text);
            }
        }

        synchronized void resize(int columns, int rows) { mirror.resize(columns, rows); }
        @Override
        public void close() {
            TerminalSubscription value;
            List<Viewer> current;
            synchronized (this) {
                if (closed) return;
                closed = true;
                value = subscription;
                subscription = null;
                pendingInitialization.clear();
                pendingInitializationBytes = 0;
                initializationOverflow = false;
                current = List.copyOf(viewers.values());
                viewers.clear();
            }
            closedSignal.tryEmitEmpty();
            try {
                if (value != null) value.close();
                for (Viewer viewer : current) viewer.close();
            } finally {
                outputPressure.close();
            }
        }
    }

    /**
     * Coalesces per-viewer pressure leases into one serial pause state for a run.
     *
     * <p>The callback that records pressure can run while terminal output is being delivered.
     * It therefore only changes the desired state and schedules a drain. The external terminal
     * calls run later, with neither a {@link Viewer} nor {@link RunState} monitor held.</p>
     */
    static final class OutputPressureCoordinator implements AutoCloseable {
        private static final long CLOSE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

        private final Runnable pause;
        private final Runnable resume;
        private final Executor executor;
        private final Object gate = new Object();
        private final Set<Object> leases = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean appliedPaused;
        private boolean draining;
        private boolean closed;

        OutputPressureCoordinator(Runnable pause, Runnable resume, Executor executor) {
            this.pause = Objects.requireNonNull(pause, "pause");
            this.resume = Objects.requireNonNull(resume, "resume");
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        void pressure(Object flow, boolean pressured) {
            Objects.requireNonNull(flow, "flow");
            boolean schedule;
            synchronized (gate) {
                if (closed) return;
                boolean changed = pressured ? leases.add(flow) : leases.remove(flow);
                if (!changed) return;
                schedule = startDrainLocked();
            }
            if (schedule) scheduleDrain();
        }

        private boolean startDrainLocked() {
            boolean desiredPaused = !closed && !leases.isEmpty();
            if (draining || desiredPaused == appliedPaused) return false;
            draining = true;
            return true;
        }

        private void scheduleDrain() {
            try {
                executor.execute(this::drain);
            } catch (RuntimeException failure) {
                synchronized (gate) {
                    draining = false;
                    gate.notifyAll();
                }
                throw failure;
            }
        }

        private void drain() {
            while (true) {
                boolean targetPaused;
                synchronized (gate) {
                    targetPaused = !closed && !leases.isEmpty();
                    if (targetPaused == appliedPaused) {
                        draining = false;
                        gate.notifyAll();
                        return;
                    }
                }

                try {
                    if (targetPaused) pause.run();
                    else resume.run();
                } catch (RuntimeException failure) {
                    synchronized (gate) {
                        draining = false;
                        gate.notifyAll();
                    }
                    LOG.warn("Terminal output pressure transition failed", failure);
                    return;
                }

                synchronized (gate) {
                    appliedPaused = targetPaused;
                }
            }
        }

        @Override public void close() {
            boolean schedule;
            synchronized (gate) {
                if (!closed) {
                    closed = true;
                    leases.clear();
                }
                schedule = startDrainLocked();
            }
            if (schedule) scheduleDrain();
            awaitDrain();
        }

        private void awaitDrain() {
            boolean interrupted = false;
            long deadline = System.nanoTime() + CLOSE_WAIT_NANOS;
            synchronized (gate) {
                while (draining) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        LOG.warn("Timed out restoring terminal output during websocket cleanup");
                        break;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(gate, remaining);
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
                if (!draining && appliedPaused) {
                    LOG.warn("Terminal output remained paused after websocket cleanup");
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static final class Viewer {
        private static final Viewer CLOSED = new Viewer();
        private TerminalOutputFlow flow;
        private Object ioLease;
        private Object controlLease;
        private boolean streaming;
        private long deliveredSequence;

        synchronized Object reserve(Channel channel) {
            Object next = new Object();
            if (channel == Channel.IO) {
                if (ioLease != null) throw new DuplicateTerminalChannelException("io");
                ioLease = next;
            } else {
                if (controlLease != null) throw new DuplicateTerminalChannelException("control");
                controlLease = next;
                streaming = false;
            }
            return next;
        }

        synchronized void installFlow(Object lease, TerminalOutputFlow next) {
            if (ioLease != lease || flow != null) {
                throw new DuplicateTerminalChannelException("io");
            }
            flow = next;
        }

        void releaseIo(Object lease, TerminalOutputFlow expected) {
            synchronized (this) {
                if (ioLease != lease) return;
                ioLease = null;
                if (flow == expected) flow = null;
                streaming = false;
            }
            if (expected != null) expected.close();
        }

        void releaseControl(Object expected) {
            TerminalOutputFlow current;
            synchronized (this) {
                if (controlLease != expected) return;
                controlLease = null;
                streaming = false;
                current = flow;
                flow = null;
            }
            if (current != null) current.close();
        }

        synchronized boolean startStreaming(long afterSequence) {
            if (flow == null) return false;
            deliveredSequence = afterSequence;
            streaming = true;
            return true;
        }

        void emit(long sequence,String text) {
            TerminalOutputFlow current;
            synchronized (this) {
                if(!streaming||flow==null||sequence<=deliveredSequence)return;
                deliveredSequence=sequence;
                current=flow;
            }
            if (current != null) current.enqueue(text);
        }

        synchronized boolean hasFlow() { return flow != null; }
        synchronized TerminalOutputFlow flow() { return flow; }
        synchronized boolean idle() { return ioLease == null && controlLease == null; }

        void close() {
            TerminalOutputFlow current;
            synchronized (this) {
                controlLease = null;
                ioLease = null;
                streaming = false;
                current = flow;
                flow = null;
            }
            if (current != null) current.close();
        }
    }

    private static final class DuplicateTerminalChannelException extends IllegalStateException {
        private DuplicateTerminalChannelException(String channel) {
            super("Terminal " + channel + " channel is already connected for this client");
        }
    }

    private static final class InvalidTerminalControlMessage extends IllegalArgumentException {
        private InvalidTerminalControlMessage() { super("Invalid terminal control message"); }
        private InvalidTerminalControlMessage(Throwable cause) {
            super("Invalid terminal control message", cause);
        }
    }

    private static final class SlowTerminalViewerException extends IllegalStateException {
        private SlowTerminalViewerException() {
            super("Terminal viewer exceeded its bounded output window");
        }
    }

    private static final class TerminalInitializationOverflowException extends IllegalStateException {
        private TerminalInitializationOverflowException() {
            super("Terminal initialization exceeded its bounded snapshot handoff window");
        }
    }
}
