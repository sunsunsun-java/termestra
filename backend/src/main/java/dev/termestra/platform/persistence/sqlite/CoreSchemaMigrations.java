package dev.termestra.platform.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.termestra.platform.persistence.sqlite.SchemaSupport.execute;
import static dev.termestra.platform.persistence.sqlite.SchemaSupport.hasColumn;

final class CoreSchemaMigrations {
    private static final int MIGRATION_BATCH_SIZE = 256;

    List<SchemaMigration> migrations() {
        return List.of(
                new SchemaMigration(1, this::v1),
                new SchemaMigration(2, c -> execute(c, "ALTER TABLE workers ADD COLUMN description TEXT")),
                new SchemaMigration(3, c -> { }),
                new SchemaMigration(4, c -> execute(c, "ALTER TABLE messages ADD COLUMN artifacts TEXT")),
                new SchemaMigration(5, this::v5),
                new SchemaMigration(6, this::v6),
                new SchemaMigration(20, c -> execute(c,"ALTER TABLE workspaces ADD COLUMN deleted_at INTEGER")),
                new SchemaMigration(21, c -> execute(c,"ALTER TABLE workers ADD COLUMN deleted_at INTEGER")),
                new SchemaMigration(25, this::v25),
                new SchemaMigration(27, this::v27),
                new SchemaMigration(28, this::v28),
                new SchemaMigration(30, this::v30));
    }

