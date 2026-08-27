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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqliteSchemaMigratorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path tempDirectory;

    @Test void createsTheCurrentSchemaAndBuiltinConfiguration() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("fresh.db"));
        new SqliteSchemaMigrator(database, CLOCK).migrate();

        database.read("verify fresh schema", connection -> {
            assertEquals(SqliteSchemaMigrator.CURRENT_SCHEMA_VERSION,
                    scalar(connection.createStatement(), "SELECT MAX(version) FROM schema_version"));
            assertEquals(10, scalar(connection.createStatement(), "SELECT COUNT(*) FROM command_presets"));
            assertEquals(Set.of("claude", "codex", "opencode", "gemini", "hermes", "qwen", "pi", "agy", "cursor", "grok"),
                    values(connection.createStatement(), "SELECT id FROM command_presets"));
            assertEquals(4, scalar(connection.createStatement(), "SELECT COUNT(*) FROM role_templates"));
            assertEquals(1, scalar(connection.createStatement(), "SELECT COUNT(*) FROM app_state WHERE key='active_workspace_id' AND value IS NULL"));
            assertEquals("wal", text(connection.createStatement(), "PRAGMA journal_mode"));
            assertEquals(1, scalar(connection.createStatement(), "PRAGMA foreign_keys"));
            assertEquals(5_000, scalar(connection.createStatement(), "PRAGMA busy_timeout"));
            assertEquals(67_108_864, scalar(connection.createStatement(), "PRAGMA journal_size_limit"));
            assertTrue(columns(connection.createStatement(), "messages").contains("artifacts"));
            assertTrue(columns(connection.createStatement(), "messages").contains("dispatch_id"));
            assertFalse(columns(connection.createStatement(), "messages").contains("kind"));
            assertTrue(columns(connection.createStatement(), "dispatches").contains("sequence"));
            assertTrue(columns(connection.createStatement(), "dispatches").contains("idempotency_key"));
            assertTrue(columns(connection.createStatement(), "dispatch_deliveries")
                    .containsAll(Set.of("dispatch_id", "state", "attempt_id", "attempt_count",
                            "input_attempted", "next_attempt_at", "lease_owner", "lease_expires_at")));
            assertTrue(columns(connection.createStatement(), "agent_launch_configs")
                    .containsAll(Set.of("interactive_command", "env_json", "model_id", "revision")));
            assertTrue(columns(connection.createStatement(), "command_presets")
                    .containsAll(Set.of("model_args_template_json", "suggested_models_json",
                            "allow_custom_model", "revision")));
            assertTrue(columns(connection.createStatement(), "agent_runs").contains("workspace_id"));
            assertTrue(columns(connection.createStatement(), "workspaces").contains("deleted_at"));
            assertTrue(columns(connection.createStatement(), "workspaces")
                    .containsAll(Set.of("canonical_path", "canonical_path_owner", "lifecycle_state")));
            Set<String> registrationColumns = columns(
                    connection.createStatement(), "workspace_registration_attempts");
            assertTrue(registrationColumns.containsAll(Set.of(
                            "registration_id", "workspace_id", "request_hash",
                            "canonical_path", "selection_kind", "selected_branch",
                            "selected_ref_oid", "state",
                            "checkout_outcome", "observed_head_kind", "observed_branch",
                            "observed_head_oid", "error_code", "created_at", "updated_at")));
            assertFalse(registrationColumns.contains("selection_token_hash"));
            assertFalse(registrationColumns.contains("completed_at"));
            assertTrue(columns(connection.createStatement(), "workers").contains("deleted_at"));
            Set<String> indexes = values(connection.createStatement(),
                    "SELECT name FROM sqlite_master WHERE type='index'");
            assertTrue(indexes.containsAll(Set.of(
                            "idx_agent_runs_agent_status", "idx_messages_workspace_created_sequence",
                            "idx_messages_workspace_type_sequence", "idx_workspaces_active_canonical_path",
                            "idx_agent_runs_workspace_status", "idx_workers_active_workspace_name",
                            "idx_dispatches_idempotency", "idx_messages_dispatch",
                            "idx_dispatch_deliveries_ready", "idx_dispatch_deliveries_worker")));
            assertFalse(indexes.contains("idx_workspace_registration_path"));
            assertEquals(
                    "CREATE INDEX idx_dispatches_open_by_worker ON dispatches(workspace_id,to_agent_id,sequence) WHERE status IN ('queued','submitted')",
                    text(connection.createStatement(), "SELECT sql FROM sqlite_master WHERE type='index' AND name='idx_dispatches_open_by_worker'"));
            return null;
        });
    }

    @Test void migratesVersionThirtyLaunchDataToStructuredModelSelection(){
        SqliteDatabase database=new SqliteDatabase(tempDirectory.resolve("v30-launch.db"));
        database.write("create v30 launch fixture",connection->{
            try(Statement statement=connection.createStatement()){
                statement.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL)");
                statement.execute("INSERT INTO schema_version VALUES(30,1)");
                statement.execute("CREATE TABLE command_presets(id TEXT PRIMARY KEY,is_builtin INTEGER NOT NULL)");
                statement.execute("INSERT INTO command_presets VALUES('codex',1)");
                statement.execute("CREATE TABLE agent_launch_configs(workspace_id TEXT NOT NULL,agent_id TEXT NOT NULL,command TEXT NOT NULL,args_json TEXT NOT NULL,PRIMARY KEY(workspace_id,agent_id))");
                statement.execute("INSERT INTO agent_launch_configs VALUES('workspace','worker','codex','[\"legacy\"]')");
            }
            return null;
        });

        new SqliteSchemaMigrator(database,CLOCK).migrate();

        database.read("verify v31 launch migration",connection->{
            assertEquals(31,scalar(connection.createStatement(),"SELECT MAX(version) FROM schema_version"));
            assertTrue(columns(connection.createStatement(),"command_presets").containsAll(Set.of(
                    "model_args_template_json","suggested_models_json","allow_custom_model","revision")));
            assertTrue(columns(connection.createStatement(),"agent_launch_configs").containsAll(Set.of(
                    "model_id","revision")));
            assertEquals("[\"legacy\"]",text(connection.createStatement(),
                    "SELECT args_json FROM agent_launch_configs WHERE agent_id='worker'"));
            assertEquals(1,scalar(connection.createStatement(),
                    "SELECT revision FROM agent_launch_configs WHERE agent_id='worker'"));
            return null;
        });
    }

    @Test void migratesLegacyOpenDispatchesWithoutAutomaticallyReplayingUnknownInput() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("v28-open-dispatches.db"));
        new SqliteSchemaMigrator(database, CLOCK).migrate();
        database.write("restore v28 open dispatch fixture", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE dispatch_deliveries");
                statement.execute("DELETE FROM schema_version WHERE version>=29");
                statement.execute("INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at,artifacts) VALUES('queued','workspace','worker','unknown','queued',1,'[]')");
                statement.execute("INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at,artifacts) VALUES('submitted','workspace','worker','known','submitted',2,'[]')");
            }
            return null;
        });

        new SqliteSchemaMigrator(database, CLOCK).migrate();

        database.read("verify conservative dispatch migration", connection -> {
            assertEquals("uncertain", text(connection.createStatement(),
                    "SELECT state FROM dispatch_deliveries WHERE dispatch_id='queued'"));
            assertEquals(1, scalar(connection.createStatement(),
                    "SELECT input_attempted FROM dispatch_deliveries WHERE dispatch_id='queued'"));
            assertEquals("submitted", text(connection.createStatement(),
                    "SELECT state FROM dispatch_deliveries WHERE dispatch_id='submitted'"));
            return null;
        });
    }

    @Test void preservesLegacyDuplicateWorkspacesWhileChoosingOneCanonicalPathOwner() throws Exception {
        Path workspace = Files.createDirectory(tempDirectory.resolve("legacy-workspace")).toRealPath();
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("v26-duplicates.db"));
        database.write("create v26 duplicate workspace fixture", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL)");
                statement.execute("INSERT INTO schema_version VALUES(26,1)");
                statement.execute("CREATE TABLE workspaces(id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT NOT NULL,created_at INTEGER NOT NULL,deleted_at INTEGER)");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                insert.setString(1, "first"); insert.setString(2, "First");
                insert.setString(3, workspace.toString()); insert.setLong(4, 1); insert.executeUpdate();
                insert.setString(1, "second"); insert.setString(2, "Second");
                insert.setString(3, workspace.resolve(".").toString()); insert.setLong(4, 2); insert.executeUpdate();
            }
            return null;
        });

        new SqliteSchemaMigrator(database, CLOCK).migrate();

        database.read("verify duplicate workspace migration", connection -> {
            assertEquals(2, scalar(connection.createStatement(), "SELECT COUNT(*) FROM workspaces"));
            assertEquals(1, scalar(connection.createStatement(), "SELECT COUNT(DISTINCT canonical_path) FROM workspaces"));
            assertEquals(1, scalar(connection.createStatement(), "SELECT SUM(canonical_path_owner) FROM workspaces"));
            assertEquals("first", text(connection.createStatement(),
                    "SELECT id FROM workspaces WHERE canonical_path_owner=1"));
            assertTrue(values(connection.createStatement(), "SELECT name FROM sqlite_master WHERE type='index'")
                    .contains("idx_workspaces_active_canonical_path"));
            return null;
        });
    }

    @Test void migratesTheDispatchWorkerIndexFromVersionTwentyFiveWithoutLosingDispatches() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("v25-dispatch-index.db"));
        new SqliteSchemaMigrator(database, CLOCK).migrate();
        database.write("restore v25 dispatch index", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM schema_version WHERE version>=26");
                statement.execute("DROP INDEX idx_dispatches_open_by_worker");
                statement.execute("CREATE INDEX idx_dispatches_open_by_worker ON dispatches(workspace_id,to_agent_id,status,sequence)");
                statement.execute("INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at) VALUES('open','workspace','worker','task','queued',1)");
                statement.execute("INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at) VALUES('closed','workspace','worker','done','reported',2)");
            }
            return null;
        });

        new SqliteSchemaMigrator(database, CLOCK).migrate();

        database.read("verify dispatch index migration", connection -> {
            assertEquals(SqliteSchemaMigrator.CURRENT_SCHEMA_VERSION,
                    scalar(connection.createStatement(), "SELECT MAX(version) FROM schema_version"));
            assertEquals(2, scalar(connection.createStatement(), "SELECT COUNT(*) FROM dispatches"));
            assertEquals(
                    "CREATE INDEX idx_dispatches_open_by_worker ON dispatches(workspace_id,to_agent_id,sequence) WHERE status IN ('queued','submitted')",
                    text(connection.createStatement(), "SELECT sql FROM sqlite_master WHERE type='index' AND name='idx_dispatches_open_by_worker'"));
            return null;
        });
    }

    @Test void freshDatabaseContainsOnlyTermestraOwnedTables() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("fresh-owned-tables.db"));
        new SqliteSchemaMigrator(database, CLOCK).migrate();

        database.read("verify owned tables", connection -> {
            assertEquals(Set.of(
                            "agent_launch_configs", "agent_runs", "agent_sessions", "app_state",
                            "command_presets", "dispatches", "dispatch_deliveries", "messages", "role_templates",
                            "schema_version", "workers", "workspaces",
                            "workspace_registration_attempts"),
                    values(connection.createStatement(), "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"));
            return null;
        });
    }

    @Test void migratesAVersionFourDatabaseWithoutLosingMessages() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("legacy.db"));
        database.write("create v4 fixture", c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL)");
                s.execute("INSERT INTO schema_version VALUES(1,1),(2,2),(3,3),(4,4)");
                s.execute("CREATE TABLE workspaces(id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT NOT NULL,created_at INTEGER NOT NULL)");
                s.execute("CREATE TABLE workers(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,name TEXT NOT NULL,role TEXT NOT NULL,created_at INTEGER NOT NULL,description TEXT)");
                s.execute("CREATE TABLE messages(sequence INTEGER PRIMARY KEY AUTOINCREMENT,workspace_id TEXT NOT NULL,worker_id TEXT NOT NULL,kind TEXT,type TEXT NOT NULL,from_agent_id TEXT,to_agent_id TEXT,text TEXT,status TEXT,created_at INTEGER NOT NULL,artifacts TEXT)");
                s.execute("INSERT INTO messages(workspace_id,worker_id,type,kind,to_agent_id,text,created_at,artifacts) VALUES('w','worker-1','send','legacy','worker-1','keep me',10,'[]')");
                s.execute("INSERT INTO messages(workspace_id,worker_id,type,kind,to_agent_id,text,created_at,artifacts) VALUES('w','worker-1','send','legacy','worker-1','second task',11,'[]')");
                s.execute("INSERT INTO messages(workspace_id,worker_id,type,kind,text,created_at,artifacts) VALUES('w','worker-1','report','legacy','first result',12,'[\"first.txt\"]')");
                s.execute("INSERT INTO messages(workspace_id,worker_id,type,kind,text,created_at,artifacts) VALUES('w','worker-1','report','legacy','second result',13,'[\"second.txt\"]')");
                s.execute("CREATE TABLE agent_launch_configs(workspace_id TEXT NOT NULL,agent_id TEXT NOT NULL,command TEXT NOT NULL,args_json TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(workspace_id,agent_id))");
                s.execute("CREATE TABLE agent_runs(run_id TEXT PRIMARY KEY,agent_id TEXT NOT NULL,status TEXT NOT NULL,exit_code INTEGER,started_at INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
            }
            return null;
        });

        new SqliteSchemaMigrator(database, CLOCK).migrate();
        database.read("verify migrated message", connection -> {
            assertFalse(columns(connection.createStatement(), "messages").contains("kind"));
            try (ResultSet rs = connection.createStatement().executeQuery("SELECT type,text,artifacts FROM messages")) {
                assertTrue(rs.next()); assertEquals("send", rs.getString(1)); assertEquals("keep me", rs.getString(2)); assertEquals("[]", rs.getString(3));
            }
            try (ResultSet rs = connection.createStatement().executeQuery("SELECT text,status,report_text,artifacts FROM dispatches ORDER BY sequence")) {
                assertTrue(rs.next()); assertEquals("keep me", rs.getString("text")); assertEquals("reported", rs.getString("status")); assertEquals("first result", rs.getString("report_text")); assertEquals("[\"first.txt\"]", rs.getString("artifacts"));
                assertTrue(rs.next()); assertEquals("second task", rs.getString("text")); assertEquals("reported", rs.getString("status")); assertEquals("second result", rs.getString("report_text"));
                assertFalse(rs.next());
            }
            return null;
        });
    }

    @Test void rejectsAForeignSchemaVersionWithoutRewritingItsHistoryOrColumns() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("foreign-schema.db"));
        database.write("create foreign schema fixture", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL)");
                for (int version = 1; version <= 40; version++) {
                    statement.execute("INSERT INTO schema_version VALUES(" + version + "," + version + ")");
                }
                statement.execute("CREATE TABLE command_presets(id TEXT PRIMARY KEY,display_name TEXT NOT NULL,command TEXT NOT NULL,args TEXT NOT NULL,env TEXT NOT NULL,resume_args_template TEXT,session_id_capture TEXT,yolo_args_template TEXT,is_builtin INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
            }
            return null;
        });

        SqlitePersistenceException error = assertThrows(SqlitePersistenceException.class,
                () -> new SqliteSchemaMigrator(database, CLOCK).migrate());
        assertTrue(error.getMessage().contains("migrate schema"));

        database.read("verify foreign schema remains untouched", connection -> {
            assertEquals(40, scalar(connection.createStatement(), "SELECT MAX(version) FROM schema_version"));
            assertTrue(columns(connection.createStatement(), "command_presets").contains("args"));
            assertFalse(columns(connection.createStatement(), "command_presets").contains("args_json"));
            return null;
        });
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (statement; ResultSet rs = statement.executeQuery(sql)) { assertTrue(rs.next()); return rs.getInt(1); }
    }
    private static String text(Statement statement, String sql) throws SQLException {
        try (statement; ResultSet rs = statement.executeQuery(sql)) { assertTrue(rs.next()); return rs.getString(1); }
    }
    private static Set<String> columns(Statement statement, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (statement; ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) { while (rs.next()) columns.add(rs.getString("name")); }
        return columns;
    }
    private static Set<String> values(Statement statement, String sql) throws SQLException {
        Set<String> values = new HashSet<>();
        try (statement; ResultSet rs = statement.executeQuery(sql)) { while (rs.next()) values.add(rs.getString(1)); }
        return values;
    }
}
