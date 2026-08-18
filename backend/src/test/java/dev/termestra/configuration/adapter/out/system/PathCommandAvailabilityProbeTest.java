package dev.termestra.configuration.adapter.out.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCommandAvailabilityProbeTest {
    @TempDir Path temporaryDirectory;

    @Test void resolvesCommandsFromPresetSpecificPath() throws Exception {
        Path command = temporaryDirectory.resolve("custom-agent");
        Files.writeString(command, "#!/bin/sh\n");
        command.toFile().setExecutable(true);
        assertTrue(new PathCommandAvailabilityProbe().available(
                "custom-agent", Map.of("PATH", temporaryDirectory.toString())));
    }

    @Test void treatsAnInvalidConfiguredExecutablePathAsUnavailable() {
        assertFalse(new PathCommandAvailabilityProbe().available("invalid\0command", Map.of()));
    }
}
