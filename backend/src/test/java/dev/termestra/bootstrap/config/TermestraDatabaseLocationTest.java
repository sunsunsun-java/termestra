package dev.termestra.bootstrap.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermestraDatabaseLocationTest {
    @TempDir Path temporaryDirectory;

    @Test void createsOnlyTheTermestraDatabaseWithoutImportingOtherDatabaseFiles() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("data");
        Files.createDirectories(dataDirectory);
        Path foreignDatabase = Files.write(dataDirectory.resolve("runtime.sqlite"), new byte[]{1, 2, 3});

        Path target = TermestraDatabaseLocation.prepare(dataDirectory);

        assertEquals(dataDirectory.resolve("termestra.db"), target);
        assertEquals(0, Files.size(target));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(foreignDatabase));
    }

    @Test void keepsAnExistingTermestraDatabase() throws Exception {
        Path dataDirectory = Files.createDirectory(temporaryDirectory.resolve("existing"));
        Path database = Files.write(dataDirectory.resolve("termestra.db"), new byte[]{4, 5});

        assertEquals(database, TermestraDatabaseLocation.prepare(dataDirectory));
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(database));
    }

    @Test void rejectsSymlinkedDataDirectoryAndDatabaseEntries() throws Exception {
        Path realDirectory = Files.createDirectory(temporaryDirectory.resolve("real-data"));
        Path directoryLink = temporaryDirectory.resolve("linked-data");
        createSymbolicLinkOrSkip(directoryLink, realDirectory);
        IOException directoryError = assertThrows(IOException.class,
                () -> TermestraDatabaseLocation.prepare(directoryLink));
        assertTrue(directoryError.getMessage().contains("real directory"));

        Path dataDirectory = Files.createDirectory(temporaryDirectory.resolve("data-links"));
        Path external = Files.writeString(temporaryDirectory.resolve("external.db"), "outside");
        Files.createSymbolicLink(dataDirectory.resolve("termestra.db"), external);
        IOException databaseError = assertThrows(IOException.class,
                () -> TermestraDatabaseLocation.prepare(dataDirectory));
        assertTrue(databaseError.getMessage().contains("real regular file"));
        assertEquals("outside", Files.readString(external));
        assertFalse(Files.isSymbolicLink(external));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.abort("Symbolic links unavailable: " + unsupported.getMessage());
        } catch (IOException unavailable) {
            Assumptions.abort("Symbolic links unavailable: " + unavailable.getMessage());
        }
    }
}
