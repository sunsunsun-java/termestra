package dev.termestra.platform.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short-lived host command while draining its output independently of process completion.
 * The runner owns the process and all of its streams until the process exits or is terminated.
 */
public final class BoundedProcessRunner {
    private static final Duration TERMINATION_GRACE = Duration.ofMillis(250);
    private static final Duration FORCED_TERMINATION_GRACE = Duration.ofSeconds(1);
    private static final Duration OUTPUT_DRAIN_GRACE = Duration.ofSeconds(1);
    private static final int MAX_DESCENDANTS = 1_024;
    private final OwnedProcessTerminator terminator;

    public BoundedProcessRunner(){this(ProcessTreeTerminator::terminate);}
    BoundedProcessRunner(OwnedProcessTerminator terminator){
        this.terminator=Objects.requireNonNull(terminator,"terminator");
    }

    public Result run(List<String> command, Duration timeout, int maxOutputBytes)
            throws IOException, InterruptedException {
        List<String> effectiveCommand = List.copyOf(Objects.requireNonNull(command, "command"));
        if (effectiveCommand.isEmpty()) throw new IllegalArgumentException("command must not be empty");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxOutputBytes < 1) throw new IllegalArgumentException("maxOutputBytes must be positive");

        Process process = new ProcessBuilder(effectiveCommand).redirectErrorStream(true).start();
        OutputCollector collector = new OutputCollector(maxOutputBytes);
        Thread reader = Thread.ofVirtual().name("termestra-process-output-" + process.pid())
                .start(() -> collector.drain(process.getInputStream()));
        boolean timedOut = false;
        try {
            timedOut = !process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (timedOut) terminateOrThrow(process);
            awaitOutput(reader, process);
            if (collector.failure() != null && !timedOut) throw collector.failure();
            return new Result(timedOut ? -1 : process.exitValue(), collector.output(), timedOut,
                    collector.truncated());
        } catch (InterruptedException interrupted) {
            if(!terminate(process))interrupted.addSuppressed(new IOException(
                    "Could not confirm helper process-tree termination after interruption"));
            closeStreams(process);
            stopReaderAfterInterruption(reader);
            throw interrupted;
        } finally {
            closeStreams(process);
            // Successful and timed-out paths have already proven ownership cleanup. This is a
            // best second attempt for exceptional paths; the primary exception must not be hidden.
            if (process.isAlive()) terminate(process);
        }
    }

    private static void awaitOutput(Thread reader, Process process) throws IOException, InterruptedException {
        reader.join(OUTPUT_DRAIN_GRACE);
        if (!reader.isAlive()) return;
        closeInput(process);
        reader.interrupt();
        reader.join(TERMINATION_GRACE);
        if (reader.isAlive()) throw new IOException("Process output reader did not stop");
    }

    private void terminateOrThrow(Process process)throws IOException {
        if(!terminate(process))throw new IOException(
                "Could not confirm helper process-tree termination within the bounded deadline");
    }

    private boolean terminate(Process process) {
        return terminator.terminate(process,TERMINATION_GRACE,FORCED_TERMINATION_GRACE,
                MAX_DESCENDANTS);
    }

    private static void stopReaderAfterInterruption(Thread reader) {
        boolean interruptedAgain = Thread.interrupted();
        reader.interrupt();
        long deadline = System.nanoTime() + OUTPUT_DRAIN_GRACE.toNanos();
        while (reader.isAlive() && System.nanoTime() < deadline) {
            long remaining = Math.max(1, deadline - System.nanoTime());
            long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
            int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
            try {
                reader.join(millis, nanos);
            } catch (InterruptedException anotherInterrupt) {
                interruptedAgain = true;
                reader.interrupt();
            }
        }
        if (interruptedAgain) Thread.currentThread().interrupt();
    }

    private static void closeStreams(Process process) {
        closeInput(process);
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // Cleanup after an owned process; there is no useful recovery for a close failure.
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Cleanup after an owned process; there is no useful recovery for a close failure.
        }
    }

    private static void closeInput(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Cleanup after an owned process; there is no useful recovery for a close failure.
        }
    }

    public record Result(int exitCode, String output, boolean timedOut, boolean outputTruncated) { }

    @FunctionalInterface
    interface OwnedProcessTerminator {
        boolean terminate(Process process,Duration gracefulTimeout,Duration forcedTimeout,
                          int maxDescendants);
    }

    private static final class OutputCollector {
        private final int limit;
        private final ByteArrayOutputStream retained;
        private volatile boolean truncated;
        private volatile IOException failure;

        private OutputCollector(int limit) {
            this.limit = limit;
            this.retained = new ByteArrayOutputStream(Math.min(limit, 8_192));
        }

        private void drain(InputStream input) {
            byte[] buffer = new byte[8_192];
            try (input) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    retain(buffer, count);
                }
            } catch (IOException error) {
                failure = error;
            }
        }

        private synchronized void retain(byte[] buffer, int count) {
            int remaining = limit - retained.size();
            if (remaining > 0) retained.write(buffer, 0, Math.min(remaining, count));
            if (count > remaining) truncated = true;
        }

        private synchronized String output() {
            return retained.toString(StandardCharsets.UTF_8);
        }

        private boolean truncated() {
            return truncated;
        }

        private IOException failure() {
            return failure;
        }
    }
}
