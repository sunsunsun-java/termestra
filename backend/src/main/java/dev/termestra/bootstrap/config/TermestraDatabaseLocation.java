package dev.termestra.bootstrap.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class TermestraDatabaseLocation {
    private static final String DATABASE_NAME = "termestra.db";

    private TermestraDatabaseLocation() { }

    static Path prepare(Path requestedDirectory) throws IOException {
        Path directory = requestedDirectory.toAbsolutePath().normalize();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Termestra data directory must be a real directory: " + directory);
        }

        Path database = directory.resolve(DATABASE_NAME);
        try {
            Files.createFile(database);
        } catch (java.nio.file.FileAlreadyExistsException existing) {
            // Validate the existing entry below.
        }
        if (Files.isSymbolicLink(database)
                || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Termestra database must be a real regular file: " + database);
        }
        try (var ignored = Files.newByteChannel(database,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return database;
        }
    }
}
