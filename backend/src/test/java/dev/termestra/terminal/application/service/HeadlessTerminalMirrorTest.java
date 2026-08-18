package dev.termestra.terminal.application.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HeadlessTerminalMirrorTest {
    @Test void mirrorsCarriageReturnAndEraseToEndOfLine() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(80, 24);

        mirror.write("progress 10%\rprogress 90%\033[K");

        assertEquals("progress 90%", mirror.screenText());
        assertEquals("progress 90%", mirror.lastPtyLine(200));
    }

    @Test void keepsParserStateAcrossOutputChunks() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(80, 24);
        mirror.write("old value\rnew");
        mirror.write("\033[");
        mirror.write("K");

        assertEquals("new", mirror.screenText());
    }

    @Test void appliesCursorAddressingAndWideCells() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(12, 3);
        mirror.write("abcdef\r\nsecond");
        mirror.write("\033[1;1H你好");

        assertEquals("你好ef\r\nsecond", mirror.screenText());
    }

    @Test void preservesUtf8CharactersSplitAcrossOutputChunks() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(12, 3);
        byte[] value = "终端".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mirror.write(java.util.Arrays.copyOfRange(value, 0, 2));
        mirror.write(java.util.Arrays.copyOfRange(value, 2, value.length));
        assertEquals("终端", mirror.screenText());
    }

    @Test void evictsOldestRowsWhenTheBoundedScrollbackIsFull() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(10, 1, 2);

        mirror.write("one\r\ntwo\r\nthree\r\nfour");

        assertEquals("two\r\nthree\r\nfour", mirror.screenText());
    }

    @Test void clampsHostileDimensionsAndCursorParameters() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(1_000_000, 1_000_000, 5);
            mirror.write("x".repeat(501));
            mirror.write("\033[999999999Bsafe");

            assertEquals("safe", mirror.lastPtyLine(20));
        });
    }

    @Test void discardsAnOversizedControlSequenceWithoutRetainingIt() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(20, 2, 5);

            mirror.write("\033[" + "9".repeat(10_000) + "mvisible");

            assertEquals("visible", mirror.screenText());
        });
    }

    @Test void defersWrappingUntilTheNextPrintableCharacter() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(4, 2, 0);

        mirror.write("abcd\r\nX");

        assertEquals("abcd\r\nX", mirror.screenText());
    }

    @Test void restoresThePrimaryScreenAfterLeavingTheAlternateScreen() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(20, 3, 0);

        mirror.write("main\033[?1049halt\033[?1049l");

        assertEquals("main", mirror.screenText());
    }

    @Test void preservesSgrAttributesInTheRestoreSnapshot() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(20, 3, 0);

        mirror.write("plain \033[31;1mred\033[0m tail");

        assertEquals("plain \033[31;1mred\033[0m tail", mirror.screenText());
    }

    @Test void scrollsOnlyInsideTheConfiguredScrollRegion() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(10, 4, 0);
        mirror.write("one\r\ntwo\r\nthree\r\nfour");

        mirror.write("\033[2;3r\033[3;1H\nX");

        assertEquals("one\r\nthree\r\nX\r\nfour", mirror.screenText());
    }

    @Test void checkpointRestoresAlternateBufferModesAndFinalCursor() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(20, 4, 0);
        mirror.write("primary\033[?1049hsecondary\033[?2004h\033[3;7H");

        String checkpoint = mirror.snapshot();

        org.junit.jupiter.api.Assertions.assertTrue(checkpoint.startsWith("\033cprimary"));
        org.junit.jupiter.api.Assertions.assertTrue(checkpoint.contains("\033[?1049hsecondary"));
        org.junit.jupiter.api.Assertions.assertTrue(checkpoint.contains("\033[?2004h"));
        org.junit.jupiter.api.Assertions.assertTrue(checkpoint.endsWith("\033[3;7H"));
    }

    @Test void checkpointReplaysIntoAFreshMirrorWithBuffersCursorStyleAndPendingWrap() {
        HeadlessTerminalMirror original = new HeadlessTerminalMirror(8, 3, 0);
        original.write("primary\0337\033[?1049h\033[31malt\033[2;8H!");
        HeadlessTerminalMirror restored = new HeadlessTerminalMirror(8, 3, 0);

        restored.write(original.snapshot());
        assertEquals(original.screenText(), restored.screenText());

        original.write("X\033[?1049l\0338Z");
        restored.write("X\033[?1049l\0338Z");
        assertEquals(original.screenText(), restored.screenText());
    }

    @Test void checkpointKeepsScrollbackOutOfTrailingBlankViewportRows() {
        HeadlessTerminalMirror original = new HeadlessTerminalMirror(8, 3, 10);
        original.write("history\r\none\r\ntwo\r\nthree");
        original.write("\033[2J\033[1;1Hlive");
        HeadlessTerminalMirror restored = new HeadlessTerminalMirror(8, 3, 10);

        restored.write(original.snapshot());
        original.write("!\r\nnext\r\nlast\r\noverflow");
        restored.write("!\r\nnext\r\nlast\r\noverflow");

        assertEquals(original.screenText(), restored.screenText());
    }

    @Test void hostileStyleChurnFallsBackToABoundedReconnectSnapshot() throws Exception {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(400, 150, 1_000);
        for (int row = 0; row < 1_200; row++) {
            for (int column = 0; column < 400; column++) {
                mirror.write("\033[" + (30 + column % 8) + ";" + (40 + row % 8) + "mX");
            }
            mirror.write("\r\n");
        }

        String snapshot = assertTimeoutPreemptively(Duration.ofSeconds(3), mirror::snapshot);
        byte[] controlFrame = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                Map.of("type", "restore", "snapshot", snapshot));

        assertTrue(controlFrame.length < 1024 * 1024,
                "restore snapshot must fit the configured websocket frame");
        HeadlessTerminalMirror restored = new HeadlessTerminalMirror(400, 150, 1_000);
        restored.write(snapshot);
        assertEquals(mirror.lastPtyLine(400), restored.lastPtyLine(400));
    }

    @Test void arbitraryPrivateModesCannotAmplifyReconnectSnapshots() {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(80, 24, 10);
        for (int mode = 10_000; mode < 30_000; mode++) {
            mirror.write("\033[?" + mode + "h");
        }

        String snapshot = mirror.snapshot();

        assertTrue(snapshot.length() < 8_192);
        assertFalse(snapshot.contains("?29999"));
    }
}
