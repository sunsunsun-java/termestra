package dev.termestra.execution.adapter.out.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VtPromptTerminalTest {
    @ParameterizedTest
    @CsvSource(value = {"claude|Yes, I trust this folder", "codex|Do you trust the contents of this directory?",
            "agy|Do you trust the contents of this project?", "cursor|Plan, search, build anything",
            "opencode|Ask anything...", "pi|Update Available", "hermes|❯ Summarize what's in this folder"}, delimiter = '|')
    void replaysRealStartupOutputWithVisibleWordSpacing(String cli, String expected) throws Exception {
        String output = fixture(cli);
        VtPromptTerminal whole = new VtPromptTerminal();
        whole.write(output);
        VtPromptTerminal chunked = new VtPromptTerminal();
        output.codePoints().forEach(codePoint -> chunked.write(new String(Character.toChars(codePoint))));

        assertEquals(whole.view(), chunked.view(), "control sequences can cross output boundaries");
        assertTrue(String.join("\n", whole.view().lines()).contains(expected), whole.view().toString());
        assertEquals(24, whole.view().lines().size());
    }

    @Test void keepsVisibleCellsAndCursorWithoutRetainingOldScreens() {
        VtPromptTerminal terminal = new VtPromptTerminal();
        terminal.resize(20, 3);
        terminal.write("old\r\na\r\nb\r\nc\033[2J\033[1;1HDo\033[1Cyou\033[1Ctrust?\033[?2004h");
        assertEquals("Do you trust?", terminal.view().lines().getFirst());
        assertEquals(0, terminal.view().cursorRow());
        assertEquals(13, terminal.view().cursorColumn());
        assertFalse(String.join("\n", terminal.view().lines()).contains("old"));
    }

    @Test void observesSamePromptRepaintsWithoutTreatingTitlesAsInputRepaints() {
        VtPromptTerminal terminal = new VtPromptTerminal();
        terminal.write("status\r\n❯ Summarize what's in this folder\r\033[2C");
        var original = terminal.view();

        terminal.write("\r\033[2K❯ Summarize what's in this folder\r\033[2C");
        var repainted = terminal.view();
        assertEquals(original.lines(), repainted.lines());
        assertEquals(original.cursorRow(), repainted.cursorRow());
        assertEquals(original.cursorColumn(), repainted.cursorColumn());
        assertTrue(repainted.lineRevisions().get(1) > original.lineRevisions().get(1));
        assertEquals(original.lineRevisions().getFirst(), repainted.lineRevisions().getFirst());

        terminal.write("\033]0;updated title\007\033]2;another title\033\\");
        assertEquals(repainted, terminal.view());

        terminal.write("\r❯ Summarize what's in this folder\r\033[2C");
        var overwritten = terminal.view();
        assertEquals(repainted.lines(), overwritten.lines());
        assertTrue(overwritten.lineRevisions().get(1) > repainted.lineRevisions().get(1));

        terminal.write("\033cstatus\r\n❯ Summarize what's in this folder\r\033[2C");
        assertEquals(overwritten.lines(), terminal.view().lines());
        assertTrue(terminal.view().lineRevisions().get(1) > overwritten.lineRevisions().get(1));
    }

    @Test void observesBlankInputRepaintAndKeepsRevisionsBoundedWhenRowsMove() {
        VtPromptTerminal terminal = new VtPromptTerminal();
        terminal.resize(20, 3);
        terminal.write("top\r\n\r\nbottom\033[2;1H");
        var original = terminal.view();
        terminal.write("\033[2K");
        assertEquals(original.lines(), terminal.view().lines());
        assertTrue(terminal.view().lineRevisions().get(1) > original.lineRevisions().get(1));

        var beforeScroll = terminal.view();
        terminal.write("\033[3;1H\n");
        assertEquals(3, terminal.view().lineRevisions().size());
        for (int row = 0; row < 3; row++) {
            assertTrue(terminal.view().lineRevisions().get(row) > beforeScroll.lineRevisions().get(row));
        }
        terminal.resize(10, 2);
        assertEquals(2, terminal.view().lineRevisions().size());
        assertEquals(terminal.view().lines().size(), terminal.view().lineRevisions().size());
    }

    @Test void exposesHermesAndCodexPlaceholderStylingFromRealScreens() throws Exception {
        VtPromptTerminal hermes = new VtPromptTerminal();
        hermes.write(fixture("hermes"));
        assertTrue(hermes.styleAt(hermes.view().cursorRow(), hermes.view().cursorColumn()).italic());

        String codex = fixture("codex");
        int placeholder = codex.indexOf("Ask Codex to do anything");
        int firstFrameEnd = codex.indexOf("\033[?2026l", placeholder) + "\033[?2026l".length();
        VtPromptTerminal terminal = new VtPromptTerminal();
        terminal.write(codex.substring(0, firstFrameEnd));
        assertTrue(terminal.styleAt(terminal.view().cursorRow(), terminal.view().cursorColumn()).dim());
    }

    private String fixture(String cli) throws Exception {
        try (var stream = getClass().getResourceAsStream("/terminal/startup/" + cli + ".json")) {
            assertNotNull(stream);
            return new ObjectMapper().readValue(new String(stream.readAllBytes(), StandardCharsets.UTF_8), String.class);
        }
    }
}
