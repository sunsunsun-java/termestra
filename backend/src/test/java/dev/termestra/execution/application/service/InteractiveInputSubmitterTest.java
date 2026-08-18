package dev.termestra.execution.application.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveInputSubmitterTest {
    @Test void submitsClaudeMessagesWithBracketedPasteAndASeparateEnter() {
        List<String> writes = new CopyOnWriteArrayList<>();
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("\r\n❯ ");

        InteractiveInputSubmitter.submit("/usr/local/bin/claude", "hello", () -> true,
                output::snapshot, bytes -> {
                    String value = new String(bytes, StandardCharsets.UTF_8);
                    writes.add(value);
                    if (value.contains("\u001b[200~")) output.append("[Pasted text #1 +1 lines]");
                });

        assertEquals(List.of("\u001b[200~hello\u001b[201~", "\r"), writes);
    }

    @Test void submitsCodexMessagesWithoutRequiringAPasteAcknowledgement() {
        List<String> writes = new CopyOnWriteArrayList<>();
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("\r\n› ");

        InteractiveInputSubmitter.submit("codex", "one\ntwo\nthree", () -> true,
                output::snapshot,
                bytes -> writes.add(new String(bytes, StandardCharsets.UTF_8)));

        assertEquals(List.of("\u001b[200~one\ntwo\nthree\u001b[201~", "\r"), writes);
    }

    @Test void submitsClaudeMessagesAfterTheBoundedWaitWhenNoPasteMarkerIsRendered() {
        List<String> writes = new CopyOnWriteArrayList<>();
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("\r\n❯ ");

        InteractiveInputSubmitter.submit("claude", "visible composer text", () -> true,
                output::snapshot,
                bytes -> writes.add(new String(bytes, StandardCharsets.UTF_8)));

        assertEquals(List.of("\u001b[200~visible composer text\u001b[201~", "\r"), writes);
    }

    @Test void submitsMultilineHermesMessagesOnlyAfterPasteAcknowledgement() {
        List<String> writes = new CopyOnWriteArrayList<>();
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("\u001b[36mdefault ❯\u001b[0m\n────────────────────────");
        String message = "one\ntwo\nthree\nfour\nfive";

        InteractiveInputSubmitter.submit("hermes --yolo", message, () -> true,
                output::snapshot, bytes -> {
                    String value = new String(bytes, StandardCharsets.UTF_8);
                    writes.add(value);
                    if (value.contains("\u001b[200~")) {
                        output.append("\r[Pasted text #1: 5 lines → /tmp/paste.txt]");
                    }
                });

        assertEquals(List.of("\u001b[200~" + message + "\u001b[201~", "\r"), writes);
    }

    @Test void submitsMultilineHermesMessagesAfterTheBoundedWaitWithoutAPasteMarker() {
        List<String> writes = new CopyOnWriteArrayList<>();
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("\u001b[36mdefault ❯\u001b[0m\n────────────────────────");
        String message = "one\ntwo\nthree\nfour\nfive";

        InteractiveInputSubmitter.submit("hermes --yolo", message, () -> true,
                output::snapshot,
                bytes -> writes.add(new String(bytes, StandardCharsets.UTF_8)));

        assertEquals(List.of("\u001b[200~" + message + "\u001b[201~", "\r"), writes);
    }

    @Test void recognizesHermesPromptNearDecoratedTuiTailWithoutWelcomeBanner() {
        String realStyleTail = "\u001b[2Kworking output\r\n"
                + "\u001b[38;5;81mcoder ❯ \u001b[0m\r\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\r\n";

        assertTrue(InteractiveInputSubmitter.promptReadyForTest(realStyleTail, "hermes"));
    }

    @Test void waitsForAPromptProducedAfterTheRequestedOutputPosition() throws Exception {
        List<String> writes = new CopyOnWriteArrayList<>();
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("❯");
        long oldPromptPosition = output.snapshot().position();

        CompletableFuture<Long> submitted = CompletableFuture.supplyAsync(() ->
                InteractiveInputSubmitter.submit("hermes", "hello", () -> true,
                        output::snapshot,
                        bytes -> writes.add(new String(bytes, StandardCharsets.UTF_8)),
                        oldPromptPosition));

        Thread.sleep(150);
        assertTrue(writes.isEmpty(), "an old prompt must not release the next queued input");
        output.append("\r\n❯ ");
        long acceptedPromptPosition = submitted.get(2, TimeUnit.SECONDS);

        assertEquals(List.of("\u001b[200~hello\u001b[201~", "\r"), writes);
        assertTrue(acceptedPromptPosition > oldPromptPosition);
    }

    @Test void reportsProcessExitBeforePromptAsNotAttempted() {
        AtomicBoolean active = new AtomicBoolean(true);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            active.set(false);
        });

        InteractiveInputSubmitter.SubmissionException failure = assertThrows(
                InteractiveInputSubmitter.SubmissionException.class,
                () -> InteractiveInputSubmitter.submit("hermes", "hello", active::get,
                        new InteractiveOutputTail()::snapshot, ignored -> { }));

        assertFalse(failure.inputAttempted());
        assertTrue(failure.getMessage().contains("Process exited"));
    }

    @Test void reportsWriteFailureAsAttemptedAndPreservesItsCause() {
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("❯");
        IllegalStateException writeFailure = new IllegalStateException("closed PTY");

        InteractiveInputSubmitter.SubmissionException failure = assertThrows(
                InteractiveInputSubmitter.SubmissionException.class,
                () -> InteractiveInputSubmitter.submit("hermes", "hello", () -> true,
                        output::snapshot, ignored -> { throw writeFailure; }));

        assertTrue(failure.inputAttempted());
        assertEquals(writeFailure, failure.getCause());
    }

    @Test void doesNotReturnUntilTheFinalEnterWriteCompletes() throws Exception {
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("❯");
        CountDownLatch enterWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseEnterWrite = new CountDownLatch(1);

        CompletableFuture<Void> submitted = CompletableFuture.runAsync(() ->
                InteractiveInputSubmitter.submit("hermes", "hello", () -> true,
                        output::snapshot, bytes -> {
                            if ("\r".equals(new String(bytes, StandardCharsets.UTF_8))) {
                                enterWriteStarted.countDown();
                                try {
                                    releaseEnterWrite.await();
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                            }
                        }));

        assertTrue(enterWriteStarted.await(2, TimeUnit.SECONDS));
        assertFalse(submitted.isDone());
        releaseEnterWrite.countDown();
        submitted.get(2, TimeUnit.SECONDS);
        assertTrue(submitted.isDone());
    }

    @Test void keepsPlainNewlineDeliveryForNonInteractiveCommands() {
        List<String> writes = new CopyOnWriteArrayList<>();
        InteractiveInputSubmitter.submit("/bin/cat", "hello", () -> true,
                new InteractiveOutputTail()::snapshot,
                bytes -> writes.add(new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(List.of("hello\n"), writes);
    }

    @Test void recognizesQuotedAndWindowsInteractiveExecutables() {
        assertEquals("claude", InteractiveInputSubmitter.commandName("\"/Applications/Claude Code/claude\" --flag"));
        assertEquals("codex", InteractiveInputSubmitter.commandName("C:\\tools\\codex.exe"));
        assertEquals("cursor-agent", InteractiveInputSubmitter.commandName("/usr/local/bin/cursor-agent"));
        for (String command : List.of("agy", "claude", "codex", "cursor-agent", "gemini", "grok", "hermes", "opencode", "pi", "qwen")) {
            assertTrue(InteractiveInputSubmitter.supports(command), command);
        }
    }

    @Test void recognizesCliSpecificReadyPrompts() {
        assertTrue(InteractiveInputSubmitter.promptReadyForTest("Type your message", "qwen"));
        assertTrue(InteractiveInputSubmitter.promptReadyForTest("Welcome to Hermes Agent!\n❯", "hermes"));
        assertTrue(InteractiveInputSubmitter.promptReadyForTest("pi v0.32.1 escape interrupt", "pi"));
        assertTrue(InteractiveInputSubmitter.promptReadyForTest("Composer ready Enter:send", "grok"));
    }
}
