package dev.termestra.workspace.adapter.out.filesystem.browse;

import dev.termestra.platform.process.BoundedProcessRunner;
import dev.termestra.workspace.application.port.in.browse.ProbeView;
import dev.termestra.workspace.application.port.out.browse.SelectedDirectoryProbe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;

public final class NioSelectedDirectoryProbe implements SelectedDirectoryProbe {
    private static final BoundedProcessRunner PROCESSES = new BoundedProcessRunner();
    private static final Duration GIT_TIMEOUT = Duration.ofMillis(800);
    private static final int MAX_GIT_OUTPUT_BYTES = 4 * 1_024;
    @Override
    public ProbeView probe(String requested) {
        if (requested == null || requested.isBlank()) return missing("");
        final Path candidate;
        try {
            candidate = Path.of(requested.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            return missing(requested.trim());
        }

        try {
            Path real = candidate.toRealPath();
            boolean directory = Files.isDirectory(real);
            boolean gitRepository = directory && Files.exists(real.resolve(".git"));
            return new ProbeView(true, real.toString(), true, directory, gitRepository,
                    gitRepository ? branch(real) : null, suggestedName(real));
        } catch (IOException | SecurityException error) {
            return missing(candidate.toString());
        }
    }

    private static ProbeView missing(String path) {
        String name;
        try {
            name = suggestedName(Path.of(path));
        } catch (InvalidPathException error) {
            name = "";
        }
        return new ProbeView(false, path, false, false, false, null, name);
    }

    private static String suggestedName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static String branch(Path path) {
        try {
            BoundedProcessRunner.Result result = PROCESSES.run(
                    java.util.List.of("git", "-C", path.toString(), "rev-parse", "--abbrev-ref", "HEAD"),
                    GIT_TIMEOUT, MAX_GIT_OUTPUT_BYTES);
            if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) return null;
            String value = result.output().trim();
            return value.isEmpty() ? null : value;
        } catch (IOException error) {
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
