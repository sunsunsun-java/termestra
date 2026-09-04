package dev.termestra.execution.adapter.out.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.platform.process.BoundedProcessRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessAgentModelDiscoveryTest {
    @Test void parsesCodexModelListAndCachesTheBoundedResult() {
        AtomicInteger calls = new AtomicInteger();
        String output = "diagnostic\n" +
                "{\"id\":1,\"result\":{}}\n" +
                "{\"id\":2,\"result\":{\"data\":[" +
                "{\"model\":\"gpt-5.6-sol\",\"hidden\":false}," +
                "{\"model\":\"hidden-model\",\"hidden\":true}]}}\n";
        ProcessAgentModelDiscovery discovery = new ProcessAgentModelDiscovery(new ObjectMapper(),
                (command, directory, input, timeout, maximum) -> {
                    calls.incrementAndGet();
                    assertEquals(List.of("codex", "app-server", "--listen", "stdio://"), command);
                    assertTrue(input.contains("\"method\":\"model/list\""));
                    return new BoundedProcessRunner.Result(0, output, false, false);
                });

        assertEquals(List.of("gpt-5.6-sol"), discovery.discover("codex", "codex", "/tmp"));
        assertEquals(List.of("gpt-5.6-sol"), discovery.discover("codex", "codex", "/tmp"));
        assertEquals(1, calls.get());
    }

    @Test void parsesSupportedLineOrientedCliFormats() {
        ProcessAgentModelDiscovery discovery = new ProcessAgentModelDiscovery(new ObjectMapper(),
                (command, directory, input, timeout, maximum) -> switch (command.getFirst()) {
                    case "cursor-agent" -> new BoundedProcessRunner.Result(0,
                            "Warning - ignored before table\nAvailable models\nauto - Auto (default)\n"
                                    + "gpt-5.6-sol - GPT\n", false, false);
                    case "opencode" -> new BoundedProcessRunner.Result(0,
                            "https://diagnostic.invalid\nopencode/free\nopenai/gpt-5.6-sol\n", false, false);
                    case "pi" -> new BoundedProcessRunner.Result(0,
                            "Warning: invalid settings\nprovider model context\n"
                                    + "openai-codex gpt-5.6-sol 272K\nWarning: invalid settings\n", false, false);
                    default -> throw new AssertionError(command);
                });

        assertEquals(List.of("auto", "gpt-5.6-sol"),
                discovery.discover("cursor", "cursor-agent", "/tmp"));
        assertEquals(List.of("opencode/free", "openai/gpt-5.6-sol"),
                discovery.discover("opencode", "opencode", "/tmp"));
        assertEquals(List.of("openai-codex/gpt-5.6-sol"),
                discovery.discover("pi", "pi", "/tmp"));
    }

    @Test void degradesUnsupportedFailedAndTruncatedDiscoveryToNoModels() {
        AtomicInteger calls = new AtomicInteger();
        ProcessAgentModelDiscovery discovery = new ProcessAgentModelDiscovery(new ObjectMapper(),
                (command, directory, input, timeout, maximum) -> switch (calls.getAndIncrement()) {
                    case 0 -> new BoundedProcessRunner.Result(1, "failure", false, false);
                    case 1 -> new BoundedProcessRunner.Result(-1, "", true, false);
                    default -> new BoundedProcessRunner.Result(0, "Available models\na - A\n", false, true);
                });

        assertEquals(List.of(), discovery.discover("claude", "claude", "/tmp"));
        assertEquals(List.of(), discovery.discover("cursor", "cursor-agent", "/tmp/failed"));
        assertEquals(List.of(), discovery.discover("cursor", "cursor-agent", "/tmp/timed-out"));
        assertEquals(List.of(), discovery.discover("cursor", "cursor-agent", "/tmp/truncated"));
        assertEquals(3, calls.get());
    }

    @Test void sharesColdDiscoveryAndRejectsWorkAboveTheConcurrencyBudget() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ProcessAgentModelDiscovery discovery = new ProcessAgentModelDiscovery(new ObjectMapper(),
                (command, directory, input, timeout, maximum) -> {
                    calls.incrementAndGet();
                    running.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    return new BoundedProcessRunner.Result(0,
                            "Available models\ngpt-a - GPT A\n", false, false);
                }, 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> discovery.discover("cursor", "cursor-agent", "/tmp/one"));
            assertTrue(running.await(3, TimeUnit.SECONDS));
            var shared = executor.submit(() -> discovery.discover("cursor", "cursor-agent", "/tmp/one"));
            assertEquals(List.of(), discovery.discover("cursor", "cursor-agent", "/tmp/two"));
            release.countDown();
            assertEquals(List.of("gpt-a"), first.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("gpt-a"), shared.get(3, TimeUnit.SECONDS));
        }
        assertEquals(1, calls.get());
    }
}
