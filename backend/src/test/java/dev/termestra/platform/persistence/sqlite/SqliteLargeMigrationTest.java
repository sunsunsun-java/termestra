package dev.termestra.platform.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteLargeMigrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);
    private static final int LARGE_FIXTURE_SIZE = 1_025;

    @TempDir Path tempDirectory;

    @Test void canonicalizesEveryWorkspaceAcrossMigrationPagesWithoutRetainingDuplicateOwners() throws Exception {
        Path workspace = Files.createDirectory(tempDirectory.resolve("shared-workspace")).toRealPath();
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("large-v26.db"));
        database.write("create large v26 workspace fixture", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL)");
                statement.execute("INSERT INTO schema_version VALUES(26,1)");
                statement.execute("CREATE TABLE workspaces(id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT NOT NULL,created_at INTEGER NOT NULL,deleted_at INTEGER)");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at,deleted_at) VALUES(?,?,?,?,?)")) {
                for (int index = 0; index < LARGE_FIXTURE_SIZE; index++) {
                    insert.setString(1, "active-" + index);
                    insert.setString(2, "Active " + index);
                    insert.setString(3, index % 2 == 0 ? workspace.toString() : workspace.resolve(".").toString());
                    insert.setLong(4, index);
                    insert.setObject(5, null);
                    insert.executeUpdate();
                }
                for (int index = 0; index < 17; index++) {
                    insert.setString(1, "deleted-" + index);
                    insert.setString(2, "Deleted " + index);
                    insert.setString(3, workspace.toString());
                    insert.setLong(4, LARGE_FIXTURE_SIZE + index);
                    insert.setLong(5, 100L + index);
                    insert.executeUpdate();
                }
            }
            return null;
        });

        new SqliteSchemaMigrator(database, CLOCK).migrate();

        database.read("verify large v27 workspace migration", connection -> {
            assertEquals(LARGE_FIXTURE_SIZE + 17,
                    scalar(connection.createStatement(), "SELECT COUNT(*) FROM workspaces"));
            assertEquals(LARGE_FIXTURE_SIZE + 17,
                    scalar(connection.createStatement(), "SELECT COUNT(*) FROM workspaces WHERE canonical_path IS NOT NULL"));
            assertEquals(1,
                    scalar(connection.createStatement(), "SELECT SUM(canonical_path_owner) FROM workspaces"));
            assertEquals("active-0", text(connection.createStatement(),
                    "SELECT id FROM workspaces WHERE canonical_path_owner=1"));
            assertFalse(tableExists(connection.createStatement(), "__termestra_migration_v27_active_paths"));
            return null;
        });
    }

    @Test void renamesEveryDuplicateWorkerAcrossMigrationPagesWithoutChangingRows() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("large-v27.db"));
        database.write("create large v27 worker fixture", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL)");
                statement.execute("INSERT INTO schema_version VALUES(27,1)");
                statement.execute("CREATE TABLE workers(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,name TEXT NOT NULL,role TEXT NOT NULL,created_at INTEGER NOT NULL,deleted_at INTEGER)");
                statement.execute("INSERT INTO workers VALUES('reserved','workspace','Member (2)','custom',0,NULL)");
                statement.execute("INSERT INTO workers VALUES('base','workspace','Member','custom',1,NULL)");
                statement.execute("INSERT INTO workers VALUES('other-workspace','other','Member','custom',1,NULL)");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO workers(id,workspace_id,name,role,created_at,deleted_at) VALUES(?,'workspace','Member','custom',?,NULL)")) {
                for (int index = 0; index < LARGE_FIXTURE_SIZE; index++) {
                    insert.setString(1, "duplicate-" + index);
                    insert.setLong(2, index + 2L);
                    insert.executeUpdate();
                }
            }
            return null;
        });

        new SqliteSchemaMigrator(database, CLOCK).migrate();

        database.read("verify large v28 worker migration", connection -> {
            assertEquals(LARGE_FIXTURE_SIZE + 3,
                    scalar(connection.createStatement(), "SELECT COUNT(*) FROM workers"));
            assertEquals(LARGE_FIXTURE_SIZE + 2,
                    scalar(connection.createStatement(), "SELECT COUNT(DISTINCT name) FROM workers WHERE workspace_id='workspace'"));
            assertEquals("Member (3)", text(connection.createStatement(),
                    "SELECT name FROM workers WHERE id='duplicate-0'"));
            assertEquals("Member (1027)", text(connection.createStatement(),
                    "SELECT name FROM workers WHERE id='duplicate-1024'"));
            assertEquals("Member", text(connection.createStatement(),
                    "SELECT name FROM workers WHERE id='other-workspace'"));
            assertFalse(tableExists(connection.createStatement(), "__termestra_migration_v28_worker_names"));
            assertFalse(tableExists(connection.createStatement(), "__termestra_migration_v28_name_suffixes"));
            return null;
        });
    }

    @Test void backfillsEveryLegacyDispatchAcrossMigrationPagesAndKeepsQueuesIndependent() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("large-v4.db"));
        database.write("create large v4 message fixture", connection -> {
            createVersionFourSchema(connection.createStatement());
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO messages(workspace_id,worker_id,type,from_agent_id,to_agent_id,text,created_at,artifacts)
                    VALUES(?,?,?,?,?,?,?,?)
                    """)) {
                for (int index = 0; index < LARGE_FIXTURE_SIZE; index++) {
                    insertMessage(insert, "workspace", "worker", "send", "orchestrator", "worker",
                            "task-" + index, index, "[]");
                }
                for (int index = 0; index < LARGE_FIXTURE_SIZE; index++) {
                    insertMessage(insert, "workspace", "worker", "report", "worker", "orchestrator",
                            "result-" + index, LARGE_FIXTURE_SIZE + index, "[\"artifact-" + index + "\"]");
                }
                insertMessage(insert, "a:b", "c", "send", "orchestrator", "c",
                        "colon-first", 10_000, "[]");
                insertMessage(insert, "a", "b:c", "send", "orchestrator", "b:c",
                        "colon-second", 10_001, "[]");
                insertMessage(insert, "a", "b:c", "report", "b:c", "orchestrator",
                        "second-result", 10_002, "[]");
                insertMessage(insert, "a:b", "c", "report", "c", "orchestrator",
                        "first-result", 10_003, "[]");
            }
            return null;
        });

        new SqliteSchemaMigrator(database, CLOCK).migrate();

        database.read("verify large v14 dispatch migration", connection -> {
            assertEquals(LARGE_FIXTURE_SIZE * 2 + 4,
                    scalar(connection.createStatement(), "SELECT COUNT(*) FROM messages"));
            assertEquals(LARGE_FIXTURE_SIZE + 2,
                    scalar(connection.createStatement(), "SELECT COUNT(*) FROM dispatches"));
            assertEquals(LARGE_FIXTURE_SIZE + 2,
                    scalar(connection.createStatement(), "SELECT COUNT(*) FROM dispatches WHERE status='reported'"));
            assertEquals(LARGE_FIXTURE_SIZE,
                    scalar(connection.createStatement(), """
                            SELECT COUNT(*) FROM dispatches
                            WHERE text LIKE 'task-%' AND report_text='result-' || substr(text,6)
                            """));
            assertEquals("first-result", text(connection.createStatement(),
                    "SELECT report_text FROM dispatches WHERE text='colon-first'"));
            assertEquals("second-result", text(connection.createStatement(),
                    "SELECT report_text FROM dispatches WHERE text='colon-second'"));
            assertFalse(tableExists(connection.createStatement(), "__termestra_migration_v14_open_dispatches"));
            return null;
        });
    }

    private static void createVersionFourSchema(Statement statement) throws SQLException {
        try (statement) {
            statement.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO schema_version VALUES(1,1),(2,2),(3,3),(4,4)");
            statement.execute("CREATE TABLE workspaces(id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT NOT NULL,created_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO workspaces VALUES('workspace','Workspace','/tmp/workspace',1)");
            statement.execute("CREATE TABLE workers(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,name TEXT NOT NULL,role TEXT NOT NULL,created_at INTEGER NOT NULL,description TEXT)");
            statement.execute("INSERT INTO workers VALUES('worker','workspace','Worker','custom',1,NULL)");
            statement.execute("CREATE TABLE messages(sequence INTEGER PRIMARY KEY AUTOINCREMENT,workspace_id TEXT NOT NULL,worker_id TEXT NOT NULL,kind TEXT,type TEXT NOT NULL,from_agent_id TEXT,to_agent_id TEXT,text TEXT,status TEXT,created_at INTEGER NOT NULL,artifacts TEXT)");
            statement.execute("CREATE TABLE agent_launch_configs(workspace_id TEXT NOT NULL,agent_id TEXT NOT NULL,command TEXT NOT NULL,args_json TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(workspace_id,agent_id))");
            statement.execute("CREATE TABLE agent_runs(run_id TEXT PRIMARY KEY,agent_id TEXT NOT NULL,status TEXT NOT NULL,exit_code INTEGER,started_at INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        }
    }

    private static void insertMessage(PreparedStatement insert, String workspaceId, String workerId,
                                      String type, String fromAgentId, String toAgentId, String text,
                                      long createdAt, String artifacts) throws SQLException {
        insert.setString(1, workspaceId);
        insert.setString(2, workerId);
        insert.setString(3, type);
        insert.setString(4, fromAgentId);
        insert.setString(5, toAgentId);
        insert.setString(6, text);
        insert.setLong(7, createdAt);
        insert.setString(8, artifacts);
        insert.executeUpdate();
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (statement; ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String text(Statement statement, String sql) throws SQLException {
        try (statement; ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean tableExists(Statement statement, String name) throws SQLException {
        try (statement; PreparedStatement query = statement.getConnection().prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }
}
