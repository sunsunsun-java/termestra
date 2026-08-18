package dev.termestra.platform.persistence.sqlite;

import java.sql.*;
import java.util.*;

import static dev.termestra.platform.persistence.sqlite.SchemaSupport.*;

final class DispatchSchemaMigrations {
    private static final int MIGRATION_BATCH_SIZE = 256;

    List<SchemaMigration> migrations() {
        return List.of(
                new SchemaMigration(14, this::v14),
                new SchemaMigration(15, this::v15),
                new SchemaMigration(26, this::v26),
                new SchemaMigration(29, this::v29));
    }

    private void v14(Connection connection) throws SQLException {
        createDispatches(connection);
        try (Statement count = connection.createStatement(); ResultSet result = count.executeQuery("SELECT COUNT(*) FROM dispatches")) {
            if (result.next() && result.getLong(1) > 0) return;
        }
        execute(connection, "DROP TABLE IF EXISTS __termestra_migration_v14_open_dispatches");
        execute(connection, """
                CREATE TABLE __termestra_migration_v14_open_dispatches (
                    queue_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    workspace_id TEXT NOT NULL,
                    worker_id TEXT NOT NULL,
                    dispatch_id TEXT NOT NULL
                )
                """);
        execute(connection, """
                CREATE INDEX __termestra_migration_v14_open_dispatches_lookup
                ON __termestra_migration_v14_open_dispatches(workspace_id,worker_id,queue_sequence)
                """);
        backfillDispatches(connection);
        execute(connection, "DROP TABLE __termestra_migration_v14_open_dispatches");
    }

