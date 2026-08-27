package dev.termestra.workspace.adapter.out.filesystem.browse;

import dev.termestra.workspace.application.port.in.browse.ProbeView;
import dev.termestra.workspace.application.service.WorkspaceRegistrationTokenCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SelectedDirectoryProbeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path temporaryDirectory;

    @Test
    void nativeSelectionCanInspectAValidDirectoryOutsideTheBrowseRoot() throws IOException {
        Path browseRoot = Files.createDirectory(temporaryDirectory.resolve("browse-root"));
        Path selected = Files.createDirectory(temporaryDirectory.resolve("external-workspace"));
        NioDirectoryBrowser browser = new NioDirectoryBrowser(browseRoot, tokens());
        NioSelectedDirectoryProbe selectedProbe = new NioSelectedDirectoryProbe(tokens());

        assertFalse(browser.probe(selected.toString()).ok(),
                "server-side browsing must remain sandboxed");

        ProbeView result = selectedProbe.probe(selected.toString());
        assertTrue(result.ok());
        assertTrue(result.exists());
        assertTrue(result.directory());
        assertEquals(selected.toRealPath().toString(), result.path());
        assertEquals("external-workspace", result.suggestedName());
    }

    @Test
    void nativeSelectionRejectsARegularFile() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("not-a-directory.txt"), "test");

        ProbeView result = new NioSelectedDirectoryProbe(tokens()).probe(file.toString());

        assertTrue(result.ok());
        assertTrue(result.exists());
        assertFalse(result.directory());
    }

    private static WorkspaceRegistrationTokenCodec tokens() {
        return new WorkspaceRegistrationTokenCodec(CLOCK);
    }
}
