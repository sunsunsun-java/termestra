package dev.termestra.terminal.adapter.in.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TerminalOutputFlowTest {
    @Test void normalResizeBurstQueuedDuringEmissionIsFlushedInsteadOfRejectedAsSlow()
            throws Exception {
        List<String> emitted = new CopyOnWriteArrayList<>();
        List<Boolean> pressure = new CopyOnWriteArrayList<>();
        CountDownLatch firstEmissionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstEmission = new CountDownLatch(1);
        String redraw = "r".repeat(32 * 1024);
        TerminalOutputFlow flow = new TerminalOutputFlow(text -> {
            emitted.add(text);
            if (!"seed".equals(text)) return;
            firstEmissionStarted.countDown();
            try {
                if (!releaseFirstEmission.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release first emission");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }, pressure::add);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        boolean closedDuringBurst;
        boolean pressuredDuringBurst;
        try {
            Future<?> first = executor.submit(() -> flow.enqueue("seed"));
            assertTrue(firstEmissionStarted.await(5, TimeUnit.SECONDS));

            flow.enqueue(redraw);
            closedDuringBurst = flow.closed();
            pressuredDuringBurst = !pressure.isEmpty();
            releaseFirstEmission.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstEmission.countDown();
            executor.shutdownNow();
        }

        assertFalse(closedDuringBurst,
                "one normal full-screen redraw must not be mistaken for a slow viewer");
        assertFalse(pressuredDuringBurst);
        awaitText(emitted, "seed" + redraw);
        assertEquals("seed" + redraw, String.join("", emitted));
        assertTrue(pressure.isEmpty());
    }

    @Test void anImmediateAcknowledgementCanObserveTheBatchBeforeTheEmitterReturns() {
        AtomicReference<TerminalOutputFlow> current = new AtomicReference<>();
        AtomicReference<Boolean> acknowledged = new AtomicReference<>();
        List<Boolean> pressure = new CopyOnWriteArrayList<>();
        TerminalOutputFlow flow = new TerminalOutputFlow(text -> acknowledged.set(
                current.get().acknowledge(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)),
                pressure::add);
        current.set(flow);

        flow.enqueue("你好");

        assertEquals(Boolean.TRUE, acknowledged.get(),
                "the outstanding byte window must exist before output becomes observable");
        assertTrue(pressure.isEmpty());
        assertFalse(flow.closed());
    }

    @Test void highWaterPausesAndOnlyRejectsAViewerAfterItsAcknowledgementGraceExpires() {
        List<String> emitted = new CopyOnWriteArrayList<>();
        List<Boolean> pressure = new CopyOnWriteArrayList<>();
        AtomicInteger rejections = new AtomicInteger();
        TerminalOutputFlow flow = new TerminalOutputFlow(emitted::add, pressure::add,
                rejections::incrementAndGet, () -> { }, 50);

        flow.enqueue("x".repeat(Math.toIntExact(TerminalOutputFlow.UNACKED_HIGH_WATER)));
        awaitCondition(() -> pressure.equals(List.of(true)), "viewer never entered pressure");

        assertEquals(List.of(true), pressure);
        assertFalse(flow.closed(), "a full window must get an opportunity to acknowledge");
        assertEquals(TerminalOutputFlow.UNACKED_HIGH_WATER,
                emitted.stream().mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length).sum());

        awaitCondition(flow::closed, "viewer with no acknowledgement was not rejected");
        awaitCondition(() -> pressure.equals(List.of(true, false)) && rejections.get() == 1,
                "viewer rejection callbacks did not finish after the flow closed");
        assertEquals(List.of(true, false), pressure);
        assertEquals(1, rejections.get());
    }

    @Test void acknowledgementDribblingCannotExtendThePressureDeadlineForever()
            throws Exception {
        List<Boolean> pressure = new CopyOnWriteArrayList<>();
        AtomicInteger rejections = new AtomicInteger();
        AtomicInteger acknowledgements = new AtomicInteger();
        AtomicBoolean keepDribbling = new AtomicBoolean(true);
        TerminalOutputFlow flow = new TerminalOutputFlow(ignored -> { }, pressure::add,
                rejections::incrementAndGet, () -> { }, 500);
        flow.enqueue("x".repeat(Math.toIntExact(TerminalOutputFlow.UNACKED_HIGH_WATER)));
        awaitCondition(() -> pressure.equals(List.of(true)), "viewer never entered pressure");

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> dribbler = executor.submit(() -> {
            while (keepDribbling.get()) {
                if (!flow.acknowledge(1)) return;
                acknowledgements.incrementAndGet();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        try {
            awaitCondition(flow::closed,
                    "a client that dribbles ACKs must not extend one pressure deadline forever");
        } finally {
            keepDribbling.set(false);
            dribbler.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
        }

        assertTrue(acknowledgements.get() >= 10,
                "the client must still be making acknowledgement progress at the deadline");
        assertEquals(List.of(true, false), pressure);
        assertEquals(1, rejections.get());
    }

    @Test void closeReleasesPressureForDisconnectedViewer() {
        List<Boolean> pressure = new CopyOnWriteArrayList<>();
        TerminalOutputFlow flow = new TerminalOutputFlow(ignored -> {}, pressure::add);
        flow.enqueue("x".repeat(Math.toIntExact(TerminalOutputFlow.UNACKED_HIGH_WATER)));
        awaitCondition(() -> pressure.equals(List.of(true)), "viewer never entered pressure");

        flow.close();

        assertEquals(List.of(true, false), pressure);
    }

    @Test void acknowledgementAtTheLowWaterResumesWithoutClosingTheViewer() {
        List<Boolean> pressure = new CopyOnWriteArrayList<>();
        TerminalOutputFlow flow = new TerminalOutputFlow(ignored -> { }, pressure::add);
        flow.enqueue("x".repeat(Math.toIntExact(TerminalOutputFlow.UNACKED_HIGH_WATER)));
        awaitCondition(() -> pressure.equals(List.of(true)), "viewer never entered pressure");

        assertTrue(flow.acknowledge(TerminalOutputFlow.UNACKED_HIGH_WATER
                - TerminalOutputFlow.UNACKED_LOW_WATER));

        awaitCondition(() -> pressure.equals(List.of(true, false)),
                "viewer did not leave pressure at the low water mark");
        assertFalse(flow.closed());
    }

    @Test void pendingOutputRemainsBoundedWhenAnEmitterStopsMakingProgress() throws Exception {
        CountDownLatch emissionStarted = new CountDownLatch(1);
        CountDownLatch releaseEmission = new CountDownLatch(1);
        AtomicInteger rejections = new AtomicInteger();
        TerminalOutputFlow flow = new TerminalOutputFlow(ignored -> {
            emissionStarted.countDown();
            try {
                releaseEmission.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }, ignored -> { }, rejections::incrementAndGet, () -> { });
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> first = executor.submit(() -> flow.enqueue("seed"));
            assertTrue(emissionStarted.await(5, TimeUnit.SECONDS));

            flow.enqueue("x".repeat(Math.toIntExact(TerminalOutputFlow.PENDING_HARD_LIMIT)));
            assertEquals(TerminalOutputFlow.PENDING_HARD_LIMIT, flow.pendingBytes());
            flow.enqueue("overflow");

            assertTrue(flow.closed());
            assertEquals(0, flow.pendingBytes());
            assertEquals(1, rejections.get());
            releaseEmission.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            releaseEmission.countDown();
            executor.shutdownNow();
        }
    }

    @Test void outputFramesAreUtf8SafeAndBounded() {
        String output = "你好🙂terminal".repeat(4_000);
        List<String> frames = new CopyOnWriteArrayList<>();
        AtomicReference<TerminalOutputFlow> current = new AtomicReference<>();
        TerminalOutputFlow flow = new TerminalOutputFlow(frame -> {
            frames.add(frame);
            assertTrue(current.get().acknowledge(frame.getBytes(StandardCharsets.UTF_8).length));
        }, ignored -> { });
        current.set(flow);

        flow.enqueue(output);
        awaitText(frames, output);

        assertEquals(output, String.join("", frames));
        assertTrue(frames.size() > 1);
        assertTrue(frames.stream().allMatch(frame ->
                frame.getBytes(StandardCharsets.UTF_8).length <= TerminalOutputFlow.MAX_FRAME_BYTES));
        assertFalse(flow.closed());
    }

    @Test void waitsForEnoughCreditRatherThanSplittingAUnicodeCodePoint() throws Exception {
        List<String> frames = new CopyOnWriteArrayList<>();
        TerminalOutputFlow flow = new TerminalOutputFlow(frames::add, ignored -> { });
        String first = "x".repeat(Math.toIntExact(TerminalOutputFlow.UNACKED_HIGH_WATER - 1));

        flow.enqueue(first);
        awaitText(frames, first);
        flow.enqueue("🙂");
        Thread.sleep(TerminalOutputFlow.BATCH_INTERVAL_MS * 5L);

        assertEquals(TerminalOutputFlow.UNACKED_HIGH_WATER - 1,
                flow.unacknowledgedBytes());
        assertEquals(first, String.join("", frames));
        assertTrue(flow.acknowledge(3));
        awaitText(frames, first + "🙂");
        assertTrue(frames.stream().allMatch(frame ->
                StandardCharsets.UTF_8.newEncoder().canEncode(frame)));
    }

    @Test void pressureCallbacksRemainOrderedWhenAnAcknowledgementRacesAPause()
            throws Exception {
        List<Boolean> pressure = new CopyOnWriteArrayList<>();
        CountDownLatch pauseStarted = new CountDownLatch(1);
        CountDownLatch releasePause = new CountDownLatch(1);
        TerminalOutputFlow flow = new TerminalOutputFlow(ignored -> { }, value -> {
            pressure.add(value);
            if (!value) return;
            pauseStarted.countDown();
            try {
                if (!releasePause.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release pressure callback");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        });
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> sending = executor.submit(() -> flow.enqueue(
                    "x".repeat(Math.toIntExact(TerminalOutputFlow.UNACKED_HIGH_WATER))));
            assertTrue(pauseStarted.await(5, TimeUnit.SECONDS));
            assertTrue(flow.acknowledge(TerminalOutputFlow.UNACKED_HIGH_WATER
                    - TerminalOutputFlow.UNACKED_LOW_WATER));
            releasePause.countDown();
            sending.get(5, TimeUnit.SECONDS);
        } finally {
            releasePause.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of(true, false), pressure,
                "a late pause callback must not run after its matching resume");
        assertFalse(flow.closed());
    }

    @Test void rejectsAcknowledgementsLargerThanOutstandingOutput() {
        List<String> emitted = new CopyOnWriteArrayList<>();
        List<Boolean> pressure = new ArrayList<>();
        TerminalOutputFlow flow = new TerminalOutputFlow(emitted::add, pressure::add);
        flow.enqueue("你好");
        awaitEmission(emitted);

        assertFalse(flow.acknowledge(7), "two CJK characters occupy only six UTF-8 bytes");
        assertTrue(flow.acknowledge(6), "a valid acknowledgement consumes the exact byte window");
        assertFalse(flow.acknowledge(1), "the same output cannot be acknowledged twice");

        assertTrue(pressure.isEmpty());
    }

    @Test void emitterAndPressureCallbacksCanCloseWithoutLockInversion() {
        AtomicReference<TerminalOutputFlow> emittedFlow = new AtomicReference<>();
        TerminalOutputFlow emitterClosing = new TerminalOutputFlow(
                ignored -> emittedFlow.get().close(), ignored -> { });
        emittedFlow.set(emitterClosing);
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> emitterClosing.enqueue("x"));

        AtomicReference<TerminalOutputFlow> pressuredFlow = new AtomicReference<>();
        TerminalOutputFlow pressureClosing = new TerminalOutputFlow(
                ignored -> { }, ignored -> pressuredFlow.get().close());
        pressuredFlow.set(pressureClosing);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> pressureClosing.enqueue(
                "x".repeat(Math.toIntExact(TerminalOutputFlow.UNACKED_HIGH_WATER))));
    }

    @Test void normalCloseCompletesTheOutputExactlyOnce() {
        AtomicInteger completions = new AtomicInteger();
        TerminalOutputFlow flow = new TerminalOutputFlow(
                ignored -> { }, ignored -> { }, completions::incrementAndGet);

        flow.close();
        flow.close();

        assertEquals(1, completions.get());
    }

    private static void awaitEmission(List<String> emitted) {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (emitted.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertFalse(emitted.isEmpty(), "flow batch was not emitted");
    }

    private static void awaitText(List<String> emitted, String expected) {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (!String.join("", emitted).equals(expected) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, String.join("", emitted));
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition,
                                       String failureMessage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

}
