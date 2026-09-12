package dev.termestra.platform.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the installed browser dependencies' parsers without launching a GUI. */
class HeadlessTerminalMirrorXtermParityTest {
    @TempDir Path temporary;

    @Test void restoreAndLiveAgreeWithXtermForResizeUnicodeAndPartialSequences() throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(scenario("shrink below cursor", 20, 4, "hello", List.of(new int[]{20, 2}), "!"));
        cases.add(scenario("shrink at bottom", 20, 4, "one\r\ntwo\r\nthree\r\nfour", List.of(new int[]{20, 2}), "!"));
        cases.add(scenario("grow back into history", 20, 4, "one\r\ntwo\r\nthree\r\nfour",
                List.of(new int[]{20, 2}, new int[]{20, 4}), "!"));
        cases.add(scenario("alternate shrink", 20, 4, "\033[?1049hhello", List.of(new int[]{20, 2}), "!"));
        for (String text : List.of("e\u0301X", "你\u0301X", "a❤\ufe0f", "abc\u0301")) {
            cases.add(scenario("combining " + text, 3, 3, text, List.of(), "!"));
        }
        cases.add(scenario("combining with autowrap disabled", 3, 3,
                "\033[?7labX\u0301", List.of(), "!"));
        cases.add(scenario("split ESC", 20, 4, "hello\033", List.of(), "[31mX"));
        cases.add(scenario("split CSI", 20, 4, "hello\033[31", List.of(), "mX"));
        cases.add(scenario("split OSC", 20, 4, "hello\033]0;", List.of(), "window title\007X"));
        cases.add(scenario("split OSC terminator", 20, 4, "hello\033]0;title\033", List.of(), "\\X"));

        Path repository = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(repository.resolve("frontend"))) repository = repository.getParent();
        Path frontend = repository.resolve("frontend/node_modules/@xterm");
        Path script = repository.resolve("backend/src/test/resources/terminal/mirror-xterm-parity.cjs");
        Path result = temporary.resolve("parity-output.txt");
        Process process = new ProcessBuilder("node", script.toString(),
                frontend.resolve("xterm").toString(), frontend.resolve("addon-unicode11").toString())
                .redirectErrorStream(true).redirectOutput(result.toFile()).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(new ObjectMapper().writeValueAsBytes(cases));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "xterm parity check timed out");
            assertEquals(0, process.exitValue(), Files.readString(result));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static Map<String, Object> scenario(String name, int columns, int rows,
                                                 String before, List<int[]> resizes, String after) {
        HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(columns, rows, 1_000);
        mirror.write(before);
        for (int[] size : resizes) mirror.resize(size[0], size[1]);
        return Map.of("name", name, "cols", columns, "rows", rows, "before", before,
                "resizes", resizes, "after", after, "snapshot", mirror.snapshot());
    }
}
