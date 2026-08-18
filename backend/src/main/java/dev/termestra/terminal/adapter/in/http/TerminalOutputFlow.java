package dev.termestra.terminal.adapter.in.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A bounded, per-viewer output window.
 *
 * <p>The batching queue and the acknowledgement window deliberately have different
 * meanings. Reaching a frame-sized batch means that it is time to send a frame; it
 * does not mean that the browser is slow. A viewer is only rejected when its bounded
 * pending queue overflows or it cannot drain below the low-water mark before the
 * grace period expires.</p>
 */
final class TerminalOutputFlow implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TerminalOutputFlow.class);

    static final int BATCH_INTERVAL_MS = 4;
    static final int LOW_LATENCY_THRESHOLD_BYTES = 256;
    static final int MAX_FRAME_BYTES = 16 * 1024;
    static final long PENDING_HIGH_WATER = 128L * 1024;
    static final long PENDING_LOW_WATER = 32L * 1024;
    static final long PENDING_HARD_LIMIT = 512L * 1024;
    static final long UNACKED_HIGH_WATER = 100L * 1024;
    static final long UNACKED_LOW_WATER = 50L * 1024;
    static final long DEFAULT_ACK_TIMEOUT_MILLIS = 30_000;

    private static final long LOW_LATENCY_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "termestra-terminal-flow");
                thread.setDaemon(true);
                return thread;
            });

    private final Consumer<String> emitter;
    private final Consumer<Boolean> pressure;
    private final Runnable rejection;
    private final Runnable completion;
    private final long acknowledgementTimeoutMillis;
    private final ArrayDeque<OutputBatch> pending = new ArrayDeque<>();

    private ScheduledFuture<?> flushTask;
    private ScheduledFuture<?> acknowledgementTimeoutTask;
    private long acknowledgementTimeoutGeneration;
    private long pendingBytes;
    private long unacknowledged;
    private long lastSent;
    private boolean emitting;
    private boolean backpressured;
    private boolean appliedPressure;
    private boolean desiredPressure;
    private boolean effectsDraining;
    private Runnable terminalEffect;
    private boolean closed;

    TerminalOutputFlow(Consumer<String> emitter, Consumer<Boolean> pressure) {
        this(emitter, pressure, () -> { }, () -> { }, DEFAULT_ACK_TIMEOUT_MILLIS);
    }

    TerminalOutputFlow(Consumer<String> emitter, Consumer<Boolean> pressure,
                       Runnable completion) {
        this(emitter, pressure, () -> { }, completion, DEFAULT_ACK_TIMEOUT_MILLIS);
    }

    TerminalOutputFlow(Consumer<String> emitter, Consumer<Boolean> pressure,
                       Runnable rejection, Runnable completion) {
        this(emitter, pressure, rejection, completion, DEFAULT_ACK_TIMEOUT_MILLIS);
    }

    TerminalOutputFlow(Consumer<String> emitter, Consumer<Boolean> pressure,
                       Runnable rejection, Runnable completion,
                       long acknowledgementTimeoutMillis) {
        if (acknowledgementTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Acknowledgement timeout must be positive");
        }
        this.emitter = emitter;
        this.pressure = pressure;
        this.rejection = rejection;
        this.completion = completion;
        this.acknowledgementTimeoutMillis = acknowledgementTimeoutMillis;
    }

    void enqueue(String text) {
        if (text.isEmpty()) return;
        OutputBatch immediate = null;
        boolean drainEffects;
        synchronized (this) {
            if (closed) return;
            long textBytes = utf8Bytes(text);
            if (textBytes > PENDING_HARD_LIMIT - pendingBytes) {
                closeRejectedLocked();
            } else {
                boolean idle = !emitting && pending.isEmpty() && flushTask == null
                        && System.nanoTime() - lastSent >= LOW_LATENCY_IDLE_NANOS;
                appendPendingLocked(text);
                if (idle && textBytes < LOW_LATENCY_THRESHOLD_BYTES) {
                    immediate = prepareNextLocked();
                } else if (!emitting && pendingBytes >= MAX_FRAME_BYTES) {
                    cancelFlushLocked();
                    immediate = prepareNextLocked();
                } else {
                    scheduleFlushLocked();
                }
                updatePressureLocked();
            }
            drainEffects = claimEffectDrainLocked();
        }
        if (drainEffects) drainEffects();
        if (immediate != null) emitFrom(immediate);
    }

    boolean acknowledge(long bytes) {
        OutputBatch next = null;
        boolean accepted;
        boolean drainEffects;
        synchronized (this) {
            accepted = !closed && bytes >= 0 && bytes <= unacknowledged;
            if (accepted) {
                unacknowledged -= bytes;
                if (!emitting && !pending.isEmpty()) next = prepareNextLocked();
                updatePressureLocked();
                if (next == null) scheduleFlushLocked();
            }
            drainEffects = claimEffectDrainLocked();
        }
        if (drainEffects) drainEffects();
        if (next != null) emitFrom(next);
        return accepted;
    }

    private void flush() {
        OutputBatch batch;
        boolean drainEffects;
        synchronized (this) {
            flushTask = null;
            batch = prepareNextLocked();
            drainEffects = claimEffectDrainLocked();
        }
        if (drainEffects) drainEffects();
        if (batch != null) emitFrom(batch);
    }

    /** User/reactor callbacks are deliberately invoked with no flow monitor held. */
    private void emitFrom(OutputBatch first) {
        OutputBatch batch = first;
        while (batch != null) {
            RuntimeException failure = null;
            try {
                emitter.accept(batch.text());
            } catch (RuntimeException viewerFailure) {
                failure = viewerFailure;
            }

            boolean drainEffects;
            synchronized (this) {
                emitting = false;
                if (!closed) {
                    if (failure != null) {
                        closeRejectedLocked();
                    } else {
                        lastSent = System.nanoTime();
                    }
                }
                batch = closed ? null : prepareNextLocked();
                if (batch == null) scheduleFlushLocked();
                updatePressureLocked();
                drainEffects = claimEffectDrainLocked();
            }
            if (drainEffects) drainEffects();
        }
    }

    private OutputBatch prepareNextLocked() {
        if (closed || emitting || pending.isEmpty()) return null;
        long availableCredit = UNACKED_HIGH_WATER - unacknowledged;
        if (availableCredit <= 0) return null;
        OutputBatch batch = pollBatchLocked(Math.min(MAX_FRAME_BYTES, availableCredit));
        if (batch == null) return null;

        // Reserve the acknowledgement credit before making the frame externally visible.
        // An emitter is allowed to acknowledge synchronously or from another thread.
        unacknowledged += batch.bytes();
        emitting = true;
        updatePressureLocked();
        return batch;
    }

    private OutputBatch pollBatchLocked(long maximumBytes) {
        if (maximumBytes <= 0 || pending.isEmpty()) return null;
        StringBuilder combined = new StringBuilder();
        long combinedBytes = 0;
        while (!pending.isEmpty()) {
            OutputBatch head = pending.getFirst();
            long available = maximumBytes - combinedBytes;
            if (head.bytes() <= available) {
                pending.removeFirst();
                pendingBytes -= head.bytes();
                combined.append(head.text());
                combinedBytes += head.bytes();
                if (combinedBytes == maximumBytes) break;
                continue;
            }
            if (combinedBytes > 0) break;
            SplitBatch split = splitPrefix(head, Math.toIntExact(available));
            if (split.prefix().bytes() == 0) return null;
            pending.removeFirst();
            if (split.remainder() != null) pending.addFirst(split.remainder());
            pendingBytes -= split.prefix().bytes();
            combined.append(split.prefix().text());
            combinedBytes += split.prefix().bytes();
            break;
        }
        return combinedBytes == 0 ? null : new OutputBatch(combined.toString(), combinedBytes);
    }

    private void appendPendingLocked(String text) {
        for (OutputBatch chunk : splitUtf8(text, MAX_FRAME_BYTES)) {
            pending.addLast(chunk);
            pendingBytes += chunk.bytes();
        }
    }

    private void scheduleFlushLocked() {
        if (!closed && !emitting && !pending.isEmpty() && flushTask == null
                && unacknowledged < UNACKED_HIGH_WATER) {
            flushTask = SCHEDULER.schedule(this::flush, BATCH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void cancelFlushLocked() {
        if (flushTask != null) flushTask.cancel(false);
        flushTask = null;
    }

    private void updatePressureLocked() {
        if (closed) return;
        if (!backpressured && (unacknowledged >= UNACKED_HIGH_WATER
                || pendingBytes >= PENDING_HIGH_WATER)) {
            backpressured = true;
            desiredPressure = true;
            scheduleAcknowledgementTimeoutLocked();
            return;
        }
        if (backpressured && unacknowledged <= UNACKED_LOW_WATER
                && pendingBytes <= PENDING_LOW_WATER) {
            backpressured = false;
            cancelAcknowledgementTimeoutLocked();
            desiredPressure = false;
        }
    }

    private void scheduleAcknowledgementTimeoutLocked() {
        cancelAcknowledgementTimeoutLocked();
        long generation = ++acknowledgementTimeoutGeneration;
        acknowledgementTimeoutTask = SCHEDULER.schedule(
                () -> acknowledgementTimedOut(generation),
                acknowledgementTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void acknowledgementTimedOut(long generation) {
        boolean drainEffects;
        synchronized (this) {
            if (!closed && backpressured && generation == acknowledgementTimeoutGeneration) {
                closeRejectedLocked();
            }
            drainEffects = claimEffectDrainLocked();
        }
        if (drainEffects) drainEffects();
    }

    private void cancelAcknowledgementTimeoutLocked() {
        acknowledgementTimeoutGeneration++;
        if (acknowledgementTimeoutTask != null) {
            acknowledgementTimeoutTask.cancel(false);
            acknowledgementTimeoutTask = null;
        }
    }

    private void closeRejectedLocked() {
        if (closed) return;
        closeStateLocked();
        terminalEffect = () -> invokeCallback(rejection,
                "Terminal viewer rejection callback failed");
    }

    private void closeStateLocked() {
        closed = true;
        cancelFlushLocked();
        cancelAcknowledgementTimeoutLocked();
        pending.clear();
        pendingBytes = 0;
        unacknowledged = 0;
        if (backpressured) {
            backpressured = false;
        }
        desiredPressure = false;
    }

    private boolean claimEffectDrainLocked() {
        if (effectsDraining
                || (appliedPressure == desiredPressure && terminalEffect == null)) return false;
        effectsDraining = true;
        return true;
    }

    private void drainEffects() {
        while (true) {
            boolean pressureEffect;
            boolean pressureValue = false;
            Runnable callback = null;
            synchronized (this) {
                pressureEffect = appliedPressure != desiredPressure;
                if (pressureEffect) {
                    pressureValue = desiredPressure;
                } else if (terminalEffect != null) {
                    callback = terminalEffect;
                    terminalEffect = null;
                } else {
                    effectsDraining = false;
                    return;
                }
            }
            if (pressureEffect) {
                invokePressure(pressureValue);
                synchronized (this) {
                    appliedPressure = pressureValue;
                }
            } else {
                callback.run();
            }
        }
    }

    private void invokePressure(boolean value) {
        try {
            pressure.accept(value);
        } catch (RuntimeException failure) {
            LOG.debug("Terminal pressure callback failed", failure);
        }
    }

    private static void invokeCallback(Runnable callback, String message) {
        try {
            callback.run();
        } catch (RuntimeException failure) {
            LOG.debug(message, failure);
        }
    }

    synchronized boolean closed() {
        return closed;
    }

    synchronized long pendingBytes() {
        return pendingBytes;
    }

    synchronized long unacknowledgedBytes() {
        return unacknowledged;
    }

    @Override public void close() {
        boolean drainEffects;
        synchronized (this) {
            if (closed) return;
            closeStateLocked();
            terminalEffect = () -> invokeCallback(completion,
                    "Terminal viewer completion callback failed");
            drainEffects = claimEffectDrainLocked();
        }
        if (drainEffects) drainEffects();
    }

    private static SplitBatch splitPrefix(OutputBatch batch, int maximumBytes) {
        int firstCodePoint = batch.text().codePointAt(0);
        if (utf8CodePointBytes(firstCodePoint) > maximumBytes) {
            return new SplitBatch(new OutputBatch("", 0), batch);
        }
        List<OutputBatch> split = splitUtf8(batch.text(), maximumBytes);
        if (split.isEmpty()) return new SplitBatch(new OutputBatch("", 0), batch);
        OutputBatch prefix = split.getFirst();
        if (split.size() == 1) return new SplitBatch(prefix, null);
        StringBuilder remainderText = new StringBuilder();
        long remainderBytes = 0;
        for (int index = 1; index < split.size(); index++) {
            OutputBatch part = split.get(index);
            remainderText.append(part.text());
            remainderBytes += part.bytes();
        }
        return new SplitBatch(prefix, new OutputBatch(remainderText.toString(), remainderBytes));
    }

    private static List<OutputBatch> splitUtf8(String text, int maximumBytes) {
        if (maximumBytes <= 0 || text.isEmpty()) return List.of();
        List<OutputBatch> result = new ArrayList<>();
        int start = 0;
        int cursor = 0;
        int bytes = 0;
        while (cursor < text.length()) {
            int codePoint = text.codePointAt(cursor);
            int characters = Character.charCount(codePoint);
            int codePointBytes = utf8CodePointBytes(codePoint);
            if (bytes > 0 && bytes + codePointBytes > maximumBytes) {
                result.add(new OutputBatch(text.substring(start, cursor), bytes));
                start = cursor;
                bytes = 0;
            }
            bytes += codePointBytes;
            cursor += characters;
        }
        if (cursor > start) result.add(new OutputBatch(text.substring(start, cursor), bytes));
        return result;
    }

    private static int utf8CodePointBytes(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) {
            return Character.isSurrogate((char) codePoint) ? 1 : 3;
        }
        return 4;
    }

    private static long utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private record OutputBatch(String text, long bytes) { }
    private record SplitBatch(OutputBatch prefix, OutputBatch remainder) { }
}
