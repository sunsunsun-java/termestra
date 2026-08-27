package dev.termestra.workspace.adapter.out.filesystem.browse;

import dev.termestra.platform.process.BoundedProcessRunner;
import dev.termestra.workspace.application.port.in.browse.BrowseEntryView;
import dev.termestra.workspace.application.port.in.browse.BrowseView;
import dev.termestra.workspace.application.port.in.browse.ProbeView;
import dev.termestra.workspace.application.port.out.browse.DirectoryBrowser;
import dev.termestra.workspace.application.service.WorkspaceRegistrationTokenCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.stream.Stream;

public final class NioDirectoryBrowser implements DirectoryBrowser {
    static final int MAX_ENTRIES = 500;
    static final int MAX_SCANNED_ENTRIES = 4_096;
    private static final BoundedProcessRunner PROCESSES = new BoundedProcessRunner();
    private static final Duration GIT_TIMEOUT = Duration.ofMillis(800);
    private static final int MAX_GIT_OUTPUT_BYTES = 4 * 1_024;
    private static final Comparator<Path> BY_NAME = Comparator.comparing(
            path -> path.getFileName().toString());

    private final Path root;
    private final WorkspaceRegistrationTokenCodec tokens;

    public NioDirectoryBrowser(Path root, WorkspaceRegistrationTokenCodec tokens) {
        try {
            this.root = root.toAbsolutePath().normalize().toRealPath();
            this.tokens = Objects.requireNonNull(tokens, "tokens");
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("Browse root is unavailable: " + root, unavailable);
        }
    }

    @Override
    public BrowseView browse(String requested) {
        Path candidate;
        try {
            candidate = resolve(requested);
        } catch (InvalidPathException invalid) {
            return failure(root, "The specified path is invalid.");
        }
        if (!inside(candidate)) {
            return failure(root, "Access denied: path is outside the browse root.");
        }
        if (!Files.isDirectory(candidate)) {
            String error = Files.exists(candidate)
                    ? "The specified path is not a directory."
                    : "Directory does not exist.";
            return failure(candidate, error);
        }

        DirectorySelection selection;
        try (var stream = Files.list(candidate)) {
            selection = selectDirectories(stream);
        } catch (IOException error) {
            return failure(candidate, error.getMessage());
        }

        List<Path> selected = new ArrayList<>(selection.paths());
        selected.sort(BY_NAME);
        List<BrowseEntryView> entries = selected.stream()
                .map(path -> new BrowseEntryView(
                        path.getFileName().toString(), path.toString(), true, isGit(path)))
                .toList();
        Path parent = candidate.equals(root) ? null : candidate.getParent();
        return new BrowseView(true, root.toString(), candidate.toString(),
                parent != null && inside(parent) ? parent.toString() : null,
                entries, selection.truncated(), null);
    }

    DirectorySelection selectDirectories(Stream<Path> paths) {
        PriorityQueue<Path> firstEntries = new PriorityQueue<>(MAX_ENTRIES, BY_NAME.reversed());
        boolean truncated = false;
        int scanned = 0;
        var iterator = paths.iterator();
        // Check the budget before hasNext(): filesystem stream iterators may advance while
        // probing, and the scan itself—not only the retained result—must remain bounded.
        while (scanned < MAX_SCANNED_ENTRIES && iterator.hasNext()) {
            scanned++;
            Path path = iterator.next();
            if (!Files.isDirectory(path) || path.getFileName().toString().startsWith(".")
                    || !inside(path)) continue;
            if (firstEntries.size() < MAX_ENTRIES) {
                firstEntries.add(path);
                continue;
            }
            truncated = true;
            if (BY_NAME.compare(path, firstEntries.element()) < 0) {
                firstEntries.remove();
                firstEntries.add(path);
            }
        }
        // Do not probe one more filesystem entry just to distinguish exactly-at-budget from
        // over-budget. Conservatively advertise truncation when the budget is exhausted.
        return new DirectorySelection(firstEntries, truncated || scanned == MAX_SCANNED_ENTRIES);
    }

    @Override
    public ProbeView probe(String requested) {
        Path candidate;
        try {
            candidate = resolve(requested);
        } catch (InvalidPathException invalid) {
            return new ProbeView(false, requested == null ? "" : requested,
                    false, false, false, null, "", null);
        }
        String name = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
        if (!inside(candidate)) {
            return new ProbeView(false, candidate.toString(), false, false,
                    false, null, name, null);
        }
        boolean exists = Files.exists(candidate);
        boolean directory = Files.isDirectory(candidate);
        boolean git = directory && isGit(candidate);
        return new ProbeView(exists, candidate.toString(), exists, directory,
                git, git ? branch(candidate) : null, name,
                git ? tokens.issuePath(candidate.toString()) : null);
    }

    private BrowseView failure(Path candidate, String error) {
        return new BrowseView(false, root.toString(), candidate.toString(),
                null, List.of(), false, error);
    }

    private Path resolve(String requested) {
        String trimmed = requested == null ? "" : requested.trim();
        if (trimmed.isEmpty()) return root;
        Path value = Path.of(trimmed);
        return (value.isAbsolute() ? value : root.resolve(value)).toAbsolutePath().normalize();
    }

    private boolean inside(Path path) {
        try {
            Path canonical = canonicalForContainment(path);
            return canonical.equals(root) || canonical.startsWith(root);
        } catch (IOException inaccessible) {
            return false;
        }
    }

    private static Path canonicalForContainment(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("Path has no accessible ancestor: " + path);
        Path canonicalParent = existing.toRealPath();
        return canonicalParent.resolve(existing.relativize(absolute)).normalize();
    }

    private static boolean isGit(Path path) {
        return Files.exists(path.resolve(".git"));
    }

    private static String branch(Path path) {
        try {
            BoundedProcessRunner.Result result = PROCESSES.run(
                    List.of("git", "-C", path.toString(), "rev-parse", "--abbrev-ref", "HEAD"),
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

    record DirectorySelection(PriorityQueue<Path> paths, boolean truncated) { }
}
