package dev.termestra.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.adapter.out.terminal.VtPromptTerminal;
import dev.termestra.execution.adapter.out.pty.Pty4jProcessLauncher;
import dev.termestra.execution.application.port.out.ProcessLaunchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.termestra.execution.application.service.InteractiveOutputTail.State.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenCodeInputRecognitionTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest @ValueSource(ints = {1, 79, 4096})
    void recognizesTheRecordedEmptyComposerAfterTheFirstMessage(int chunkSize) throws Exception {
        var output = output();
        String frame = fixture("opencode-followup");
        for (int offset = 0; offset < frame.length(); offset += chunkSize) {
            output.append(frame.substring(offset, Math.min(frame.length(), offset + chunkSize)));
        }
        assertEquals(READY, output.snapshot().readiness().state());
    }

    @Test void deliversStartupAndConsecutiveDispatchesAfterThePlaceholderDisappears() throws Exception {
        var output = output();
        output.append(fixture("opencode"));
        String followup = fixture("opencode-followup");
        List<String> writes = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (var mailbox = new AutomaticInputMailbox("opencode-regression", (text, position) ->
                    InteractiveInputSubmitter.submit("opencode", text, () -> true, output::snapshot, bytes -> {
                        String value = new String(bytes, StandardCharsets.UTF_8);
                        writes.add(value);
                        if (value.equals("\r")) output.append(followup);
                    }, position))) {
                mailbox.submit("startup");
                mailbox.submit("first dispatch");
                mailbox.submit("second dispatch");
            }
        });
        assertEquals(List.of("\u001b[200~startup\u001b[201~", "\r",
                "\u001b[200~first dispatch\u001b[201~", "\r",
                "\u001b[200~second dispatch\u001b[201~", "\r"), writes);
    }

    @Test @EnabledOnOs(OS.MAC)
    void deliversConsecutiveMessagesAcrossARealPty() throws Exception {
        Path frame = temporaryDirectory.resolve("followup.ansi");
        Path received = temporaryDirectory.resolve("received.txt");
        Files.writeString(frame, fixture("opencode-followup"));
        String script = "stty -echo; printf 'Ask anything...\\r'; "
                + "while IFS= read -r line; do printf '%s\\n' \"$line\" >> \"$1\"; cat \"$2\"; done";
        var pty = new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh", "-c", script, "opencode-fixture", received.toString(), frame.toString()),
                temporaryDirectory.toString(), Map.of(), 80, 24));
        var output = output();
        var decoder = new IncrementalUtf8Decoder();
        try {
            pty.activate(bytes -> output.append(decoder.decode(bytes)), ignored -> { });
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                try (var mailbox = new AutomaticInputMailbox("opencode-pty-regression", (text, position) ->
                        InteractiveInputSubmitter.submit("opencode", text, pty::alive,
                                output::snapshot, pty::write, position))) {
                    mailbox.submit("startup");
                    mailbox.submit("first dispatch");
                    long accepted = mailbox.submit("second dispatch");
                    // Await the final repaint so the fixture has consumed the final Enter too.
                    while (output.snapshot().readiness().position() <= accepted) Thread.sleep(10);
                }
            });
            assertEquals(List.of("\u001b[200~startup\u001b[201~",
                    "\u001b[200~first dispatch\u001b[201~", "\u001b[200~second dispatch\u001b[201~"),
                    Files.readAllLines(received));
        } finally {
            assertTrue(pty.stopAndConfirm(), "fixture process must be cleaned up");
        }
    }

    @Test void rejectsDraftsEvenWhenTheCursorIsOnAnEmptyLineOrMovedHome() throws Exception {
        for (int draftRow : List.of(18, 19, 20)) {
            var output = output();
            output.append(fixture("opencode-followup"));
            output.invalidateForUserInput();
            output.append("\u001b[" + draftRow + ";6Hunfinished draft\u001b[19;6H");
            assertEquals(INITIALIZING, output.snapshot().readiness().state(), "draft row " + draftRow);
        }
        var extended = output();
        extended.append(fixture("opencode-followup"));
        extended.append("\u001b[17;1H  ┃  earlier draft\u001b[19;6H");
        assertEquals(INITIALIZING, extended.snapshot().readiness().state(),
                "an expanded multiline draft can extend above the three empty rows near the cursor");
    }

    @Test void rejectsBusyShellAndDialogScreensAndCursorsOutsideTheInputStart() throws Exception {
        String frame = fixture("opencode-followup");
        for (String mutation : List.of(
                "\u001b[23;1H\u001b[2K  esc interrupt\u001b[19;6H",
                "\u001b[23;1H\u001b[2K  esc again to interrupt\u001b[19;6H",
                "\u001b[21;1H\u001b[2K  ┃  Shell mode\u001b[19;6H",
                "\u001b[19;3H", "\u001b[19;7H", "\u001b[18;6H", "\u001b[20;6H",
                "\u001b[5;1HAllow tool execution?\r\nAllow once")) {
            var output = output();
            output.append(frame);
            output.append(mutation);
            assertEquals(INITIALIZING, output.snapshot().readiness().state(), mutation);
        }
        var blank = output();
        blank.append("\u001b[19;6H");
        assertEquals(INITIALIZING, blank.snapshot().readiness().state());
    }

    @Test void requiresACompleteComposerAndSupportsCustomAgentAndModelNames() throws Exception {
        String frame = fixture("opencode-followup");
        var custom = output();
        custom.append(frame.replace("Build · Example Model Provider", "Reviewer · Other Model Vendor"));
        assertEquals(READY, custom.snapshot().readiness().state());
        for (int removedRow : List.of(18, 21, 22)) {
            var output = output();
            output.append(frame);
            output.append("\u001b[" + removedRow + ";1H\u001b[2K\u001b[19;6H");
            assertEquals(INITIALIZING, output.snapshot().readiness().state(), "missing row " + removedRow);
        }
    }

    @Test void requiresFreshComposerPaintAfterManualInputOrAnAcceptedDispatch() throws Exception {
        var output = output();
        output.append(fixture("opencode-followup"));
        long accepted = output.snapshot().readiness().position();
        output.append("\u001b]0;Updated title\u0007\u001b[23;70H1\u001b[19;6H");
        assertEquals(accepted, output.snapshot().readiness().position());
        output.invalidateForUserInput();
        output.append("\u001b[19;6H\u001b]0;Another title\u0007");
        assertEquals(INITIALIZING, output.snapshot().readiness().state());
        output.append("\u001b[19;1H\u001b[2K  ┃\u001b[19;6H");
        assertEquals(READY, output.snapshot().readiness().state());
        assertTrue(output.snapshot().readiness().position() > accepted);
    }

    private static InteractiveOutputTail output() {
        return new InteractiveOutputTail("opencode", new VtPromptTerminal());
    }

    private static String fixture(String name) throws Exception {
        try (var input = OpenCodeInputRecognitionTest.class.getResourceAsStream("/terminal/startup/" + name + ".json")) {
            assertNotNull(input);
            return new ObjectMapper().readValue(input, String.class);
        }
    }
}
