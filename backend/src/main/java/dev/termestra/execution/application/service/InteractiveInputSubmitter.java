package dev.termestra.execution.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import dev.termestra.execution.application.port.out.PromptTerminal;
import java.util.regex.Pattern;

final class InteractiveInputSubmitter {
    private static final Set<String> INTERACTIVE = Set.of(
            "agy", "claude", "codex", "cursor-agent", "gemini", "grok", "hermes", "opencode", "pi", "qwen");
    private static final Set<String> BRACKETED_PASTE = Set.of(
            "agy", "claude", "codex", "grok", "hermes", "opencode", "pi");
    private static final Pattern COMMAND_NAME = Pattern.compile(
            "(?:^|[/\\\\\\s\\\"'])(agy|claude|codex|cursor-agent|gemini|grok|hermes|opencode|pi|qwen)(?:\\.cmd|\\.exe)?(?:$|[\\s\\\"'])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PASTE_ACK = Pattern.compile(
            "(?is).*\\[(?:Pasted\\s+text(?:\\s+#\\d+)?[^]]*|Pasted\\s+Content\\s+[\\d,]+\\s+chars?)].*");
    private static final Pattern HERMES_PROMPT = Pattern.compile(
            "^(?:[\\p{L}\\p{N}_.-]+\\s+)?[❯›>](?:\\s*[─━═╌╍┄┅┈┉-]+)?\\s*$");
    private static final Pattern DECORATION_LINE = Pattern.compile("^[─━═╌╍┄┅┈┉-]{6,}$");
    private static final Pattern BUSY_HINT = Pattern.compile("(?i)\\besc(?:ape)?\\s+(?:again\\s+)?(?:to\\s+)?interrupt\\b");
    private static final long READY_SETTLE_MS = 250;
    private static final long STARTUP_TIMEOUT_MS = 120_000;
    private static final long USER_WAIT_TIMEOUT_MS = 600_000;
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
        return submit(command, text, active, output, input, readyAfterPosition, false);
    }

    static long submit(String command, String text, BooleanSupplier active,
                       Supplier<InteractiveOutputTail.Snapshot> output, Consumer<byte[]> input,
                       long readyAfterPosition, boolean deferIfBusy) {
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

        long acceptedPromptPosition = awaitReadyPrompt(executable, active, output, readyAfterPosition, null, deferIfBusy);
        pasteAndComplete(executable, text, active, output, input);
        return acceptedPromptPosition;
    }

    static long submitStartup(String command, String text, BooleanSupplier active,
                              Supplier<InteractiveOutputTail.Snapshot> output,
                              Consumer<byte[]> input, Consumer<String> onWaitingForUser) {
        Objects.requireNonNull(onWaitingForUser, "onWaitingForUser");
        String executable = commandName(command);
        if (executable == null) {
            return submit(command, text, active, output, input, NO_READY_POSITION);
        }
        long acceptedPosition = awaitStartup(command, active, output, onWaitingForUser);
        pasteAndComplete(executable, text, active, output, input);
        return acceptedPosition;
    }

    static long awaitStartup(String command, BooleanSupplier active,
                             Supplier<InteractiveOutputTail.Snapshot> output,
                             Consumer<String> onWaitingForUser) {
        Objects.requireNonNull(onWaitingForUser, "onWaitingForUser");
        String executable = commandName(command);
        if (executable == null) {
            requireActive(active, false, "Process exited before startup completed");
            return snapshot(output, false).position();
        }
        return awaitReadyPrompt(executable, active, output, NO_READY_POSITION, onWaitingForUser, false);
    }

    private static long awaitReadyPrompt(String executable, BooleanSupplier active,
                                         Supplier<InteractiveOutputTail.Snapshot> output,
                                         long readyAfterPosition, Consumer<String> onWaitingForUser, boolean deferIfBusy) {
        long started = System.nanoTime();
        long lastPoll = started;
        long initializingNanos = 0;
        Long readySince = null;
        String waitingReason = null;
        while (true) {
            requireActive(active, false, "Process exited before an input prompt became ready");
            InteractiveOutputTail.Snapshot snapshot = snapshot(output, false);
            InteractiveOutputTail.Readiness readiness = snapshot.readiness();
            long now = System.nanoTime();
            if (waitingReason == null) initializingNanos += now - lastPoll;
            lastPoll = now;
            String nextReason = readiness.state() == InteractiveOutputTail.State.WAITING_FOR_USER
                    ? readiness.reason() : null;
            if (!Objects.equals(waitingReason, nextReason)) {
                waitingReason = nextReason;
                if (onWaitingForUser != null) onWaitingForUser.accept(waitingReason);
            }
            if (waitingReason != null && onWaitingForUser == null) {
                throw new SubmissionException(executable + " is waiting for user action: " + waitingReason
                        + ". Complete it in the terminal, then retry.", false);
            }
            if (deferIfBusy && readiness.busy()) {
                throw SubmissionException.deferred(executable + " is busy; waiting for its next input prompt");
            }
            boolean ready = readiness.state() == InteractiveOutputTail.State.READY
                    && (readyAfterPosition < 0 || readiness.position() > readyAfterPosition);
            if (ready) {
                if (readySince == null) readySince = now;
                if (elapsedMillis(readySince) >= READY_SETTLE_MS) return snapshot.position();
            } else readySince = null;
            boolean expired = onWaitingForUser == null ? elapsedMillis(started) >= HARD_READY_TIMEOUT_MS
                    : elapsedMillis(started) >= USER_WAIT_TIMEOUT_MS
                        || initializingNanos >= Duration.ofMillis(STARTUP_TIMEOUT_MS).toNanos();
            if (expired) {
                throw new SubmissionException(waitingReason == null
                        ? "Timed out waiting for " + executable + " input prompt"
                        : "Timed out waiting for user action in " + executable + ": " + waitingReason, false);
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

    static String waitingReason(String plain) {
        // Called only when the current input region is not a verified composer. Visible responses
        // and resumed history may quote these same instructions without opening a setup dialog.
        String normalized = plain.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.matches("(?s).*(?:do you trust|trust this (?:directory|folder|workspace|project)|yes,? i trust|workspace trust required).*")) {
            return "Confirm workspace trust in the terminal";
        }
        if (normalized.matches("(?s).*(?:login required|sign in to|log in to|please (?:log|sign) in|choose (?:a )?login|select (?:a )?login|open (?:this|the) (?:url|link).*sign in).*")) {
            return "Complete CLI login in the terminal";
        }
        if (normalized.matches("(?s).*(?:enter to confirm|return to confirm|press enter to continue|enter confirm|choose a (?:theme|option|provider)|pick a (?:theme|option|provider)|select a (?:theme|option|provider)).*")) {
            return "Complete CLI setup in the terminal";
        }
        return null;
    }

    static boolean screenReady(PromptTerminal terminal, PromptTerminal.View view,
                               String executable, boolean piIdentity) {
        String screen = String.join("\n", view.lines());
        if ("codex".equals(executable) && codexHeaderLoading(view)) return false;
        if (!"pi".equals(executable) && currentBusyIndicator(terminal, view, executable)) return false;
        int row = view.cursorRow();
        String current = row >= 0 && row < view.lines().size() ? view.lines().get(row) : "";
        String trimmed = current.trim();
        return switch (executable == null ? "" : executable) {
            case "hermes" -> hermesScreenPrompt(terminal, view);
            case "claude" -> (trimmed.matches("[❯›]\\s*") && emptyPromptAtCursor(current, view.cursorColumn()))
                    || (trimmed.startsWith("❯ ") && placeholderAtCursor(terminal, view, '❯', false));
            case "codex" -> (trimmed.matches("[❯›»]\\s*") && emptyPromptAtCursor(current, view.cursorColumn()))
                    || ((trimmed.startsWith("› ") || trimmed.startsWith("» "))
                        && placeholderAtCursor(terminal, view, trimmed.charAt(0), false));
            case "pi" -> piIdentity && trimmed.isEmpty() && row > 0 && row + 1 < view.lines().size()
                    && DECORATION_LINE.matcher(view.lines().get(row - 1).trim()).matches()
                    && DECORATION_LINE.matcher(view.lines().get(row + 1).trim()).matches();
            case "cursor-agent" -> cursorComposer(view).matches("\\s*→\\s*(?:Plan, search, build anything|Add a follow-up)\\s*");
            case "opencode" -> current.contains("Ask anything...")
                    && view.cursorColumn() == current.indexOf("Ask anything...")
                    || current.contains("Ask anything…")
                    && view.cursorColumn() == current.indexOf("Ask anything…")
                    || openCodeEmptyComposer(view);
            case "agy" -> trimmed.equals(">") && emptyPromptAtCursor(current, view.cursorColumn())
                    && row + 1 < view.lines().size()
                    && view.lines().get(row + 1).trim().matches("(?:[─-]{8,}|\\?\\s*for shortcuts.*)");
            case "gemini", "qwen" -> screen.contains("Type your message");
            case "grok" -> screen.matches("(?s).*\\b(?:Enter:send|Composer\\s+\\S+).*");
            default -> false;
        };
    }

    private static boolean openCodeEmptyComposer(PromptTerminal.View view) {
        // OpenCode's session page omits the homepage placeholder. Require the complete
        // empty composer and input-start cursor, rather than any blank terminal line.
        int row = view.cursorRow();
        if (row < 2 || row + 3 >= view.lines().size()) return false;
        String current = view.lines().get(row);
        int border = current.indexOf('┃');
        if (border < 0 || view.cursorColumn() != border + 3) return false;
        // A multiline draft can leave the last few rows blank; its border extends above them.
        if (view.lines().get(row - 2).indexOf('┃') == border) return false;
        for (int inputRow = row - 1; inputRow <= row + 1; inputRow++) {
            String line = view.lines().get(inputRow);
            if (!line.trim().equals("┃") || line.indexOf('┃') != border) return false;
        }
        String metadata = view.lines().get(row + 2);
        String bottom = view.lines().get(row + 3);
        if (metadata.indexOf('┃') != border
                || !metadata.stripLeading().matches("┃\\s+\\S.* · \\S.*")) return false;
        if (bottom.indexOf('╹') == border && bottom.strip().matches("╹▀+")) return true;
        // Transparent themes paint the bottom border as spaces. The command footer below
        // it supplies the missing lower-boundary evidence; its shortcut can be customized.
        return bottom.isBlank() && row + 4 < view.lines().size()
                && view.lines().get(row + 4).matches(".*\\S+\\s+commands(?:\\s.*)?");
    }

    private static boolean codexHeaderLoading(PromptTerminal.View view) {
        int row = 0;
        while (row < view.lines().size() && view.lines().get(row).isBlank()) row++;
        if (row + 1 >= view.lines().size() || !view.lines().get(row).stripLeading().startsWith("╭")
                || !view.lines().get(row + 1).contains("OpenAI Codex")) return false;
        // Loading is a field in the initial banner box, not a keyword in a model response.
        for (row++; row < view.lines().size(); row++) {
            String line = view.lines().get(row).stripLeading();
            if (!line.startsWith("│")) return false;
            if (line.matches("(?i)│\\s*(?:model|directory):\\s*(?:loading|connecting)\\b.*")) return true;
        }
        return false;
    }

    static boolean currentBusyIndicator(PromptTerminal terminal, PromptTerminal.View view,
                                                String executable) {
        int composer = "cursor-agent".equals(executable) ? cursorComposerRow(view) : view.cursorRow();
        if (composer < 0 || composer >= view.lines().size()) return false;
        // OpenCode 1.18.30 can finish a silent tool while its submitted prompt still occupies the
        // composer-shaped region. During that repaint the command footer appears before the
        // trailing tool card, and the usual "esc interrupt" status is absent. Treat only the
        // complete footer/bottom-border/tool-card layout as busy; identical transcript text above
        // a current composer remains harmless.
        if ("opencode".equals(executable)) {
            for (int footer = composer + 2; footer + 1 < view.lines().size(); footer++) {
                String commandFooter = view.lines().get(footer);
                if (!commandFooter.matches(".*\\S+\\s+commands(?:\\s.*)?")) continue;
                String metadata = view.lines().get(footer - 2);
                String bottom = view.lines().get(footer - 1);
                int border = metadata.indexOf('┃');
                boolean completeComposerEnd = border >= 0
                        && metadata.stripLeading().matches("┃\\s+\\S.* · \\S.*")
                        && (bottom.indexOf('╹') == border && bottom.strip().matches("╹▀+")
                            || bottom.isBlank());
                if (!completeComposerEnd) continue;
                for (int row = footer + 1; row < view.lines().size(); row++) {
                    if (view.lines().get(row).strip().matches("┃\\s*\\(no output\\)")) return true;
                }
            }
        }
        // OpenCode sometimes leaves its cursor on the busy footer during a partial repaint.
        // Require the bottom command bar and adjacent composer metadata, not transcript prose.
        if ("opencode".equals(executable) && composer >= 2 && composer >= view.lines().size() - 2
                && BUSY_HINT.matcher(view.lines().get(composer)).find()
                && view.lines().get(composer).matches(".*\\S+\\s+commands(?:\\s.*)?")
                && view.lines().get(composer - 2).stripLeading().matches("┃\\s+\\S.* · \\S.*")) return true;
        // CLI shortcut/status footers are below the current composer. Earlier response text may
        // explain the same shortcut and must not keep a ready input blocked indefinitely.
        for (int row = composer + 1; row < view.lines().size(); row++) {
            if (BUSY_HINT.matcher(view.lines().get(row)).find()) return true;
        }
        // Claude's teammate spinner is immediately above the composer separator. Its interrupt
        // hint is dim and parenthesized; do not interpret ordinary prose above the input as status.
        for (int row = composer - 1; row >= 0; row--) {
            String line = view.lines().get(row);
            if (line.isBlank() || DECORATION_LINE.matcher(line.trim()).matches()) continue;
            Matcher hint = BUSY_HINT.matcher(line);
            return hint.find() && line.substring(0, hint.start()).contains("(")
                    && terminal.styleAt(row, hint.start()).dim();
        }
        return false;
    }

    static String cursorComposer(PromptTerminal.View view) {
        int row = cursorComposerRow(view);
        return row < 0 ? "" : view.lines().get(row);
    }

    static int cursorComposerRow(PromptTerminal.View view) {
        for (int row = view.lines().size() - 1; row >= 0; row--) {
            if (view.lines().get(row).stripLeading().startsWith("→")) return row;
        }
        return -1;
    }

    private static boolean hermesScreenPrompt(PromptTerminal terminal, PromptTerminal.View view) {
        int row = view.cursorRow();
        if (row < 0 || row >= view.lines().size()) return false;
        String current = view.lines().get(row);
        if (HERMES_PROMPT.matcher(current.trim()).matches()) return emptyPromptAtCursor(current, view.cursorColumn());
        return current.trim().matches("(?:[\\p{L}\\p{N}_.-]+\\s+)?[❯›>]\\s+.+")
                && (placeholderAtCursor(terminal, view, '❯', true)
                    || placeholderAtCursor(terminal, view, '›', true)
                    || placeholderAtCursor(terminal, view, '>', true));
    }

    private static boolean emptyPromptAtCursor(String line, int column) {
        for (char marker : new char[] {'❯', '›', '»', '>'}) {
            int prompt = line.indexOf(marker);
            if (prompt >= 0 && column > prompt && column <= prompt + 2) return true;
        }
        return false;
    }

    private static boolean placeholderAtCursor(PromptTerminal terminal, PromptTerminal.View view,
                                                char marker, boolean italic) {
        String line = view.lines().get(view.cursorRow());
        int prompt = line.indexOf(marker);
        if (prompt < 0) return false;
        int text = prompt + 1;
        while (text < line.length() && Character.isWhitespace(line.charAt(text))) text++;
        if (text == line.length() || view.cursorColumn() <= prompt || view.cursorColumn() > text) return false;
        // Cursor position alone cannot distinguish a placeholder from a draft after Home. Hermes
        // paints its placeholder italic; Codex and Claude use dim text. Claude's focused cursor
        // inverses the first placeholder character while the remaining text stays dim.
        var style = terminal.styleAt(view.cursorRow(), text);
        if (italic) return style.italic();
        return style.dim() || (style.inverse() && text + 1 < line.length()
                && terminal.styleAt(view.cursorRow(), text + 1).dim());
    }

    private static String plainTail(String output) {
        String plain = output.replace('\r', '\n')
                .replaceAll("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)", "")
                .replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "");
        return plain.substring(Math.max(0, plain.length() - 8_000));
    }

    private static long deadlineAfter(long milliseconds) {
        return System.nanoTime() + Duration.ofMillis(milliseconds).toNanos();
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    static final class SubmissionException extends RuntimeException {
        private final boolean inputAttempted;
        private final boolean deferred;

        SubmissionException(String message, boolean inputAttempted) {
            this(message, inputAttempted, null, false);
        }

        SubmissionException(String message, boolean inputAttempted, Throwable cause) {
            this(message, inputAttempted, cause, false);
        }

        private SubmissionException(String message, boolean inputAttempted, Throwable cause, boolean deferred) {
            super(message, cause);
            this.inputAttempted = inputAttempted;
            this.deferred = deferred;
        }

        static SubmissionException deferred(String message) {
            return new SubmissionException(message, false, null, true);
        }

        boolean deferred() { return deferred; }

        boolean inputAttempted() {
            return inputAttempted;
        }
    }
}
