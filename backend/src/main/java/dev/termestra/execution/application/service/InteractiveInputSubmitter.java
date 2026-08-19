package dev.termestra.execution.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InteractiveInputSubmitter {
    private static final Set<String> INTERACTIVE = Set.of(
            "agy", "claude", "codex", "cursor-agent", "gemini", "grok", "hermes", "opencode", "pi", "qwen");
    private static final Set<String> BRACKETED_PASTE = Set.of(
            "agy", "claude", "codex", "grok", "hermes", "opencode", "pi");
    private static final Set<String> NO_SOFT_READY_TIMEOUT = Set.of(
            "agy", "gemini", "hermes", "opencode", "pi", "qwen");
    private static final Pattern COMMAND_NAME = Pattern.compile(
            "(?:^|[/\\\\\\s\\\"'])(agy|claude|codex|cursor-agent|gemini|grok|hermes|opencode|pi|qwen)(?:\\.cmd|\\.exe)?(?:$|[\\s\\\"'])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PASTE_ACK = Pattern.compile(
            "(?is).*\\[(?:Pasted\\s+text(?:\\s+#\\d+)?[^]]*|Pasted\\s+Content\\s+[\\d,]+\\s+chars?)].*");
    private static final Pattern HERMES_PROMPT = Pattern.compile(
            "^(?:[\\p{L}\\p{N}_.-]+\\s+)?[❯›>](?:\\s*[─━═╌╍┄┅┈┉-]+)?\\s*$");
    private static final Pattern DECORATION_LINE = Pattern.compile("^[─━═╌╍┄┅┈┉-]{6,}$");
    private static final long READY_TIMEOUT_MS = 3_000;
    private static final long HARD_READY_TIMEOUT_MS = 30_000;
    private static final long PASTE_ACK_TIMEOUT_MS = 3_000;
    private static final long PASTE_ACK_SETTLE_MS = 100;
    private static final long POLL_INTERVAL_MS = 50;
    private static final long NO_READY_POSITION = -1;

    private InteractiveInputSubmitter() { }

    static String commandName(String command) {
        if (command == null || command.isBlank()) return null;
        Matcher matcher = COMMAND_NAME.matcher(command.trim());
        return matcher.find() ? matcher.group(1).toLowerCase() : null;
    }

    static boolean supports(String command) {
        String executable = commandName(command);
        return executable != null && INTERACTIVE.contains(executable);
    }

    /**
     * Submits one complete terminal input. Unlike the former implementation, returning from this
     * method means both the body and the final Enter have been written.
     */
    static void submit(String command, String text, BooleanSupplier active,
                       Supplier<InteractiveOutputTail.Snapshot> output,
                       Consumer<byte[]> input) {
        submit(command, text, active, output, input, NO_READY_POSITION);
    }

    /**
     * Submits one complete terminal input after observing a prompt produced after the supplied
     * output position. A negative position permits an already-visible prompt. The position-aware
     * overload is intended for serialized submissions, so an old prompt cannot release the next
     * queued message.
     */
    static long submit(String command, String text, BooleanSupplier active,
                       Supplier<InteractiveOutputTail.Snapshot> output,
                       Consumer<byte[]> input, long readyAfterPosition) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(input, "input");

        String executable = commandName(command);
        if (executable == null) {
            requireActive(active, false, "Process exited before terminal input was written");
            long acceptedPosition = snapshot(output, false).position();
            write(input, (text + "\n").getBytes(StandardCharsets.UTF_8));
            return acceptedPosition;
        }

        long acceptedPromptPosition = awaitReadyPrompt(executable, active, output, readyAfterPosition);
        pasteAndComplete(executable, text, active, output, input);
        return acceptedPromptPosition;
    }

    private static long awaitReadyPrompt(String executable, BooleanSupplier active,
                                         Supplier<InteractiveOutputTail.Snapshot> output,
                                         long readyAfterPosition) {
        long started = System.nanoTime();
        long deadline = deadlineAfter(HARD_READY_TIMEOUT_MS);
        while (true) {
            requireActive(active, false, "Process exited before an input prompt became ready");
            InteractiveOutputTail.Snapshot snapshot = snapshot(output, false);
            boolean hasNewOutput = readyAfterPosition < 0 || snapshot.position() > readyAfterPosition;
            String candidate = readyAfterPosition < 0
                    ? snapshot.tail()
                    : snapshot.appendedSince(readyAfterPosition);
            long elapsed = elapsedMillis(started);
            boolean softFallback = !NO_SOFT_READY_TIMEOUT.contains(executable)
                    && elapsed >= READY_TIMEOUT_MS;
            boolean readyPrompt = promptReady(candidate, executable);
            if (hasNewOutput && (readyPrompt || (!firstRunSetupPrompt(candidate) && softFallback))) {
                return snapshot.position();
            }
            if (System.nanoTime() >= deadline) {
                throw new SubmissionException(
                        "Timed out waiting for " + executable + " input prompt", false);
            }
            pause(false, "Interrupted while waiting for an input prompt");
        }
    }

    private static void pasteAndComplete(String executable, String text, BooleanSupplier active,
                                         Supplier<InteractiveOutputTail.Snapshot> output,
                                         Consumer<byte[]> input) {
        requireActive(active, false, "Process exited before terminal input was written");
        long baseline = snapshot(output, false).position();
        String submitted = BRACKETED_PASTE.contains(executable)
                ? "\u001b[200~" + text + "\u001b[201~"
                : text;
        write(input, submitted.getBytes(StandardCharsets.UTF_8));

        if (waitsForPasteAcknowledgement(executable, text)) {
            awaitPasteAcknowledgement(text, active, output, baseline);
        } else {
            awaitMinimumPasteDelay(text, active);
        }

        requireActive(active, true, "Process exited before pasted input could be submitted");
        write(input, "\r".getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitPasteAcknowledgement(String text, BooleanSupplier active,
                                                   Supplier<InteractiveOutputTail.Snapshot> output,
                                                   long baseline) {
        long started = System.nanoTime();
        long minimumDelay = minimumPasteDelay(text);
        long deadline = deadlineAfter(PASTE_ACK_TIMEOUT_MS);
        Long acknowledgedAt = null;
        while (true) {
            requireActive(active, true, "Process exited while waiting for pasted input acknowledgement");
            String recent = snapshot(output, true).appendedSince(baseline);
            if (acknowledgedAt == null && PASTE_ACK.matcher(plainTail(recent)).matches()) {
                acknowledgedAt = System.nanoTime();
            }
            if (acknowledgedAt != null
                    && elapsedMillis(started) >= minimumDelay
                    && elapsedMillis(acknowledgedAt) >= PASTE_ACK_SETTLE_MS) return;
            if (System.nanoTime() >= deadline) return;
            pause(true, "Interrupted while waiting for pasted input acknowledgement");
        }
    }

    private static void awaitMinimumPasteDelay(String text, BooleanSupplier active) {
        long deadline = deadlineAfter(minimumPasteDelay(text));
        while (System.nanoTime() < deadline) {
            requireActive(active, true, "Process exited before pasted input settled");
            pause(true, "Interrupted while waiting for pasted input to settle");
        }
    }

    private static long minimumPasteDelay(String text) {
        return Math.min(1_500, Math.max(600, (text.length() + 3L) / 4L));
    }

    private static boolean waitsForPasteAcknowledgement(String executable, String text) {
        return "claude".equals(executable) || ("hermes".equals(executable)
                && (text.length() >= 2_000
                || text.chars().filter(character -> character == '\n').count() >= 4));
    }

    private static void write(Consumer<byte[]> input, byte[] bytes) {
        try {
            input.accept(bytes);
        } catch (SubmissionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SubmissionException("Failed to write terminal input", true, failure);
        }
    }

    private static InteractiveOutputTail.Snapshot snapshot(
            Supplier<InteractiveOutputTail.Snapshot> output, boolean inputAttempted) {
        try {
            return Objects.requireNonNull(output.get(), "output snapshot");
        } catch (SubmissionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SubmissionException("Failed to read terminal output", inputAttempted, failure);
        }
    }

    private static void requireActive(BooleanSupplier active, boolean inputAttempted, String message) {
        final boolean processActive;
        try {
            processActive = active.getAsBoolean();
        } catch (RuntimeException failure) {
            throw new SubmissionException("Failed to inspect terminal process state", inputAttempted, failure);
        }
        if (!processActive) throw new SubmissionException(message, inputAttempted);
    }

    private static void pause(boolean inputAttempted, String message) {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SubmissionException(message, inputAttempted, interrupted);
        }
    }

    private static boolean promptReady(String output, String executable) {
        String plain = plainTail(output);
        String last = lastNonEmptyLine(plain);
        if (last.matches("[❯›]")) return true;
        return switch (executable) {
            case "agy" -> plain.matches("(?s).*(?:^|\\n)\\s*>\\s*\\n\\s*(?:[─-]{8,}|\\?\\s*for shortcuts).*");
            case "gemini", "qwen" -> plain.contains("Type your message");
            case "grok" -> plain.matches("(?s).*\\b(?:Enter:send|Composer\\s+\\S+).*");
            case "hermes" -> hermesPromptNearTail(plain);
            case "opencode" -> plain.contains("Ask anything...") || plain.matches("(?s).*\\besc\\s+interrupt\\b.*");
            case "pi" -> plain.matches("(?is).*\\bpi\\s+v\\d+(?:\\.\\d+){1,3}.*\\b(?:escape\\s+interrupt|ctrl\\+c/ctrl\\+d\\s+clear/exit)\\b.*");
            default -> false;
        };
    }

    private static boolean hermesPromptNearTail(String output) {
        String[] lines = output.split("\\n");
        int significantLines = 0;
        for (int index = lines.length - 1; index >= 0 && significantLines < 8; index--) {
            String line = lines[index].trim();
            if (line.isBlank()) continue;
            significantLines++;
            if (HERMES_PROMPT.matcher(line).matches()) return true;
            if (significantLines == 1 && DECORATION_LINE.matcher(line).matches()) continue;
        }
        return false;
    }

    static boolean promptReadyForTest(String output, String executable) {
        return promptReady(output, executable);
    }

    private static boolean firstRunSetupPrompt(String output) {
        String plain = plainTail(output);
        if (lastNonEmptyLine(plain).matches("[❯›]")) return false;
        return plain.matches("(?is).*\\b(?:Do you trust|trust this (?:directory|folder|workspace|project)|Log in|Login required|Sign in|Enter to confirm|Return to confirm|Choose a (?:theme|option|provider)|Pick a (?:theme|option|provider)|Select a (?:theme|option|provider))\\b.*");
    }

    private static String plainTail(String output) {
        String plain = output.replace('\r', '\n')
                .replaceAll("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)", "")
                .replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "");
        return plain.substring(Math.max(0, plain.length() - 8_000));
    }

    private static String lastNonEmptyLine(String output) {
        String[] lines = output.split("\\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            if (!lines[index].isBlank()) return lines[index].trim();
        }
        return "";
    }

    private static long deadlineAfter(long milliseconds) {
        return System.nanoTime() + Duration.ofMillis(milliseconds).toNanos();
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    static final class SubmissionException extends RuntimeException {
        private final boolean inputAttempted;

        SubmissionException(String message, boolean inputAttempted) {
            super(message);
            this.inputAttempted = inputAttempted;
        }

        SubmissionException(String message, boolean inputAttempted, Throwable cause) {
            super(message, cause);
            this.inputAttempted = inputAttempted;
        }

        boolean inputAttempted() {
            return inputAttempted;
        }
    }
}
