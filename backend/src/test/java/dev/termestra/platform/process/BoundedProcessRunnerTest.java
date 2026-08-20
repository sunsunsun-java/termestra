package dev.termestra.platform.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BoundedProcessRunnerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void drainsLargeOutputWithoutRetainingMoreThanTheConfiguredLimit() throws Exception {
        BoundedProcessRunner.Result result = new BoundedProcessRunner()
                .run(childCommand("output", "1048576"), Duration.ofSeconds(5), 4_096);

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.outputTruncated());
        assertEquals(4_096, result.output().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void timesOutAndReapsAProcessThatDoesNotExit() throws Exception {
        Path pidFile = temporaryDirectory.resolve("child.pid");
        long started = System.nanoTime();

        BoundedProcessRunner.Result result = new BoundedProcessRunner()
                .run(childCommand("hang", pidFile.toString()), Duration.ofMillis(250), 1_024);

        assertTrue(result.timedOut());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "a timed-out helper process must be reaped before run returns");
    }

    @Test
    void keepsDrainingButDoesNotRetainUnboundedOutputBeforeTimeout() throws Exception {
        Path pidFile = temporaryDirectory.resolve("flood.pid");

        BoundedProcessRunner.Result result = new BoundedProcessRunner()
                .run(childCommand("flood", pidFile.toString()), Duration.ofMillis(250), 4_096);

        assertTrue(result.timedOut());
        assertTrue(result.outputTruncated());
        assertEquals(4_096, result.output().getBytes(StandardCharsets.UTF_8).length);
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void interruptionAlsoReapsTheOwnedProcess() throws Exception {
        Path pidFile = temporaryDirectory.resolve("interrupted.pid");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                new BoundedProcessRunner().run(childCommand("hang", pidFile.toString()),
                        Duration.ofMinutes(1), 1_024);
                failure.set(new AssertionError("run should have been interrupted"));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        awaitFile(pidFile);

        caller.interrupt();
        caller.join(Duration.ofSeconds(3));

        assertFalse(caller.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "an interrupted caller must not leave its helper process running");
    }

    @Test
    void capturesBoundedOutputAndExitStatusFromACompletedProcess() throws Exception {
        BoundedProcessRunner.Result result = new BoundedProcessRunner()
                .run(childCommand("exit", "7", "diagnostic"), Duration.ofSeconds(5), 1_024);

        assertEquals(7, result.exitCode());
        assertEquals("diagnostic", result.output());
        assertFalse(result.timedOut());
        assertFalse(result.outputTruncated());
    }

    @Test
    void neverReturnsSuccessWhenOwnedProcessTreeTerminationCannotBeConfirmed() throws Exception {
        Path pidFile = temporaryDirectory.resolve("unconfirmed.pid");
        BoundedProcessRunner runner = new BoundedProcessRunner(
                (process, graceful, forced, maximum) -> {
                    process.destroyForcibly();
                    return false;
                });

        IOException failure = assertThrows(IOException.class, () -> runner.run(
                childCommand("hang", pidFile.toString()), Duration.ofMillis(250), 1_024));

        assertTrue(failure.getMessage().contains("Could not confirm helper process-tree termination"));
        awaitFile(pidFile);
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).ifPresent(ProcessHandle::destroyForcibly);
    }

    private static List<String> childCommand(String... arguments) {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(executable.toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Child.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(path), "child process did not publish its pid");
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "output" -> {
                    int length = Integer.parseInt(args[1]);
                    byte[] block = "x".repeat(8_192).getBytes(StandardCharsets.UTF_8);
                    for (int written = 0; written < length; written += block.length) {
                        System.out.write(block, 0, Math.min(block.length, length - written));
                    }
                }
                case "hang" -> {
                    Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(Duration.ofMinutes(5));
                }
                case "flood" -> {
                    Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                    byte[] block = "x".repeat(8_192).getBytes(StandardCharsets.UTF_8);
                    while (true) System.out.write(block);
                }
                case "exit" -> {
                    System.out.print(args[2]);
                    System.exit(Integer.parseInt(args[1]));
                }
                default -> throw new IllegalArgumentException("Unknown fixture command");
            }
        }
    }
}
