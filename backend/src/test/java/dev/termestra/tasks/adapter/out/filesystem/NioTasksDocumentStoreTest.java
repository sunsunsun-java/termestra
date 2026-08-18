package dev.termestra.tasks.adapter.out.filesystem;

import dev.termestra.tasks.application.service.TeamProtocolDocument;
import dev.termestra.tasks.application.port.in.TasksDocumentAccessFailure;
import dev.termestra.tasks.application.port.in.TasksDocumentTooLarge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class NioTasksDocumentStoreTest {
    @TempDir Path workspace;
    @TempDir Path externalMetadata;
    private final NioTasksDocumentStore store = new NioTasksDocumentStore();

    @Test void createsTasksAndCurrentProtocolDocuments() throws Exception {
        assertEquals("", store.read(workspace));
        assertEquals(TeamProtocolDocument.content(), Files.readString(protocol(), StandardCharsets.UTF_8));
    }

    @Test void initializesOnlyTermestraMetadataAndIgnoresForeignTaskFiles() throws Exception {
        Path rootTasks = Files.writeString(workspace.resolve("tasks.md"), "root tasks", StandardCharsets.UTF_8);
        Path legacy = Files.createDirectory(workspace.resolve(".hive"));
        Path legacyTasks = Files.writeString(legacy.resolve("tasks.md"), "legacy metadata tasks");
        Path legacyProtocol = Files.writeString(legacy.resolve("PROTOCOL.md"), "legacy protocol");

        assertEquals("", store.read(workspace));

        assertEquals("", Files.readString(workspace.resolve(".termestra/tasks.md")));
        assertEquals(TeamProtocolDocument.content(),
                Files.readString(workspace.resolve(".termestra/PROTOCOL.md"), StandardCharsets.UTF_8));
        assertEquals("root tasks", Files.readString(rootTasks));
        assertEquals("legacy metadata tasks", Files.readString(legacyTasks));
        assertEquals("legacy protocol", Files.readString(legacyProtocol));
    }

    @Test void replacesAStaleProtocolDocument() throws Exception {
        store.read(workspace);
        Files.writeString(protocol(), "stale", StandardCharsets.UTF_8);
        store.read(workspace);
        assertEquals(TeamProtocolDocument.content(), Files.readString(protocol(), StandardCharsets.UTF_8));
    }

    @Test void doesNotRewriteAnUpToDateProtocolDocument() throws Exception {
        store.read(workspace);
        FileTime marker = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(protocol(), marker);
        store.read(workspace);
        assertEquals(marker, Files.getLastModifiedTime(protocol()));
    }

    @Test void replacesAnOversizedProtocolWithoutReadingItIntoMemory() throws Exception {
        store.read(workspace);
        Files.write(protocol(), new byte[Math.toIntExact(NioTasksDocumentStore.MAX_TASKS_BYTES + 1)]);

        store.read(workspace);

        assertEquals(TeamProtocolDocument.content(), Files.readString(protocol(), StandardCharsets.UTF_8));
    }

    @Test void rejectsOversizedWritesBeforeReplacingExistingContent() throws Exception {
        store.write(workspace,"keep me");
        String oversized="x".repeat(Math.toIntExact(NioTasksDocumentStore.MAX_TASKS_BYTES+1));

        assertThrows(TasksDocumentTooLarge.class,()->store.write(workspace,oversized));
        assertEquals("keep me",store.read(workspace));
    }

    @Test void rejectsOversizedDocumentsEditedOutsideTermestra() throws Exception {
        store.read(workspace);
        Files.write(workspace.resolve(".termestra/tasks.md"),new byte[Math.toIntExact(NioTasksDocumentStore.MAX_TASKS_BYTES+1)]);

        assertThrows(TasksDocumentTooLarge.class,()->store.read(workspace));
    }

    @Test void rejectsSymlinkedWorkspaceMetadataWithoutTouchingItsTarget() throws Exception {
        Path outsideTasks = Files.writeString(externalMetadata.resolve("tasks.md"), "outside");
        Files.createSymbolicLink(workspace.resolve(".termestra"), externalMetadata);

        assertThrows(TasksDocumentAccessFailure.class, () -> store.write(workspace, "overwrite"));
        assertEquals("outside", Files.readString(outsideTasks));
        assertFalse(Files.exists(externalMetadata.resolve("PROTOCOL.md")));
    }

    @Test void rejectsSymlinkedTasksAndProtocolFiles() throws Exception {
        Path metadata = Files.createDirectory(workspace.resolve(".termestra"));
        Path outsideTasks = Files.writeString(workspace.resolve("outside-tasks"), "outside");
        Files.createSymbolicLink(metadata.resolve("tasks.md"), outsideTasks);
        assertThrows(TasksDocumentAccessFailure.class, () -> store.read(workspace));

        Files.delete(metadata.resolve("tasks.md"));
        Files.writeString(metadata.resolve("tasks.md"), "safe");
        Path outsideProtocol = Files.writeString(workspace.resolve("outside-protocol"), "outside");
        Files.createSymbolicLink(metadata.resolve("PROTOCOL.md"), outsideProtocol);
        assertThrows(TasksDocumentAccessFailure.class, () -> store.read(workspace));
        assertEquals("outside", Files.readString(outsideProtocol));
    }

    private Path protocol() { return workspace.resolve(".termestra/PROTOCOL.md"); }
}