    private void backfillDispatches(Connection connection) throws SQLException {
        Long cursor = null;
        try (PreparedStatement firstPage = connection.prepareStatement("""
                     SELECT sequence,workspace_id,worker_id,type,from_agent_id,to_agent_id,text,artifacts,created_at
                     FROM messages
                     WHERE type IN ('send','report')
                     ORDER BY sequence
                     LIMIT ?
                     """);
             PreparedStatement nextPage = connection.prepareStatement("""
                     SELECT sequence,workspace_id,worker_id,type,from_agent_id,to_agent_id,text,artifacts,created_at
                     FROM messages
                     WHERE type IN ('send','report') AND sequence>?
                     ORDER BY sequence
                     LIMIT ?
                     """);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO dispatches(id,workspace_id,from_agent_id,to_agent_id,text,status,created_at,artifacts)
                     VALUES(?,?,?,?,?,'queued',?,'[]')
                     """);
             PreparedStatement enqueue = connection.prepareStatement("""
                     INSERT INTO __termestra_migration_v14_open_dispatches(workspace_id,worker_id,dispatch_id)
                     VALUES(?,?,?)
                     """);
             PreparedStatement nextOpen = connection.prepareStatement("""
                     SELECT queue_sequence,dispatch_id
                     FROM __termestra_migration_v14_open_dispatches
                     WHERE workspace_id=? AND worker_id=?
                     ORDER BY queue_sequence
                     LIMIT 1
                     """);
             PreparedStatement removeOpen = connection.prepareStatement("""
                     DELETE FROM __termestra_migration_v14_open_dispatches
                     WHERE queue_sequence=?
                     """);
             PreparedStatement report = connection.prepareStatement("""
                     UPDATE dispatches
                     SET status='reported',reported_at=?,report_text=?,artifacts=?
                     WHERE id=?
                     """)) {
            while (true) {
                List<LegacyDispatchMessage> page = readMessagePage(firstPage, nextPage, cursor);
                if (page.isEmpty()) return;
                for (LegacyDispatchMessage message : page) {
                    backfillMessage(message, insert, enqueue, nextOpen, removeOpen, report);
                }
                cursor = page.getLast().sequence();
            }
        }
    }

    private static List<LegacyDispatchMessage> readMessagePage(PreparedStatement firstPage,
                                                                PreparedStatement nextPage,
                                                                Long cursor) throws SQLException {
        PreparedStatement query;
        if (cursor == null) {
            query = firstPage;
            query.setInt(1, MIGRATION_BATCH_SIZE);
        } else {
            query = nextPage;
            query.setLong(1, cursor);
            query.setInt(2, MIGRATION_BATCH_SIZE);
        }
        List<LegacyDispatchMessage> page = new ArrayList<>(MIGRATION_BATCH_SIZE);
        try (ResultSet result = query.executeQuery()) {
            while (result.next()) {
                page.add(new LegacyDispatchMessage(
                        result.getLong("sequence"),
                        result.getString("workspace_id"),
                        result.getString("worker_id"),
                        result.getString("type"),
                        result.getString("from_agent_id"),
                        result.getString("to_agent_id"),
                        result.getString("text"),
                        result.getString("artifacts"),
                        result.getLong("created_at")));
            }
        }
        return page;
    }

    private void backfillMessage(LegacyDispatchMessage message, PreparedStatement insert,
                                 PreparedStatement enqueue, PreparedStatement nextOpen,
                                 PreparedStatement removeOpen, PreparedStatement report)
            throws SQLException {
        if ("send".equals(message.type())) {
            String id = UUID.randomUUID().toString();
            String target = message.toAgentId();
            insert.setString(1,id); insert.setString(2,message.workspaceId()); insert.setString(3,message.fromAgentId());
            insert.setString(4,target == null ? message.workerId() : target); insert.setString(5,Objects.requireNonNullElse(message.text(),"")); insert.setLong(6,message.createdAt()); insert.executeUpdate();
            enqueue.setString(1, message.workspaceId());
            enqueue.setString(2, message.workerId());
            enqueue.setString(3, id);
            enqueue.executeUpdate();
            return;
        }
        OpenDispatch open = findOpenDispatch(nextOpen, message.workspaceId(), message.workerId());
        if (open == null) return;
        report.setLong(1,message.createdAt()); report.setString(2,Objects.requireNonNullElse(message.text(),""));
        report.setString(3,Objects.requireNonNullElse(message.artifacts(),"[]")); report.setString(4,open.dispatchId()); report.executeUpdate();
        removeOpen.setLong(1, open.queueSequence());
        removeOpen.executeUpdate();
    }

    private static OpenDispatch findOpenDispatch(PreparedStatement nextOpen, String workspaceId,
                                                  String workerId) throws SQLException {
        nextOpen.setString(1, workspaceId);
        nextOpen.setString(2, workerId);
        try (ResultSet result = nextOpen.executeQuery()) {
            if (!result.next()) return null;
            return new OpenDispatch(result.getLong("queue_sequence"), result.getString("dispatch_id"));
        }
    }

    private record LegacyDispatchMessage(long sequence, String workspaceId, String workerId, String type,
                                         String fromAgentId, String toAgentId, String text, String artifacts,
                                         long createdAt) { }

    private record OpenDispatch(long queueSequence, String dispatchId) { }

    private void v15(Connection connection) throws SQLException {
        if (hasColumn(connection,"dispatches","sequence")) return;
        execute(connection,"DROP INDEX IF EXISTS idx_dispatches_workspace_created_at");
        execute(connection,"DROP INDEX IF EXISTS idx_dispatches_open_by_worker");
        execute(connection,"ALTER TABLE dispatches RENAME TO dispatches_v14");
        createDispatches(connection);
        execute(connection,"INSERT INTO dispatches(id,workspace_id,from_agent_id,to_agent_id,text,status,created_at,delivered_at,submitted_at,reported_at,report_text,artifacts) SELECT id,workspace_id,from_agent_id,to_agent_id,text,status,created_at,delivered_at,submitted_at,reported_at,report_text,artifacts FROM dispatches_v14 ORDER BY created_at,rowid");
        execute(connection,"DROP TABLE dispatches_v14");
    }

    private void v26(Connection connection) throws SQLException {
        if (!hasColumn(connection,"dispatches","sequence")) createDispatches(connection);
        execute(connection,"DROP INDEX IF EXISTS idx_dispatches_open_by_worker");
        execute(connection,"CREATE INDEX idx_dispatches_open_by_worker ON dispatches(workspace_id,to_agent_id,sequence) WHERE status IN ('queued','submitted')");
    }

    private void v29(Connection connection) throws SQLException {
        if (!hasTable(connection, "dispatches")) createDispatches(connection);
        if (!hasTable(connection, "messages")) {
            execute(connection, """
                    CREATE TABLE messages (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_id TEXT NOT NULL,
                        worker_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        from_agent_id TEXT,
                        to_agent_id TEXT,
                        text TEXT,
                        status TEXT,
                        artifacts TEXT,
                        created_at INTEGER NOT NULL
                    )
                    """);
        }
        if (!hasColumn(connection, "dispatches", "idempotency_key")) {
            execute(connection, "ALTER TABLE dispatches ADD COLUMN idempotency_key TEXT");
        }
        if (!hasColumn(connection, "messages", "dispatch_id")) {
            execute(connection, "ALTER TABLE messages ADD COLUMN dispatch_id TEXT");
        }
        execute(connection, """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_dispatches_idempotency
                ON dispatches(workspace_id,from_agent_id,idempotency_key)
                WHERE idempotency_key IS NOT NULL
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_messages_dispatch ON messages(dispatch_id)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS dispatch_deliveries (
                    dispatch_id TEXT PRIMARY KEY,
                    workspace_id TEXT NOT NULL,
                    to_agent_id TEXT NOT NULL,
                    runtime_port TEXT NOT NULL,
                    state TEXT NOT NULL,
                    attempt_id TEXT,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    input_attempted INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    next_attempt_at INTEGER NOT NULL,
                    lease_owner TEXT,
                    lease_expires_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    CHECK(state IN ('pending','delivering','retry_wait','submitted','uncertain','failed','closed')),
                    CHECK(attempt_count >= 0),
                    CHECK(input_attempted IN (0,1))
                )
                """);
        execute(connection, """
                CREATE INDEX IF NOT EXISTS idx_dispatch_deliveries_ready
                ON dispatch_deliveries(state,next_attempt_at,created_at,dispatch_id)
                """);
        execute(connection, """
                CREATE INDEX IF NOT EXISTS idx_dispatch_deliveries_worker
                ON dispatch_deliveries(workspace_id,to_agent_id,state)
                """);
        // Legacy queued rows may already have reached a PTY. Requiring an explicit
        // retry is safer than executing the same task twice after migration.
        execute(connection, """
                INSERT OR IGNORE INTO dispatch_deliveries(
                    dispatch_id,workspace_id,to_agent_id,runtime_port,state,attempt_count,
                    input_attempted,last_error,next_attempt_at,created_at,updated_at)
                SELECT id,workspace_id,to_agent_id,'3000',
                       CASE status WHEN 'submitted' THEN 'submitted' ELSE 'uncertain' END,
                       0,CASE status WHEN 'queued' THEN 1 ELSE 0 END,
                       CASE status WHEN 'queued' THEN
                         'Legacy queued dispatch requires explicit retry after migration'
                       ELSE NULL END,
                       created_at,created_at,created_at
                FROM dispatches
                WHERE status IN ('queued','submitted')
                """);
    }

    private void createDispatches(Connection connection) throws SQLException {
        execute(connection,"CREATE TABLE IF NOT EXISTS dispatches (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, workspace_id TEXT NOT NULL, from_agent_id TEXT, to_agent_id TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, delivered_at INTEGER, submitted_at INTEGER, reported_at INTEGER, report_text TEXT, artifacts TEXT)");
        execute(connection,"CREATE INDEX IF NOT EXISTS idx_dispatches_workspace_created_at ON dispatches(workspace_id,sequence)");
        execute(connection,"CREATE INDEX IF NOT EXISTS idx_dispatches_open_by_worker ON dispatches(workspace_id,to_agent_id,status,sequence)");
    }
}
