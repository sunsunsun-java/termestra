package dev.termestra.workspace.adapter.out.filesystem.browse;

import dev.termestra.workspace.application.port.in.browse.BrowseView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioDirectoryBrowserTest {
    @TempDir Path root;

    @Test
    void returnsAStableBoundedProjectionForVeryLargeDirectories() throws IOException {
        for (int index = NioDirectoryBrowser.MAX_ENTRIES + 4; index >= 0; index--) {
            Files.createDirectory(root.resolve("folder-%04d".formatted(index)));
        }
        Files.createDirectory(root.resolve(".hidden"));
        Files.writeString(root.resolve("regular-file.txt"), "ignored");

        BrowseView result = new NioDirectoryBrowser(root).browse("");

        assertTrue(result.ok());
        assertTrue(result.truncated());
        assertEquals(NioDirectoryBrowser.MAX_ENTRIES, result.entries().size());
        assertEquals("folder-0000", result.entries().getFirst().name());
        assertEquals("folder-0499", result.entries().getLast().name());
        assertEquals(IntStream.range(0, NioDirectoryBrowser.MAX_ENTRIES)
                        .mapToObj(index -> "folder-%04d".formatted(index)).toList(),
                result.entries().stream().map(entry -> entry.name()).toList());
    }

    @Test
    void rejectsAndHidesDirectoriesWhoseSymlinkTargetEscapesTheBrowseRoot() throws IOException {
        Path sandbox = Files.createDirectory(root.resolve("sandbox"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createDirectory(outside.resolve("secret"));
        Files.createSymbolicLink(sandbox.resolve("escape"), outside);
        NioDirectoryBrowser browser = new NioDirectoryBrowser(sandbox);

        assertFalse(browser.browse("escape").ok());
        assertFalse(browser.probe("escape/secret").ok());
        BrowseView rootView = browser.browse("");
        assertTrue(rootView.ok());
        assertTrue(rootView.entries().stream().noneMatch(entry -> entry.name().equals("escape")));
    }

    @Test
    void boundsTheActualDirectoryTraversal() {
        NioDirectoryBrowser browser = new NioDirectoryBrowser(root);
        AtomicInteger traversed = new AtomicInteger();
        Stream<Path> paths = Stream.generate(() -> {
                    traversed.incrementAndGet();
                    return root;
                })
                .limit(NioDirectoryBrowser.MAX_SCANNED_ENTRIES + 100L);

        NioDirectoryBrowser.DirectorySelection selection = browser.selectDirectories(paths);

        assertEquals(NioDirectoryBrowser.MAX_SCANNED_ENTRIES, traversed.get());
        assertTrue(selection.truncated());
        assertEquals(NioDirectoryBrowser.MAX_ENTRIES, selection.paths().size());
    }

    @Test
    void returnsAValidationFailureForAnInvalidPathInsteadOfThrowing() {
        NioDirectoryBrowser browser = new NioDirectoryBrowser(root);
        String invalidPath = "invalid" + (char) 0 + "path";

        assertFalse(browser.browse(invalidPath).ok());
        assertFalse(browser.probe(invalidPath).ok());
    }
}