    private void v1(Connection c) throws SQLException {
        execute(c, "CREATE TABLE workspaces (id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, created_at INTEGER NOT NULL)");
        execute(c, "CREATE TABLE workers (id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL, created_at INTEGER NOT NULL)");
        execute(c, "CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, workspace_id TEXT NOT NULL, worker_id TEXT NOT NULL, kind TEXT, type TEXT NOT NULL, from_agent_id TEXT, to_agent_id TEXT, text TEXT, status TEXT, created_at INTEGER NOT NULL)");
        execute(c, "CREATE TABLE agent_launch_configs (workspace_id TEXT NOT NULL, agent_id TEXT NOT NULL, command TEXT NOT NULL, args_json TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (workspace_id, agent_id))");
        execute(c, "CREATE TABLE agent_runs (run_id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, status TEXT NOT NULL, exit_code INTEGER, started_at INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
    }

    private void v5(Connection c) throws SQLException {
        execute(c, "ALTER TABLE workers ADD COLUMN last_session_id TEXT");
        execute(c, "ALTER TABLE agent_runs ADD COLUMN pid INTEGER");
        execute(c, "ALTER TABLE agent_runs ADD COLUMN ended_at INTEGER");
        execute(c, "ALTER TABLE messages RENAME TO messages_v4");
        execute(c, "CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, workspace_id TEXT NOT NULL, worker_id TEXT NOT NULL, type TEXT NOT NULL, from_agent_id TEXT, to_agent_id TEXT, text TEXT, status TEXT, artifacts TEXT, created_at INTEGER NOT NULL)");
        execute(c, "INSERT INTO messages(sequence,workspace_id,worker_id,type,from_agent_id,to_agent_id,text,status,artifacts,created_at) SELECT sequence,workspace_id,worker_id,type,from_agent_id,to_agent_id,text,status,artifacts,created_at FROM messages_v4");
        execute(c, "DROP TABLE messages_v4");
    }

    private void v6(Connection c) throws SQLException {
        execute(c, "ALTER TABLE agent_launch_configs ADD COLUMN resume_args_template TEXT");
        execute(c, "ALTER TABLE agent_launch_configs ADD COLUMN session_id_capture_json TEXT");
        execute(c, "CREATE TABLE agent_sessions (agent_id TEXT NOT NULL, workspace_id TEXT NOT NULL, last_session_id TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (workspace_id, agent_id))");
    }

    private void v25(Connection c) throws SQLException {
        if (tableExists(c, "agent_runs")) {
            execute(c, "CREATE INDEX IF NOT EXISTS idx_agent_runs_agent_status ON agent_runs(agent_id,status)");
        }
        if (tableExists(c, "messages")) {
            execute(c, "CREATE INDEX IF NOT EXISTS idx_messages_workspace_created_sequence ON messages(workspace_id,created_at,sequence)");
            execute(c, "CREATE INDEX IF NOT EXISTS idx_messages_workspace_type_sequence ON messages(workspace_id,type,sequence)");
        }
    }

    private void v27(Connection connection) throws SQLException {
        if (!hasColumn(connection, "workspaces", "canonical_path")) {
            execute(connection, "ALTER TABLE workspaces ADD COLUMN canonical_path TEXT");
        }
        if (!hasColumn(connection, "workspaces", "canonical_path_owner")) {
            execute(connection, "ALTER TABLE workspaces ADD COLUMN canonical_path_owner INTEGER NOT NULL DEFAULT 0");
        }

        execute(connection, "DROP TABLE IF EXISTS __termestra_migration_v27_active_paths");
        execute(connection, """
                CREATE TABLE __termestra_migration_v27_active_paths (
                    canonical_path TEXT PRIMARY KEY
                ) WITHOUT ROWID
                """);
        migrateCanonicalWorkspacePaths(connection);
        execute(connection, "DROP TABLE __termestra_migration_v27_active_paths");
        execute(connection, """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_workspaces_active_canonical_path
                ON workspaces(canonical_path)
                WHERE deleted_at IS NULL AND canonical_path_owner=1
                """);
    }

    private void v30(Connection connection) throws SQLException {
        // Some migration boundary tests intentionally construct only the table owned by
        // an earlier migration. A production schema always has workspaces, while a
        // partial fixture must still be able to advance its version safely.
        if (!tableExists(connection, "workspaces")) return;
        if (!hasColumn(connection, "workspaces", "lifecycle_state")) {
            execute(connection, """
                    ALTER TABLE workspaces
                    ADD COLUMN lifecycle_state TEXT NOT NULL DEFAULT 'active'
                    CHECK(lifecycle_state IN ('preparing','active'))
                    """);
        }
        execute(connection, """
                CREATE TABLE IF NOT EXISTS workspace_registration_attempts (
                    registration_id TEXT PRIMARY KEY,
                    workspace_id TEXT UNIQUE,
                    request_hash TEXT NOT NULL,
                    canonical_path TEXT NOT NULL,
                    selection_kind TEXT NOT NULL
                        CHECK(selection_kind IN ('current','local_branch')),
                    selected_branch TEXT,
                    selected_ref_oid TEXT,
                    state TEXT NOT NULL
                        CHECK(state IN ('reserved','switching','checkout_applied','completed','failed','uncertain')),
                    checkout_outcome TEXT NOT NULL
                        CHECK(checkout_outcome IN ('not_attempted','applied','rejected','unknown')),
                    observed_head_kind TEXT,
                    observed_branch TEXT,
                    observed_head_oid TEXT,
                    error_code TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE SET NULL
                )
                """);
        execute(connection, """
                CREATE INDEX IF NOT EXISTS idx_workspace_registration_recovery
                ON workspace_registration_attempts(state,updated_at)
                """);
    }

    private void migrateCanonicalWorkspacePaths(Connection connection) throws SQLException {
        LegacyWorkspacePath cursor = null;
        try (PreparedStatement firstPage = connection.prepareStatement("""
                     SELECT id,path,deleted_at,created_at,
                            CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS activity_order
                     FROM workspaces
                     ORDER BY activity_order,created_at,id
                     LIMIT ?
                     """);
             PreparedStatement nextPage = connection.prepareStatement("""
                     SELECT id,path,deleted_at,created_at,
                            CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS activity_order
                     FROM workspaces
                     WHERE CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END > ?
                        OR (CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END = ? AND
                            (created_at > ? OR (created_at = ? AND id > ?)))
                     ORDER BY activity_order,created_at,id
                     LIMIT ?
                     """);
             PreparedStatement claimPath = connection.prepareStatement("""
                     INSERT OR IGNORE INTO __termestra_migration_v27_active_paths(canonical_path)
                     VALUES (?)
                     """);
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE workspaces SET canonical_path=?,canonical_path_owner=? WHERE id=?")) {
            while (true) {
                List<LegacyWorkspacePath> page = readWorkspacePage(firstPage, nextPage, cursor);
                if (page.isEmpty()) return;
                for (LegacyWorkspacePath workspace : page) {
                    String canonicalPath = canonicalize(workspace.path());
                    boolean owner = workspace.active() && claimCanonicalPath(claimPath, canonicalPath);
                    update.setString(1, canonicalPath);
                    update.setInt(2, owner ? 1 : 0);
                    update.setString(3, workspace.id());
                    update.addBatch();
                }
                update.executeBatch();
                update.clearBatch();
                cursor = page.getLast();
            }
        }
    }

    private static List<LegacyWorkspacePath> readWorkspacePage(PreparedStatement firstPage,
                                                                 PreparedStatement nextPage,
                                                                 LegacyWorkspacePath cursor)
            throws SQLException {
        PreparedStatement query;
        if (cursor == null) {
            query = firstPage;
            query.setInt(1, MIGRATION_BATCH_SIZE);
        } else {
            query = nextPage;
            query.setInt(1, cursor.activityOrder());
            query.setInt(2, cursor.activityOrder());
            query.setLong(3, cursor.createdAt());
            query.setLong(4, cursor.createdAt());
            query.setString(5, cursor.id());
            query.setInt(6, MIGRATION_BATCH_SIZE);
        }
        List<LegacyWorkspacePath> page = new ArrayList<>(MIGRATION_BATCH_SIZE);
        try (ResultSet result = query.executeQuery()) {
            while (result.next()) {
                page.add(new LegacyWorkspacePath(
                        result.getString("id"),
                        result.getString("path"),
                        result.getObject("deleted_at") == null,
                        result.getInt("activity_order"),
                        result.getLong("created_at")));
            }
        }
        return page;
    }

    private static boolean claimCanonicalPath(PreparedStatement claimPath, String canonicalPath)
            throws SQLException {
        claimPath.setString(1, canonicalPath);
        return claimPath.executeUpdate() == 1;
    }

    private static String canonicalize(String rawPath) {
        try {
            Path normalized = Path.of(rawPath).toAbsolutePath().normalize();
            try {
                return normalized.toRealPath().toString();
            } catch (IOException unavailable) {
                return normalized.toString();
            }
        } catch (InvalidPathException invalid) {
            return rawPath;
        }
    }

    private record LegacyWorkspacePath(
            String id,
            String path,
            boolean active,
            int activityOrder,
            long createdAt) { }

    private void v28(Connection connection) throws SQLException {
        if (tableExists(connection, "agent_launch_configs")
                && !hasColumn(connection, "agent_launch_configs", "env_json")) {
            execute(connection, "ALTER TABLE agent_launch_configs ADD COLUMN env_json TEXT NOT NULL DEFAULT '{}'");
        }
        if (tableExists(connection, "agent_runs") && !hasColumn(connection, "agent_runs", "workspace_id")) {
            execute(connection, "ALTER TABLE agent_runs ADD COLUMN workspace_id TEXT");
            if (tableExists(connection, "workers")) execute(connection, """
                    UPDATE agent_runs
                    SET workspace_id=(SELECT workspace_id FROM workers WHERE workers.id=agent_runs.agent_id)
                    WHERE workspace_id IS NULL
                    """);
            if (tableExists(connection, "agent_launch_configs")) execute(connection, """
                    UPDATE agent_runs
                    SET workspace_id=(SELECT workspace_id FROM agent_launch_configs
                                      WHERE agent_launch_configs.agent_id=agent_runs.agent_id LIMIT 1)
                    WHERE workspace_id IS NULL
                    """);
            execute(connection, """
                    UPDATE agent_runs
                    SET workspace_id=substr(agent_id,1,length(agent_id)-length(':orchestrator'))
                    WHERE workspace_id IS NULL AND agent_id LIKE '%:orchestrator'
                    """);
            execute(connection, """
                    UPDATE agent_runs
                    SET workspace_id=substr(agent_id,1,length(agent_id)-length(':shell'))
                    WHERE workspace_id IS NULL AND agent_id LIKE '%:shell'
                    """);
        }
        if (tableExists(connection, "agent_runs")) {
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_agent_runs_workspace_status ON agent_runs(workspace_id,status)");
        }
        if (!tableExists(connection, "workers")) return;
        execute(connection, "DROP TABLE IF EXISTS __termestra_migration_v28_worker_names");
        execute(connection, "DROP TABLE IF EXISTS __termestra_migration_v28_name_suffixes");
        execute(connection, """
                CREATE TABLE __termestra_migration_v28_worker_names (
                    workspace_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    PRIMARY KEY (workspace_id,name)
                ) WITHOUT ROWID
                """);
        execute(connection, """
                CREATE TABLE __termestra_migration_v28_name_suffixes (
                    workspace_id TEXT NOT NULL,
                    base_name TEXT NOT NULL,
                    next_suffix INTEGER NOT NULL,
                    PRIMARY KEY (workspace_id,base_name)
                ) WITHOUT ROWID
                """);
        migrateUniqueWorkerNames(connection);
        execute(connection, "DROP TABLE __termestra_migration_v28_name_suffixes");
        execute(connection, "DROP TABLE __termestra_migration_v28_worker_names");
        execute(connection, """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_workers_active_workspace_name
                ON workers(workspace_id,name)
                WHERE deleted_at IS NULL
                """);
    }

    private void migrateUniqueWorkerNames(Connection connection) throws SQLException {
        LegacyWorkerName cursor = null;
        try (PreparedStatement firstPage = connection.prepareStatement("""
                     SELECT rowid AS migration_rowid,id,workspace_id,name,created_at
                     FROM workers
                     WHERE deleted_at IS NULL
                     ORDER BY workspace_id,created_at,rowid
                     LIMIT ?
                     """);
             PreparedStatement nextPage = connection.prepareStatement("""
                     SELECT rowid AS migration_rowid,id,workspace_id,name,created_at
                     FROM workers
                     WHERE deleted_at IS NULL AND
                           (workspace_id > ? OR (workspace_id = ? AND
                            (created_at > ? OR (created_at = ? AND rowid > ?))))
                     ORDER BY workspace_id,created_at,rowid
                     LIMIT ?
                     """);
             PreparedStatement reserveName = connection.prepareStatement("""
                     INSERT OR IGNORE INTO __termestra_migration_v28_worker_names(workspace_id,name)
                     VALUES (?,?)
                     """);
             PreparedStatement readSuffix = connection.prepareStatement("""
                     SELECT next_suffix
                     FROM __termestra_migration_v28_name_suffixes
                     WHERE workspace_id=? AND base_name=?
                     """);
             PreparedStatement saveSuffix = connection.prepareStatement("""
                     INSERT INTO __termestra_migration_v28_name_suffixes(workspace_id,base_name,next_suffix)
                     VALUES (?,?,?)
                     ON CONFLICT(workspace_id,base_name) DO UPDATE SET next_suffix=excluded.next_suffix
                     """);
             PreparedStatement update = connection.prepareStatement("UPDATE workers SET name=? WHERE id=?")) {
            while (true) {
                List<LegacyWorkerName> page = readWorkerPage(firstPage, nextPage, cursor);
                if (page.isEmpty()) return;
                for (LegacyWorkerName worker : page) {
                    if (reserveWorkerName(reserveName, worker.workspaceId(), worker.name())) continue;
                    long suffix = nextWorkerNameSuffix(readSuffix, worker.workspaceId(), worker.name());
                    String candidate;
                    do {
                        candidate = worker.name() + " (" + suffix++ + ")";
                    } while (!reserveWorkerName(reserveName, worker.workspaceId(), candidate));
                    saveWorkerNameSuffix(saveSuffix, worker.workspaceId(), worker.name(), suffix);
                    update.setString(1, candidate);
                    update.setString(2, worker.id());
                    update.addBatch();
                }
                update.executeBatch();
                update.clearBatch();
                cursor = page.getLast();
            }
        }
    }

    private static List<LegacyWorkerName> readWorkerPage(PreparedStatement firstPage,
                                                          PreparedStatement nextPage,
                                                          LegacyWorkerName cursor)
            throws SQLException {
        PreparedStatement query;
        if (cursor == null) {
            query = firstPage;
            query.setInt(1, MIGRATION_BATCH_SIZE);
        } else {
            query = nextPage;
            query.setString(1, cursor.workspaceId());
            query.setString(2, cursor.workspaceId());
            query.setLong(3, cursor.createdAt());
            query.setLong(4, cursor.createdAt());
            query.setLong(5, cursor.rowId());
            query.setInt(6, MIGRATION_BATCH_SIZE);
        }
        List<LegacyWorkerName> page = new ArrayList<>(MIGRATION_BATCH_SIZE);
        try (ResultSet result = query.executeQuery()) {
            while (result.next()) {
                page.add(new LegacyWorkerName(
                        result.getLong("migration_rowid"),
                        result.getString("id"),
                        result.getString("workspace_id"),
                        result.getString("name"),
                        result.getLong("created_at")));
            }
        }
        return page;
    }

    private static boolean reserveWorkerName(PreparedStatement reserveName, String workspaceId, String name)
            throws SQLException {
        reserveName.setString(1, workspaceId);
        reserveName.setString(2, name);
        return reserveName.executeUpdate() == 1;
    }

    private static long nextWorkerNameSuffix(PreparedStatement readSuffix, String workspaceId, String baseName)
            throws SQLException {
        readSuffix.setString(1, workspaceId);
        readSuffix.setString(2, baseName);
        try (ResultSet result = readSuffix.executeQuery()) {
            return result.next() ? result.getLong("next_suffix") : 2;
        }
    }

    private static void saveWorkerNameSuffix(PreparedStatement saveSuffix, String workspaceId,
                                              String baseName, long nextSuffix) throws SQLException {
        saveSuffix.setString(1, workspaceId);
        saveSuffix.setString(2, baseName);
        saveSuffix.setLong(3, nextSuffix);
        saveSuffix.executeUpdate();
    }

    private record LegacyWorkerName(long rowId, String id, String workspaceId, String name, long createdAt) { }

    private static boolean tableExists(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
