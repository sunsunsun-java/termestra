package dev.termestra.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.adapter.out.terminal.VtPromptTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.termestra.execution.application.service.InteractiveOutputTail.State.*;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveStartupRecognitionTest {
    @ParameterizedTest @ValueSource(strings = {"›", "»"})
    void recognizesCodexComposerFromReportedStartupScreen(String marker) {
        var output = output("codex");
        output.append(marker + " \u001b[2mAsk Codex to do anything\u001b[0m\r\u001b[3G");
        assertEquals(READY, output.snapshot().readiness().state());
        output.append("\r\u001b[2K" + marker + " ");
        assertEquals(READY, output.snapshot().readiness().state());
    }

    @ParameterizedTest @ValueSource(strings = {"›", "»"})
    void codexComposerStillRequiresAnEmptyInputAtTheCursor(String marker) {
        var output = output("codex");
        output.append(marker + " \u001b[2mAsk Codex to do anything\u001b[0m\r\u001b[3G");
        output.invalidateForUserInput();
        output.append("\r\u001b[2K" + marker + " Ask Codex to do anything\r\u001b[3G");
        assertEquals(INITIALIZING, output.snapshot().readiness().state(), "ordinary text is a draft");
        output.append("\r\u001b[2K" + marker + " \u001b[2mAsk Codex to do anything\u001b[0m\r\u001b[3G");
        assertEquals(READY, output.snapshot().readiness().state(), "placeholder repaint clears the draft");
        output.append("\u001b[1G");
        assertEquals(INITIALIZING, output.snapshot().readiness().state(), "cursor on prompt glyph");
        output.append("\u001b[40G");
        assertEquals(INITIALIZING, output.snapshot().readiness().state(), "cursor outside input start");
        output.append("\u001b[3G\r\n  esc to interrupt\u001b[1A\u001b[3G");
        assertEquals(INITIALIZING, output.snapshot().readiness().state(), "busy footer");
    }

    @ParameterizedTest @ValueSource(strings = {"hermes", "claude", "codex", "agy", "cursor-agent", "opencode"})
    void quotedBusyHelpAboveTheComposerDoesNotBlockInput(String command) {
        var output = output(command);
        String composer = switch (command) {
            case "agy" -> "> \r\n────────────────────────\u001b[1A\u001b[3G";
            case "cursor-agent" -> "  → Add a follow-up";
            case "opencode" -> "Ask anything...\r";
            case "codex" -> "› ";
            default -> "❯ ";
        };
        output.append("The documentation says: esc to interrupt\r\n" + composer);
        assertEquals(READY, output.snapshot().readiness().state());
    }

    @Test void quotedLoadingTextIsNotTheCurrentCodexStartupHeader() {
        var output = output("codex");
        output.append("The screenshot contains model: loading and directory: connecting\r\n› ");
        assertEquals(READY, output.snapshot().readiness().state());
    }

    @Test void aCurrentBusyFooterOrStyledSpinnerStillBlocksAnEmptyComposer() {
        var opencode = output("opencode");
        opencode.append("Ask anything...\r\n\r\n  esc interrupt\u001b[2A\r");
        assertEquals(INITIALIZING, opencode.snapshot().readiness().state());
        var claude = output("claude");
        claude.append("Thinking (\u001b[2mesc to interrupt teammate\u001b[0m)\r\n────────────────────────\r\n❯ ");
        assertEquals(INITIALIZING, claude.snapshot().readiness().state());
    }

    @ParameterizedTest @ValueSource(strings = {"hermes", "claude", "codex"})
    void homeAndComposerRepaintCannotSubmitIntoAnExistingDraft(String command) throws Exception {
        String marker = command.equals("codex") ? "›" : "❯";
        var output = output(command);
        output.append(marker + " ");
        output.invalidateForUserInput();
        output.append("\r\u001b[2K" + marker + " my unfinished draft");
        output.invalidateForUserInput();
        output.append("\r\u001b[2K" + marker + " my unfinished draft\r\u001b[3G");
        AtomicBoolean active = new AtomicBoolean(true);
        List<byte[]> writes = new CopyOnWriteArrayList<>();
        var submitted = CompletableFuture.runAsync(() -> InteractiveInputSubmitter.submitStartup(command, "automatic",
                active::get, output::snapshot, writes::add, ignored -> { }));
        try {
            Thread.sleep(500);
            assertEquals(INITIALIZING, output.snapshot().readiness().state());
            assertTrue(writes.isEmpty(), "must not paste into the user's draft");
            assertFalse(submitted.isDone());
        } finally { active.set(false); }
        assertThrows(java.util.concurrent.ExecutionException.class, () -> submitted.get(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest @ValueSource(strings = {"hermes", "claude", "codex"})
    void quotedSetupInstructionsAboveTheComposerDoNotBecomeAnActiveDialog(String command) {
        String marker = command.equals("codex") ? "›" : "❯";
        for (String text : List.of("The warning says: do you trust this directory?",
                "Documentation: Please sign in to the service.",
                "The help text says: press enter to continue.")) {
            var output = output(command);
            output.append(text + "\r\n" + marker + " ");
            assertEquals(READY, output.snapshot().readiness().state(), text);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"hermes", "claude", "codex"})
    void recognizesPlaceholderStyleWithoutAcceptingTheSameTextAsADraft(String command) {
        String marker = command.equals("codex") ? "›" : "❯";
        String style = command.equals("hermes") ? "\u001b[3m" : "\u001b[2m";
        var output = output(command);
        output.append(marker + " " + style + "A changing suggestion\u001b[0m\r\u001b[3G");
        assertEquals(READY, output.snapshot().readiness().state());
        output.invalidateForUserInput();
        output.append("\r\u001b[2K" + marker + " A changing suggestion\r\u001b[3G");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("\r\u001b[2K" + marker + " " + style + "Another suggestion\u001b[0m\r\u001b[3G");
        assertEquals(READY, output.snapshot().readiness().state());
    }

    @Test void recognizesTheFocusedClaudePlaceholderButNotAnInvertedDraftCursor() {
        var output = output("claude");
        output.append("❯ \u001b[7mT\u001b[27;2mry a suggestion\u001b[0m\r\u001b[3G");
        assertEquals(READY, output.snapshot().readiness().state());
        output.append("\r\u001b[2K❯ \u001b[7mT\u001b[27myped draft\r\u001b[3G");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
    }

    @Test void aCompleteQuotedTrustPageDoesNotOverrideTheCurrentComposer() throws Exception {
        for (String command : List.of("claude", "codex", "agy")) {
            var output = output(command);
            output.append(fixture(command));
            assertEquals(WAITING_FOR_USER, output.snapshot().readiness().state());
            // A resumed transcript can retain the complete old dialog above the live composer.
            output.append("\u001b[22;1H\u001b[2K" + (command.equals("codex") ? "› " : command.equals("agy") ? "> " : "❯ "));
            if (command.equals("agy")) output.append("\r\n────────────────────────\u001b[1A\u001b[3G");
            assertEquals(READY, output.snapshot().readiness().state(), command);
        }
    }

    @Test void aLoginPageWithAnUnknownInputFieldCannotReleaseStartupInput() {
        var output = output("hermes");
        output.append("Please sign in to continue\r\nVerification code: ");
        assertEquals(WAITING_FOR_USER, output.snapshot().readiness().state());
        List<byte[]> writes = new CopyOnWriteArrayList<>();
        assertThrows(InteractiveInputSubmitter.SubmissionException.class, () ->
                InteractiveInputSubmitter.submit("hermes", "automatic", () -> true, output::snapshot, writes::add));
        assertTrue(writes.isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {1, 79, 4096})
    void replaysRealCliStartupScreensAtDifferentOutputBoundaries(int chunkSize) throws Exception {
        for (String cli : List.of("claude", "codex", "codex-double-chevron", "agy", "cursor", "opencode", "pi", "hermes")) {
            String command = cli.equals("cursor") ? "cursor-agent" : cli.startsWith("codex") ? "codex" : cli;
            InteractiveOutputTail output = output(command);
            String raw = fixture(cli);
            for (int offset = 0; offset < raw.length(); offset += chunkSize) {
                output.append(raw.substring(offset, Math.min(raw.length(), offset + chunkSize)));
            }
            assertEquals(List.of("claude", "codex", "agy").contains(cli) ? WAITING_FOR_USER : READY,
                    output.snapshot().readiness().state(), cli + " at chunk size " + chunkSize);
            assertTrue(output.snapshot().tail().length() <= InteractiveOutputTail.MAX_CHARS);
        }
    }

    @Test void recognizesHermesPlaceholderButDoesNotSubmitOverTypedUserInput() {
        var output = output("hermes");
        output.append("\u001b[?2004h\u001b[36m❯ \u001b[33;3mSummarize what's in this folder\u001b[0m"
                + "\r\n────────────────────────\u001b[1A\u001b[3G");
        assertEquals(READY, output.snapshot().readiness().state());
        output.append("\r\u001b[2K❯ my unfinished message");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
    }

    @Test void observesPiIdentityBeforeItScrollsAwayAndRequiresCurrentEmptyInput() throws Exception {
        var output = output("pi");
        output.append(fixture("pi"));
        assertFalse(output.snapshot().tail().contains("pi v0.84.3"));
        assertEquals(READY, output.snapshot().readiness().state());
        output.append("working on a response");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("\r\u001b[2K");
        assertEquals(READY, output.snapshot().readiness().state());
    }

    @Test void manualInputInvalidatesReadinessUntilTheInputAreaChanges() {
        var output = output("hermes");
        output.append("❯ ");
        assertEquals(READY, output.snapshot().readiness().state());
        output.invalidateForUserInput();
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("\u001b]0;Unrelated title\u0007\u001b[2G");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("my message");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("\r\u001b[2K❯ ");
        assertEquals(READY, output.snapshot().readiness().state());
    }

    @Test void cursorManualInputTracksTheComposerInsteadOfItsHiddenStatusCursor() throws Exception {
        var output = output("cursor-agent");
        output.append(fixture("cursor"));
        output.invalidateForUserInput();
        output.append("\u001b[10;1H\u001b[2K  → user text\u001b[15;1H");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("\u001b[10;1H\u001b[2K  → Plan, search, build anything\u001b[15;1H");
        assertEquals(READY, output.snapshot().readiness().state());
    }

    @Test void aSameCallbackPromptRepaintClearsManualInvalidationAndCountsAsFreshOutput() {
        for (String command : List.of("hermes", "claude", "codex")) {
            var output = output(command);
            output.append("❯ ");
            long accepted = output.snapshot().position();
            output.invalidateForUserInput();
            output.append("\r\u001b[2K❯ ");
            assertEquals(READY, output.snapshot().readiness().state(), command);
            assertTrue(output.snapshot().readiness().position() > accepted, command);
            long firstRepaint = output.snapshot().position();
            output.append("\r\u001b[2K❯ ");
            assertTrue(output.snapshot().readiness().position() > firstRepaint,
                    command + " must provide fresh evidence for a subsequent queued input");
        }
    }

    @Test void cursorComposerRepaintCountsAsFreshWhileStatusRepaintDoesNot() throws Exception {
        var output = output("cursor-agent");
        output.append(fixture("cursor"));
        long accepted = output.snapshot().position();
        output.invalidateForUserInput();
        output.append("\u001b[15;1H\u001b[2KUpdated status\u001b[15;1H");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("\u001b[10;1H\u001b[2K  → Plan, search, build anything\u001b[15;1H");
        assertEquals(READY, output.snapshot().readiness().state());
        assertTrue(output.snapshot().readiness().position() > accepted);
    }

    @Test void doesNotTreatUnrelatedOutputAsANewPrompt() {
        var output = output("hermes");
        output.append("❯ ");
        long accepted = output.snapshot().readiness().position();
        output.append("\u001b]0;A different terminal title\u0007");
        assertEquals(accepted, output.snapshot().readiness().position());
    }

    @Test void letsTheUserCompleteSetupBeforeSubmittingExactlyOnce() throws Exception {
        var output = output("claude");
        output.append(fixture("claude"));
        List<String> writes = new CopyOnWriteArrayList<>();
        List<String> phases = new CopyOnWriteArrayList<>();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean(true);
        var submitted = CompletableFuture.runAsync(() -> InteractiveInputSubmitter.submitStartup("claude", "hello",
                active::get, output::snapshot, bytes -> {
                    writes.add(new String(bytes, StandardCharsets.UTF_8));
                    output.append("[Pasted text #1 +1 lines]");
                }, reason -> {
                    phases.add(reason == null ? "initializing" : reason);
                    if (reason != null) waiting.countDown();
                }));
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertTrue(writes.isEmpty());
            output.append("\u001b[2J\u001b[H❯ ");
            submitted.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("\u001b[200~hello\u001b[201~", "\r"), writes);
            assertEquals(2, phases.size());
            assertEquals("initializing", phases.get(1));
        } finally { active.set(false); }
    }

    @Test void noLongerTreatsElapsedTimeWithoutPromptEvidenceAsSuccess() throws Exception {
        var output = output("codex");
        output.append("Loading model and configuration");
        AtomicBoolean active = new AtomicBoolean(true);
        List<byte[]> writes = new CopyOnWriteArrayList<>();
        var submitted = CompletableFuture.runAsync(() -> InteractiveInputSubmitter.submitStartup("codex", "hello",
                active::get, output::snapshot, writes::add, ignored -> { }));
        try {
            Thread.sleep(3_300);
            assertFalse(submitted.isDone());
            assertTrue(writes.isEmpty());
        } finally { active.set(false); }
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> submitted.get(1, TimeUnit.SECONDS));
        assertFalse(((InteractiveInputSubmitter.SubmissionException) failure.getCause()).inputAttempted());
    }

    @Test void agyUsesOnlyItsCurrentComposerAndRejectsHistoricalEmptyPrompts() {
        var output = output("agy");
        output.append("> \r\n────────────────────────\u001b[1A\u001b[3G");
        assertEquals(READY, output.snapshot().readiness().state());
        output.append("\u001b[4;1Hworking on a response");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("\u001b[1;3Hmy unfinished message");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
    }

    @Test void emptyPromptRequiresTheCursorAtTheInputStart() {
        for (String command : List.of("claude", "codex", "hermes")) {
            var output = output(command);
            output.append("❯ ");
            assertEquals(READY, output.snapshot().readiness().state(), command);
            output.append("\u001b[1G");
            assertEquals(INITIALIZING, output.snapshot().readiness().state(), command + " on prompt glyph");
            output.append("\u001b[40G");
            assertEquals(INITIALIZING, output.snapshot().readiness().state(), command + " away from input");
            output.append("\u001b[3G");
            assertEquals(READY, output.snapshot().readiness().state(), command + " back at input start");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"›", "»"})
    void aLoadingCodexComposerCannotReleaseInputEvenAfterTheStabilityWindow(String marker) throws Exception {
        var output = output("codex");
        String realStartup = fixture("codex").replace("›", marker);
        int placeholder = realStartup.indexOf("Ask Codex to do anything");
        int initialFrameEnd = realStartup.indexOf("\u001b[?2026l", placeholder) + "\u001b[?2026l".length();
        output.append(realStartup.substring(0, initialFrameEnd));
        AtomicBoolean active = new AtomicBoolean(true);
        List<byte[]> writes = new CopyOnWriteArrayList<>();
        var submitted = CompletableFuture.runAsync(() -> InteractiveInputSubmitter.submitStartup("codex", "hello",
                active::get, output::snapshot, writes::add, ignored -> { }));
        try {
            Thread.sleep(500);
            assertEquals(INITIALIZING, output.snapshot().readiness().state());
            assertFalse(submitted.isDone());
            assertTrue(writes.isEmpty());
        } finally { active.set(false); }
        assertThrows(java.util.concurrent.ExecutionException.class, () -> submitted.get(1, TimeUnit.SECONDS));
    }

    @Test void ignoresHistoricalPlaceholdersWhenTheCurrentComposerIsBusyOrContainsInput() throws Exception {
        var cursor = output("cursor-agent");
        cursor.append(fixture("cursor"));
        cursor.append("\u001b[17;1H  → my unfinished question");
        assertEquals(INITIALIZING, cursor.snapshot().readiness().state());
        var opencode = output("opencode");
        opencode.append(fixture("opencode"));
        opencode.append("\u001b[18;1Hesc interrupt");
        assertEquals(INITIALIZING, opencode.snapshot().readiness().state());
    }

    @Test void aTransientComposerBeforeATrustPageDoesNotReleaseStartupInput() throws Exception {
        var output = output("codex");
        output.append("› \u001b[2mAsk Codex to do anything\u001b[0m\r\u001b[3G");
        assertEquals(READY, output.snapshot().readiness().state());
        List<byte[]> writes = new CopyOnWriteArrayList<>();
        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch waiting = new CountDownLatch(1);
        var submitted = CompletableFuture.runAsync(() -> InteractiveInputSubmitter.submitStartup("codex", "hello",
                active::get, output::snapshot, writes::add, reason -> {
                    if (reason != null) waiting.countDown();
                }));
        try {
            Thread.sleep(100);
            output.append("\u001b[2J\u001b[HDo you trust this directory?\r\n› 1. Yes, continue");
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            assertTrue(writes.isEmpty());
        } finally { active.set(false); }
        assertThrows(java.util.concurrent.ExecutionException.class, () -> submitted.get(1, TimeUnit.SECONDS));
    }

    @Test void setupAlwaysWinsOverAPromptShapedMenuArrow() {
        var output = output("hermes");
        // A real setup page has a selection, not an indistinguishable empty chat composer.
        output.append("Do you trust this directory?\r\n❯ 1. Yes, continue\r\nEnter to confirm");
        assertEquals(WAITING_FOR_USER, output.snapshot().readiness().state());
        List<byte[]> writes = new CopyOnWriteArrayList<>();
        var failure = assertThrows(InteractiveInputSubmitter.SubmissionException.class,
                () -> InteractiveInputSubmitter.submit("hermes", "hello", () -> true, output::snapshot, writes::add));
        assertFalse(failure.inputAttempted());
        assertTrue(writes.isEmpty());
    }

    private static InteractiveOutputTail output(String command) {
        return new InteractiveOutputTail(command, new VtPromptTerminal());
    }

    private static String fixture(String cli) throws Exception {
        try (var input = InteractiveStartupRecognitionTest.class.getResourceAsStream("/terminal/startup/" + cli + ".json")) {
            return new ObjectMapper().readValue(input, String.class);
        }
    }
}
