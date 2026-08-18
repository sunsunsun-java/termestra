package dev.termestra.tasks.adapter.out.filesystem;

import dev.termestra.tasks.application.port.in.TasksDocumentAccessFailure;
import dev.termestra.tasks.application.port.in.TasksDocumentTooLarge;
import dev.termestra.tasks.application.port.out.TasksDocumentStore;
import dev.termestra.tasks.application.service.TeamProtocolDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public final class NioTasksDocumentStore implements TasksDocumentStore {
    public static final long MAX_TASKS_BYTES = 1024L * 1024L;
    private static final String METADATA_DIRECTORY = ".termestra";

    @Override public void initialize(Path workspace) {
        ensure(workspace);
    }

    @Override public String read(Path workspace) {
        return readBounded(ensure(workspace));
    }

    @Override public void write(Path workspace, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        requireSize(bytes.length);
        Path tasks = ensure(workspace);
        try {
            replaceAtomically(tasks, bytes);
        } catch (IOException error) {
            throw new TasksDocumentAccessFailure("Failed to write tasks file: " + tasks, error);
        }
    }

    private static Path ensure(Path workspace) {
        Path directory = workspace.toAbsolutePath().normalize().resolve(METADATA_DIRECTORY);
        try {
            Path root = workspace.toRealPath();
            directory = root.resolve(METADATA_DIRECTORY);
            ensureRealDirectory(directory);
            Path tasks = directory.resolve("tasks.md");
            if (Files.exists(tasks, LinkOption.NOFOLLOW_LINKS)) {
                requireRegularFile(tasks);
            } else {
                try {
                    Files.write(tasks, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException raced) {
                    requireRegularFile(tasks);
                }
            }
            ensureProtocol(directory);
            return tasks;
        } catch (IOException error) {
            throw new TasksDocumentAccessFailure(
                    "Failed to initialize workspace files: " + directory, error);
        }
    }

    private static void ensureRealDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ignored) {
            // Validate the entry below; an existing symlink is never accepted.
        }
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Workspace metadata directory must be a real directory: " + directory);
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Workspace metadata file must be a regular file: " + path);
        }
    }

    private static String readBounded(Path path) {
        return new String(readBytesBounded(path), StandardCharsets.UTF_8);
    }

    private static byte[] readBytesBounded(Path path) {
        try {
            requireRegularFile(path);
            try (InputStream input = Files.newInputStream(
                    path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(Math.toIntExact(MAX_TASKS_BYTES) + 1);
                requireSize(bytes.length);
                return bytes;
            }
        } catch (IOException error) {
            throw new TasksDocumentAccessFailure("Failed to read tasks file: " + path, error);
        }
    }

    private static void requireSize(long size) {
        if (size > MAX_TASKS_BYTES) throw new TasksDocumentTooLarge(MAX_TASKS_BYTES);
    }

    private static void ensureProtocol(Path directory) throws IOException {
        Path protocol = directory.resolve("PROTOCOL.md");
        byte[] desired = TeamProtocolDocument.content().getBytes(StandardCharsets.UTF_8);
        if (Files.exists(protocol, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(protocol);
            if (Files.size(protocol) == desired.length
                    && Arrays.equals(readAtMost(protocol, desired.length + 1), desired)) return;
        }
        replaceAtomically(protocol, desired);
    }

    private static byte[] readAtMost(Path path, int maximumBytes) throws IOException {
        requireRegularFile(path);
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return input.readNBytes(maximumBytes);
        }
    }

    private static void replaceAtomically(Path target, byte[] content) throws IOException {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".tmp");
            Files.write(temporary, content, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        }
    }
}
