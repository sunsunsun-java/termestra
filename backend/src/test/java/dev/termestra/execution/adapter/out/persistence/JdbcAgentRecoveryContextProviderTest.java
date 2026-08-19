package dev.termestra.execution.adapter.out.persistence;

import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAgentRecoveryContextProviderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void loadsOnlyBoundedRecoveryProjectionsFromLargeHistory() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path metadata = Files.createDirectory(workspace.resolve(".termestra"));
        Files.writeString(metadata.resolve("tasks.md"), "t".repeat(10_000));
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("termestra.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("large recovery fixture", connection -> {
            try (PreparedStatement insertWorkspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace',?,1)")) {
                insertWorkspace.setString(1, workspace.toString());
                insertWorkspace.executeUpdate();
            }
            try (PreparedStatement insertWorker = connection.prepareStatement(
                    "INSERT INTO workers(id,workspace_id,name,role,created_at) VALUES('worker','workspace','Worker','coder',1)")) {
                insertWorker.executeUpdate();
            }
            try (PreparedStatement message = connection.prepareStatement(
                    "INSERT INTO messages(workspace_id,worker_id,type,to_agent_id,text,artifacts,created_at) VALUES('workspace','worker','user_input','worker',?,'[]',?)");
                 PreparedStatement dispatch = connection.prepareStatement(
                    "INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at,artifacts) VALUES(?,'workspace','worker',?,'queued',?,'[]')")) {
                for (int index = 0; index < 300; index++) {
                    String text = "%03d-".formatted(index) + "x".repeat(5_000);
                    message.setString(1, text); message.setLong(2, index + 1L); message.addBatch();
                    dispatch.setString(1, "dispatch-" + index); dispatch.setString(2, text);
                    dispatch.setLong(3, index + 1L); dispatch.addBatch();
                }
                message.executeBatch();
                dispatch.executeBatch();
            }
            return null;
        });

        var context = new JdbcAgentRecoveryContextProvider(database)
                .load("workspace", Instant.EPOCH);

        assertEquals(JdbcAgentRecoveryContextProvider.MAX_RECOVERY_TASKS_CHARS,
                context.tasksContent().length());
        assertEquals(JdbcAgentRecoveryContextProvider.MAX_RECOVERY_MESSAGES,
                context.recentMessages().size());
        assertEquals(JdbcAgentRecoveryContextProvider.MAX_RECOVERY_MESSAGES,
                context.allTaskMessages().size());
        assertTrue(context.recentMessages().stream().allMatch(message ->
                message.text().length() <= JdbcAgentRecoveryContextProvider.MAX_RECOVERY_MESSAGE_CHARS));
        assertTrue(context.allTaskMessages().stream().allMatch(message ->
                message.text().length() <= JdbcAgentRecoveryContextProvider.MAX_RECOVERY_MESSAGE_CHARS));
        assertEquals(1, context.workers().size());
        assertEquals(300, context.workers().getFirst().pendingTaskCount());
        assertTrue(context.recentMessages().getFirst().text().startsWith("044-"));
        assertTrue(context.recentMessages().getLast().text().startsWith("299-"));
    }

    @Test
    void limitsLegacyWorkerCandidatesAndProjectsTheirTextInSql() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("worker-candidates.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("large worker candidate fixture", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace',?,1)")) {
                workspace.setString(1, temporaryDirectory.toAbsolutePath().toString());
                workspace.executeUpdate();
            }
            try (PreparedStatement worker = connection.prepareStatement(
                    "INSERT INTO workers(id,workspace_id,name,role,created_at) VALUES(?,'workspace',?,?,?)")) {
                for (int index = 0; index < 300; index++) {
                    worker.setString(1, "worker-" + index);
                    worker.setString(2, index == 0 ? "n".repeat(2_000_000) : "worker-" + index);
                    worker.setString(3, index == 0 ? "r".repeat(2_000_000) : "coder");
                    worker.setLong(4, index);
                    worker.addBatch();
                }
                worker.executeBatch();
            }
            return null;
        });

        var workers = new JdbcAgentRecoveryContextProvider(database)
                .load("workspace", Instant.EPOCH).workers();

        assertEquals(256, workers.size());
        assertTrue(workers.stream().allMatch(worker ->
                worker.name().length() <= 128));
        assertTrue(workers.stream().allMatch(worker ->
                worker.role().length() <= 64));
        assertTrue(workers.stream().noneMatch(worker -> worker.name().startsWith("nnnnnnnn")),
                "an oversized persisted name must not become a truncated dispatch identity");
    }

    @Test
    void boundsMessageMetadataAndOmitsMalformedLegacyIdentities() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("metadata-workspace"));
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("message-metadata.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String huge="x".repeat(2_000_000);
        String boundaryText="t".repeat(JdbcAgentRecoveryContextProvider.MAX_RECOVERY_MESSAGE_CHARS-1)
                +"😀tail";
        String boundaryStatus="s".repeat(JdbcAgentRecoveryContextProvider.MAX_RECOVERY_STATUS_CHARS-1)
                +"😀tail";
        database.write("seed poisoned recovery metadata", connection -> {
            try(var insertWorkspace=connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace',?,1)");
                var statement=connection.createStatement();
                var valid=connection.prepareStatement("""
                    INSERT INTO messages(workspace_id,worker_id,type,from_agent_id,to_agent_id,text,status,artifacts,created_at)
                    VALUES('workspace','worker','report','worker','workspace:orchestrator',?,?,'[]',1)
                    """);
                var malformed=connection.prepareStatement("""
                    INSERT INTO messages(workspace_id,worker_id,type,from_agent_id,to_agent_id,text,status,artifacts,created_at)
                    VALUES('workspace','worker','report',?,?,'ignored','done','[]',2)
                    """);
                var unknown=connection.prepareStatement("""
                    INSERT INTO messages(workspace_id,worker_id,type,from_agent_id,to_agent_id,text,status,artifacts,created_at)
                    VALUES('workspace','worker',?,'worker','worker','ignored','done','[]',3)
                    """);
                var dispatch=connection.prepareStatement("""
                    INSERT INTO dispatches(id,workspace_id,from_agent_id,to_agent_id,text,status,created_at,artifacts)
                    VALUES('dispatch','workspace',?,'worker',?,'queued',4,'[]')
                    """)){
                insertWorkspace.setString(1,workspace.toString());insertWorkspace.executeUpdate();
                statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,created_at) VALUES('worker','workspace','Worker','coder',1)");
                valid.setString(1,boundaryText);valid.setString(2,boundaryStatus);valid.executeUpdate();
                malformed.setString(1,huge);malformed.setString(2,huge);malformed.executeUpdate();
                unknown.setString(1,huge);unknown.executeUpdate();
                dispatch.setString(1,huge);dispatch.setString(2,boundaryText);dispatch.executeUpdate();
            }
            return null;
        });

        var context=new JdbcAgentRecoveryContextProvider(database).load("workspace",Instant.EPOCH);

        assertEquals(1,context.recentMessages().size());
        var recent=context.recentMessages().getFirst();
        assertEquals("worker",recent.fromAgentId());
        assertTrue(recent.text().length()<=JdbcAgentRecoveryContextProvider.MAX_RECOVERY_MESSAGE_CHARS);
        assertTrue(recent.status().length()<=JdbcAgentRecoveryContextProvider.MAX_RECOVERY_STATUS_CHARS);
        assertEquals(false,Character.isHighSurrogate(recent.text().charAt(recent.text().length()-1)));
        assertEquals(false,Character.isHighSurrogate(recent.status().charAt(recent.status().length()-1)));
        assertEquals(1,context.allTaskMessages().size());
        assertEquals(null,context.allTaskMessages().getFirst().fromAgentId());
        assertEquals("worker",context.allTaskMessages().getFirst().toAgentId());
    }

    @Test
    void cannotRecreateMessagesAfterTheWorkerOrWorkspaceIsDeleted() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("delete-race.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("deleted recovery fixture", connection -> {
            try (var statement = connection.createStatement();
                 var workspace = connection.prepareStatement(
                         "INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace',?,1)")) {
                workspace.setString(1, temporaryDirectory.toAbsolutePath().toString());
                workspace.executeUpdate();
                statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,created_at) VALUES('worker','workspace','Worker','coder',1)");
                statement.executeUpdate("DELETE FROM workers WHERE id='worker'");
            }
            return null;
        });

        JdbcAgentRecoveryContextProvider provider = new JdbcAgentRecoveryContextProvider(database);
        assertThrows(RuntimeException.class, () -> provider.appendUserInput(
                "workspace", "worker", "must not be resurrected", Instant.EPOCH));
        assertEquals(0, messageCount(database));

        database.write("delete workspace", connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM workspaces WHERE id='workspace'");
            }
            return null;
        });
        assertThrows(RuntimeException.class, () -> provider.appendSystemRecoveryMessage(
                "workspace", "workspace:orchestrator", "must not be resurrected", Instant.EPOCH));
        assertEquals(0, messageCount(database));
    }

    @Test
    void refusesToReadRecoveryTasksThroughWorkspaceSymlinks() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("symlink-workspace"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("tasks.md"), "outside secret");
        Files.createSymbolicLink(workspace.resolve(".termestra"), outside);
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("symlink.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("symlink recovery fixture", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace',?,1)")) {
                statement.setString(1, workspace.toString());
                statement.executeUpdate();
            }
            return null;
        });

        assertThrows(RuntimeException.class, () ->
                new JdbcAgentRecoveryContextProvider(database).load("workspace", Instant.EPOCH));
    }

    @Test
    void fillsTheRecoveryTaskPrefixWhenAReaderReturnsShortChunks() throws Exception {
        String content="x".repeat(JdbcAgentRecoveryContextProvider.MAX_RECOVERY_TASKS_CHARS+100);
        Reader oneCharacterAtATime=new Reader(){
            private int offset;
            @Override public int read(char[] target,int start,int length){
                if(offset==content.length())return -1;
                target[start]=content.charAt(offset++);
                return 1;
            }
            @Override public void close(){ }
        };

        String prefix=JdbcAgentRecoveryContextProvider.readTasksPrefix(oneCharacterAtATime);

        assertEquals(JdbcAgentRecoveryContextProvider.MAX_RECOVERY_TASKS_CHARS,prefix.length());
        assertEquals(content.substring(0,JdbcAgentRecoveryContextProvider.MAX_RECOVERY_TASKS_CHARS),prefix);
    }

    private static int messageCount(SqliteDatabase database) {
        return database.read("count recovery messages", connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM messages")) {
                result.next();
                return result.getInt(1);
            }
        });
    }
}
