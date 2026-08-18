package dev.termestra.team.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.shared.id.*;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.team.application.exception.InvalidDispatchRecord;
import dev.termestra.team.application.exception.InactiveDeliveryAttempt;
import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.port.in.TeamInputLimits;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class JdbcTeamLedger implements TeamLedger, OpenDispatchCountSource {
    private static final int SUMMARY_TEXT_LIMIT = 512;
    private static final int SUMMARY_REPORT_LIMIT = 512;
    private static final int SUMMARY_ARTIFACTS_JSON_LIMIT = 2048;
    private static final int DETAIL_TEXT_LIMIT = 65_536;
    private static final int DETAIL_REPORT_LIMIT = 65_536;
    private static final int DETAIL_ARTIFACTS_JSON_LIMIT = 131_072;
    private static final int MAX_DISPATCH_ID_CHARACTERS = 256;
    private static final int MAX_WORKSPACE_ID_CHARACTERS = 256;
    private static final int MAX_AGENT_ID_CHARACTERS = TeamInputLimits.MAX_MEMBER_ID_CHARACTERS;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final SqliteDatabase database;
    private final ObjectMapper json;
    public JdbcTeamLedger(SqliteDatabase database, ObjectMapper json) { this.database=database; this.json=json; }

    @Override public DispatchEnqueueResult enqueue(Dispatch dispatch, TeamMessage message, String runtimePort,
                                                   String idempotencyKey) {
        return database.write("enqueue dispatch delivery", connection -> {
            if (idempotencyKey != null) {
                Optional<ExistingEnqueue> existing = findExistingEnqueue(connection, dispatch, idempotencyKey);
                if (existing.isPresent()) {
                    ExistingEnqueue replay = existing.orElseThrow();
                    if (!replay.toAgentId().equals(dispatch.toAgentId().toString())
                            || !replay.text().equals(dispatch.task().value())) {
                        throw new TeamConflict("idempotency_key was already used for a different dispatch");
                    }
                    return new DispatchEnqueueResult(replay.dispatchId(), replay.messageSequence(), false);
                }
            }
            insertDispatch(connection, dispatch, idempotencyKey);
            TeamMessage linked = new TeamMessage(message.workspaceId(), message.workerId(), message.type(),
                    message.fromAgentId(), message.toAgentId(), message.text(), message.status(),
                    message.artifacts(), message.createdAt(), dispatch.id().toString());
            long sequence = insertMessage(connection, linked);
            insertDelivery(connection, dispatch, runtimePort);
            return new DispatchEnqueueResult(dispatch.id().toString(), sequence, true);
        });
    }

    @Override public long create(Dispatch dispatch, TeamMessage message) {
        return enqueue(dispatch, message, "3000", null).messageSequence();
    }

    @Override public Optional<DispatchDeliveryWork> claimNextDelivery(String leaseOwner, Instant now,
                                                                      Instant leaseExpiresAt) {
        return database.write("claim dispatch delivery", connection -> {
            try (PreparedStatement expired = connection.prepareStatement("""
                    UPDATE dispatch_deliveries
                    SET state='uncertain',input_attempted=1,
                        last_error='Delivery lease expired with an unknown terminal outcome',
                        lease_owner=NULL,lease_expires_at=NULL,updated_at=?
                    WHERE state='delivering' AND lease_expires_at<?
                    """)) {
                expired.setLong(1, now.toEpochMilli());
                expired.setLong(2, now.toEpochMilli());
                expired.executeUpdate();
            }
            String dispatchId;
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT delivery.dispatch_id
                    FROM dispatch_deliveries delivery
                    JOIN dispatches dispatch ON dispatch.id=delivery.dispatch_id
                    JOIN workers worker ON worker.workspace_id=delivery.workspace_id
                                       AND worker.id=delivery.to_agent_id
                                       AND worker.deleted_at IS NULL
                    WHERE delivery.state IN ('pending','retry_wait')
                      AND delivery.next_attempt_at<=?
                      AND dispatch.status='queued'
                      AND NOT EXISTS(
                        SELECT 1
                        FROM dispatch_deliveries older_delivery
                        JOIN dispatches older_dispatch ON older_dispatch.id=older_delivery.dispatch_id
                        WHERE older_delivery.workspace_id=delivery.workspace_id
                          AND older_delivery.to_agent_id=delivery.to_agent_id
                          AND older_dispatch.status='queued'
                          AND older_dispatch.sequence<dispatch.sequence
                          AND older_delivery.state IN ('pending','delivering','retry_wait','uncertain','failed'))
                      AND NOT EXISTS(
                        SELECT 1 FROM dispatch_deliveries active
                        WHERE active.workspace_id=delivery.workspace_id
                          AND active.to_agent_id=delivery.to_agent_id
                          AND active.state='delivering')
                    ORDER BY delivery.next_attempt_at,delivery.created_at,delivery.dispatch_id
                    LIMIT 1
                    """)) {
                select.setLong(1, now.toEpochMilli());
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    dispatchId = rows.getString(1);
                }
            }
            String attemptId = UUID.randomUUID().toString();
            try (PreparedStatement claim = connection.prepareStatement("""
                    UPDATE dispatch_deliveries
                    SET state='delivering',attempt_id=?,attempt_count=attempt_count+1,
                        lease_owner=?,lease_expires_at=?,updated_at=?
                    WHERE dispatch_id=? AND state IN ('pending','retry_wait')
                    """)) {
                claim.setString(1, attemptId);
                claim.setString(2, leaseOwner);
                claim.setLong(3, leaseExpiresAt.toEpochMilli());
                claim.setLong(4, now.toEpochMilli());
                claim.setString(5, dispatchId);
                if (claim.executeUpdate() != 1) return Optional.empty();
            }
            try (PreparedStatement load = connection.prepareStatement("""
                    SELECT dispatch.sequence,dispatch.id,dispatch.workspace_id,dispatch.from_agent_id,
                           dispatch.to_agent_id,dispatch.text,dispatch.status,dispatch.created_at,
                           dispatch.submitted_at,dispatch.delivered_at,dispatch.reported_at,
                           dispatch.report_text,dispatch.artifacts,
                           delivery.runtime_port,delivery.attempt_id,delivery.attempt_count
                    FROM dispatches dispatch
                    JOIN dispatch_deliveries delivery ON delivery.dispatch_id=dispatch.id
                    WHERE dispatch.id=?
                    """)) {
                load.setString(1, dispatchId);
                try (ResultSet rows = load.executeQuery()) {
                    if (!rows.next()) throw new SQLException("claimed dispatch delivery disappeared");
                    return Optional.of(new DispatchDeliveryWork(map(rows), rows.getString("runtime_port"),
                            rows.getString("attempt_id"), rows.getInt("attempt_count")));
                }
            }
        });
    }

    @Override public void markDeliverySubmitted(String attemptId, Instant submittedAt) {
        database.write("complete dispatch delivery", connection -> {
            String dispatchId = transitionAttempt(connection, attemptId, "submitted", null, null,
                    true, submittedAt);
            if (dispatchId == null) {
                throw new InactiveDeliveryAttempt(attemptId);
            }
            try (PreparedStatement dispatch = connection.prepareStatement("""
                    UPDATE dispatches
                    SET status='submitted',submitted_at=?,delivered_at=?
                    WHERE id=? AND status='queued'
                    """)) {
                dispatch.setLong(1, submittedAt.toEpochMilli());
                dispatch.setLong(2, submittedAt.toEpochMilli());
                dispatch.setString(3, dispatchId);
                dispatch.executeUpdate();
            }
            return null;
        });
    }

    @Override public void deferDeliveryClaim(String attemptId, String reason, Instant nextAttemptAt,
                                             Instant updatedAt) {
        database.write("defer dispatch delivery claim", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE dispatch_deliveries
                    SET state='retry_wait',attempt_id=NULL,
                        attempt_count=CASE WHEN attempt_count>0 THEN attempt_count-1 ELSE 0 END,
                        input_attempted=0,last_error=?,next_attempt_at=?,
                        lease_owner=NULL,lease_expires_at=NULL,updated_at=?
                    WHERE attempt_id=? AND state='delivering'
                    """)) {
                statement.setString(1, reason);
                statement.setLong(2, nextAttemptAt.toEpochMilli());
                statement.setLong(3, updatedAt.toEpochMilli());
                statement.setString(4, attemptId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Dispatch delivery claim is no longer active: " + attemptId);
                }
            }
            return null;
        });
    }

    @Override public void rescheduleDelivery(String attemptId, String error, Instant nextAttemptAt,
                                             Instant updatedAt) {
        database.write("reschedule dispatch delivery", connection -> {
            transitionAttempt(connection, attemptId, "retry_wait", error, nextAttemptAt,
                    false, updatedAt);
            return null;
        });
    }

    @Override public void markDeliveryUncertain(String attemptId, String error, Instant updatedAt) {
        database.write("mark dispatch delivery uncertain", connection -> {
            transitionAttempt(connection, attemptId, "uncertain", error, null, true, updatedAt);
            return null;
        });
    }

    @Override public void markDeliveryFailed(String attemptId, String error, Instant updatedAt) {
        database.write("mark dispatch delivery failed", connection -> {
            transitionAttempt(connection, attemptId, "failed", error, null, false, updatedAt);
            return null;
        });
    }

    @Override public int recoverInterruptedDeliveries(Instant recoveredAt) {
        return database.write("recover interrupted dispatch deliveries", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE dispatch_deliveries
                    SET state='uncertain',input_attempted=1,
                        last_error='Termestra restarted while terminal delivery was in progress',
                        lease_owner=NULL,lease_expires_at=NULL,updated_at=?
                    WHERE state='delivering'
                    """)) {
                statement.setLong(1, recoveredAt.toEpochMilli());
                return statement.executeUpdate();
            }
        });
    }

    @Override public boolean retryDelivery(String workspaceId, String dispatchId, Instant retriedAt) {
        return database.write("retry dispatch delivery", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE dispatch_deliveries
                    SET state='pending',attempt_id=NULL,attempt_count=0,input_attempted=0,
                        last_error=NULL,next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL,updated_at=?
                    WHERE workspace_id=? AND dispatch_id=? AND state IN ('uncertain','failed')
                      AND EXISTS(SELECT 1 FROM dispatches
                                 WHERE dispatches.id=dispatch_deliveries.dispatch_id
                                   AND dispatches.status='queued')
                    """)) {
                statement.setLong(1, retriedAt.toEpochMilli());
                statement.setLong(2, retriedAt.toEpochMilli());
                statement.setString(3, workspaceId);
                statement.setString(4, dispatchId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override public void discardCreated(String dispatchId, long messageSequence) {
        database.write("discard failed dispatch", c -> {
            try (PreparedStatement delivery=c.prepareStatement("DELETE FROM dispatch_deliveries WHERE dispatch_id=?");
                 PreparedStatement dispatch=c.prepareStatement("DELETE FROM dispatches WHERE id=?");
                 PreparedStatement message=c.prepareStatement("DELETE FROM messages WHERE sequence=?")) {
                delivery.setString(1,dispatchId); delivery.executeUpdate();
                dispatch.setString(1,dispatchId); dispatch.executeUpdate();
                message.setLong(1,messageSequence); message.executeUpdate();
            }
            return null;
        });
    }

    @Override public Optional<StoredDispatch> reportOne(String workspaceId, String workerId, String dispatchId,
                                                        String result, List<String> artifacts, Instant reportedAt,
                                                        TeamMessage message) {
        String returning = storedTransitionColumns("artifacts");
        return database.write("report dispatch", connection -> {
            String sql = dispatchId == null ? """
                    UPDATE dispatches
                    SET status='reported',reported_at=?,report_text=?,artifacts=?
                    WHERE sequence=(
                      SELECT sequence FROM dispatches
                      WHERE workspace_id=? AND to_agent_id=? AND status IN ('queued','submitted')
                      ORDER BY sequence LIMIT 1
                    ) AND status IN ('queued','submitted')
                    RETURNING %s
                    """.formatted(returning) : """
                    UPDATE dispatches
                    SET status='reported',reported_at=?,report_text=?,artifacts=?
                    WHERE workspace_id=? AND to_agent_id=? AND id=? AND status IN ('queued','submitted')
                    RETURNING %s
                    """.formatted(returning);
            Optional<StoredDispatch> updated;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, reportedAt.toEpochMilli());
                statement.setString(2, result);
                statement.setString(3, json(artifacts));
                statement.setString(4, workspaceId);
                statement.setString(5, workerId);
                if (dispatchId != null) statement.setString(6, dispatchId);
                try (ResultSet rows = statement.executeQuery()) {
                    updated = rows.next() ? Optional.of(map(rows)) : Optional.empty();
                }
            }
            if (updated.isPresent()) {
                insertMessage(connection, message);
                closeDelivery(connection, updated.orElseThrow().dispatch().id().toString(), reportedAt);
            }
            return updated;
        });
    }

    @Override public Optional<StoredDispatch> cancelOne(String workspaceId, String dispatchId,
                                                        String reason, Instant cancelledAt) {
        return database.write("cancel dispatch", connection -> {
            String sql = """
                    UPDATE dispatches
                    SET status='cancelled',reported_at=?,report_text=?
                    WHERE workspace_id=? AND id=? AND status IN ('queued','submitted')
                    RETURNING %s
                    """.formatted(storedTransitionColumns("'[]'"));
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, cancelledAt.toEpochMilli());
                statement.setString(2, reason);
                statement.setString(3, workspaceId);
                statement.setString(4, dispatchId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    StoredDispatch cancelled = map(rows);
                    closeDelivery(connection, cancelled.dispatch().id().toString(), cancelledAt);
                    return Optional.of(cancelled);
                }
            }
        });
    }

    @Override public Optional<String> findOpenRecipient(String workspaceId, String dispatchId) {
        if (!boundedIdentifier(workspaceId, MAX_WORKSPACE_ID_CHARACTERS)
                || !boundedIdentifier(dispatchId, MAX_DISPATCH_ID_CHARACTERS)) return Optional.empty();
        return database.read("find open dispatch recipient", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT CASE WHEN length(to_agent_id) BETWEEN 1 AND ?
                                THEN to_agent_id END AS to_agent_id
                    FROM dispatches
                    WHERE workspace_id=? AND id=? AND status IN ('queued','submitted')
                    """)) {
                statement.setInt(1, MAX_AGENT_ID_CHARACTERS);
                statement.setString(2, workspaceId);
                statement.setString(3, dispatchId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    String recipient = rows.getString("to_agent_id");
                    if (recipient == null) throw new InvalidDispatchRecord();
                    return Optional.of(recipient);
                }
            }
        });
    }

    @Override public Map<String, Integer> loadOpenCounts(String workspaceId) {
        return database.read("load pending task projection", connection -> {
            Map<String, Integer> counts = new HashMap<>();
            String sql = """
                    WITH selected_workers AS (
                      SELECT rowid,id,workspace_id,created_at
                      FROM workers
                      WHERE workspace_id=? AND deleted_at IS NULL
                        AND length(id) BETWEEN 1 AND ?
                        AND length(name) BETWEEN 1 AND ?
                        AND unicode(substr(name,1,1))>32
                        AND unicode(substr(name,-1,1))>32
                        AND role IN ('coder','reviewer','tester','custom')
                      ORDER BY created_at,rowid
                      LIMIT ?
                    )
                    SELECT worker.id AS to_agent_id,
                           MIN(COUNT(dispatch.sequence), ?) AS pending
                    FROM selected_workers worker
                    JOIN dispatches dispatch
                      ON dispatch.workspace_id=worker.workspace_id
                     AND dispatch.to_agent_id=worker.id
                    WHERE dispatch.status IN ('queued','submitted')
                    GROUP BY worker.rowid,worker.id,worker.created_at
                    ORDER BY worker.created_at,worker.rowid
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, workspaceId);
                statement.setInt(2, TeamInputLimits.MAX_MEMBER_ID_CHARACTERS);
                statement.setInt(3, TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS);
                statement.setInt(4, OpenDispatchCountSource.MAX_TRACKED_WORKERS_PER_WORKSPACE);
                statement.setInt(5, OpenDispatchCountSource.MAX_PENDING_TASKS_PER_WORKER);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) counts.put(rows.getString("to_agent_id"), rows.getInt("pending"));
                }
            }
            return Map.copyOf(counts);
        });
    }

    @Override public void append(TeamMessage message) { database.write("append team message",c->{insertMessage(c,message);return null;}); }

    @Override public List<DispatchSummaryProjection> listSummaries(String workspaceId, String state, int limit, int offset) {
        if (!boundedIdentifier(workspaceId, MAX_WORKSPACE_ID_CHARACTERS)) return List.of();
        String columns = summaryColumns();
        String visible = "workspace_id=?" +
                " AND length(id) BETWEEN 1 AND " + MAX_DISPATCH_ID_CHARACTERS +
                " AND length(workspace_id) BETWEEN 1 AND " + MAX_WORKSPACE_ID_CHARACTERS +
                " AND (from_agent_id IS NULL OR length(from_agent_id) BETWEEN 1 AND " + MAX_AGENT_ID_CHARACTERS + ")" +
                " AND length(to_agent_id) BETWEEN 1 AND " + MAX_AGENT_ID_CHARACTERS +
                " AND text IS NOT NULL" +
                " AND status IN ('queued','submitted','reported','cancelled')";
        return database.read("list dispatch summaries", c -> state == null
                ? selectSummaries(c,"SELECT "+columns+" FROM dispatches WHERE "+visible+" ORDER BY sequence LIMIT ? OFFSET ?",workspaceId,null,limit,offset)
                : selectSummaries(c,"SELECT "+columns+" FROM dispatches WHERE "+visible+" AND status=? ORDER BY sequence LIMIT ? OFFSET ?",workspaceId,state,limit,offset));
    }

    @Override public List<DispatchSummaryProjection> listDeliveryIssues(String workspaceId, int limit) {
        if (!boundedIdentifier(workspaceId, MAX_WORKSPACE_ID_CHARACTERS)) return List.of();
        String columns = summaryColumns();
        String sql = "SELECT " + columns + " FROM dispatches " +
                "WHERE dispatches.workspace_id=? AND dispatches.status='queued' " +
                "AND length(dispatches.id) BETWEEN 1 AND " + MAX_DISPATCH_ID_CHARACTERS + " " +
                "AND length(dispatches.workspace_id) BETWEEN 1 AND " + MAX_WORKSPACE_ID_CHARACTERS + " " +
                "AND (dispatches.from_agent_id IS NULL OR length(dispatches.from_agent_id) BETWEEN 1 AND " + MAX_AGENT_ID_CHARACTERS + ") " +
                "AND length(dispatches.to_agent_id) BETWEEN 1 AND " + MAX_AGENT_ID_CHARACTERS + " " +
                "AND dispatches.text IS NOT NULL " +
                "AND EXISTS (SELECT 1 FROM dispatch_deliveries delivery " +
                "WHERE delivery.dispatch_id=dispatches.id AND delivery.state IN ('uncertain','failed')) " +
                "ORDER BY dispatches.sequence LIMIT ? OFFSET ?";
        return database.read("list dispatch delivery issues", connection ->
                selectSummaries(connection, sql, workspaceId, null, limit, 0));
    }

    private static String summaryColumns() {
        return "id,workspace_id,from_agent_id,to_agent_id," +
                "substr(text,1," + SUMMARY_TEXT_LIMIT + ") AS text,status,created_at,delivered_at,submitted_at,reported_at," +
                "CASE WHEN report_text IS NULL THEN NULL ELSE substr(report_text,1," + SUMMARY_REPORT_LIMIT + ") END AS report_text," +
                "CASE WHEN length(COALESCE(artifacts,'[]'))<=" + SUMMARY_ARTIFACTS_JSON_LIMIT +
                " AND json_valid(COALESCE(artifacts,'[]')) AND json_type(COALESCE(artifacts,'[]'))='array'" +
                " THEN COALESCE(artifacts,'[]') ELSE '[]' END AS artifacts," +
                "CASE WHEN length(text)>" + SUMMARY_TEXT_LIMIT +
                " OR length(COALESCE(report_text,''))>" + SUMMARY_REPORT_LIMIT +
                " OR length(COALESCE(artifacts,'[]'))>" + SUMMARY_ARTIFACTS_JSON_LIMIT +
                " OR NOT json_valid(COALESCE(artifacts,'[]'))" +
                " OR COALESCE(json_type(COALESCE(artifacts,'[]')),'')<>'array'" +
                " THEN 1 ELSE 0 END AS truncated," +
                "(SELECT state FROM dispatch_deliveries WHERE dispatch_id=dispatches.id) AS delivery_state," +
                "COALESCE((SELECT attempt_count FROM dispatch_deliveries WHERE dispatch_id=dispatches.id),0) AS delivery_attempt_count," +
                "(SELECT substr(last_error,1,2048) FROM dispatch_deliveries WHERE dispatch_id=dispatches.id) AS delivery_error," +
                "(SELECT next_attempt_at FROM dispatch_deliveries WHERE dispatch_id=dispatches.id) AS delivery_next_attempt_at," +
                "COALESCE((SELECT input_attempted FROM dispatch_deliveries WHERE dispatch_id=dispatches.id),0) AS delivery_input_attempted";
    }

    @Override public Optional<DispatchDetailProjection> findDetailById(String workspaceId, String dispatchId) {
        if (!boundedIdentifier(workspaceId, MAX_WORKSPACE_ID_CHARACTERS)
                || !boundedIdentifier(dispatchId, MAX_DISPATCH_ID_CHARACTERS)) return Optional.empty();
        String columns =
                "CASE WHEN length(id) BETWEEN 1 AND " + MAX_DISPATCH_ID_CHARACTERS + " THEN id END AS id," +
                "CASE WHEN length(workspace_id) BETWEEN 1 AND " + MAX_WORKSPACE_ID_CHARACTERS + " THEN workspace_id END AS workspace_id," +
                "CASE WHEN from_agent_id IS NULL OR length(from_agent_id) BETWEEN 1 AND " + MAX_AGENT_ID_CHARACTERS + " THEN from_agent_id END AS from_agent_id," +
                "CASE WHEN from_agent_id IS NULL OR length(from_agent_id) BETWEEN 1 AND " + MAX_AGENT_ID_CHARACTERS + " THEN 1 ELSE 0 END AS from_agent_id_valid," +
                "CASE WHEN length(to_agent_id) BETWEEN 1 AND " + MAX_AGENT_ID_CHARACTERS + " THEN to_agent_id END AS to_agent_id," +
                "substr(text,1," + DETAIL_TEXT_LIMIT + ") AS text," +
                "CASE WHEN status IN ('queued','submitted','reported','cancelled') THEN status END AS status," +
                "created_at,delivered_at,submitted_at,reported_at," +
                "CASE WHEN report_text IS NULL THEN NULL ELSE substr(report_text,1," + DETAIL_REPORT_LIMIT + ") END AS report_text," +
                "CASE WHEN length(COALESCE(artifacts,'[]'))<=" + DETAIL_ARTIFACTS_JSON_LIMIT +
                " AND json_valid(COALESCE(artifacts,'[]')) AND json_type(COALESCE(artifacts,'[]'))='array'" +
                " THEN COALESCE(artifacts,'[]') ELSE '[]' END AS artifacts," +
                "CASE WHEN length(COALESCE(artifacts,'[]'))>" + DETAIL_ARTIFACTS_JSON_LIMIT +
                " THEN 1 WHEN json_valid(COALESCE(artifacts,'[]'))" +
                " AND json_type(COALESCE(artifacts,'[]'))='array' THEN 1 ELSE 0 END AS artifacts_valid," +
                "CASE WHEN length(text)>" + DETAIL_TEXT_LIMIT +
                " OR length(COALESCE(report_text,''))>" + DETAIL_REPORT_LIMIT +
                " OR length(COALESCE(artifacts,'[]'))>" + DETAIL_ARTIFACTS_JSON_LIMIT +
                " THEN 1 ELSE 0 END AS truncated," +
                "(SELECT state FROM dispatch_deliveries WHERE dispatch_id=dispatches.id) AS delivery_state," +
                "COALESCE((SELECT attempt_count FROM dispatch_deliveries WHERE dispatch_id=dispatches.id),0) AS delivery_attempt_count," +
                "(SELECT substr(last_error,1,2048) FROM dispatch_deliveries WHERE dispatch_id=dispatches.id) AS delivery_error," +
                "(SELECT next_attempt_at FROM dispatch_deliveries WHERE dispatch_id=dispatches.id) AS delivery_next_attempt_at," +
                "COALESCE((SELECT input_attempted FROM dispatch_deliveries WHERE dispatch_id=dispatches.id),0) AS delivery_input_attempted";
        return database.read("find dispatch detail", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT "+columns+" FROM dispatches WHERE workspace_id=? AND id=?")) {
                ps.setString(1, workspaceId);
                ps.setString(2, dispatchId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapDetail(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override public void markDelivered(StoredDispatch stored) {
        database.write("mark dispatch delivered",c->{
            Dispatch d=stored.dispatch();
            try(PreparedStatement ps=c.prepareStatement("UPDATE dispatches SET status='submitted',submitted_at=?,delivered_at=? WHERE id=? AND status='queued'")){
                ps.setLong(1,d.submittedAt().orElseThrow().toEpochMilli()); ps.setLong(2,d.deliveredAt().orElseThrow().toEpochMilli()); ps.setString(3,d.id().toString());
                // A very fast worker may report or receive a cancellation before delivery
                // acknowledgement is persisted. The terminal state wins; acknowledgement is
                // intentionally an idempotent no-op in that case.
                ps.executeUpdate();
            } return null;
        });
    }

    private void insertDispatch(Connection c, Dispatch d, String idempotencyKey) throws SQLException {
        String sql="""
                INSERT INTO dispatches(id,workspace_id,from_agent_id,to_agent_id,text,status,created_at,artifacts,idempotency_key)
                SELECT ?,?,?,?,?,?,?,?,?
                WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL)
                  AND EXISTS(SELECT 1 FROM workers WHERE workspace_id=? AND id=? AND deleted_at IS NULL)
                """;
        try(PreparedStatement ps=c.prepareStatement(sql)){ ps.setString(1,d.id().toString());ps.setString(2,d.workspaceId().toString());ps.setString(3,d.fromAgentId().orElse(null));ps.setString(4,d.toAgentId().toString());ps.setString(5,d.task().value());ps.setString(6,d.status().wireValue());ps.setLong(7,d.createdAt().toEpochMilli());ps.setString(8,json(d.artifacts()));ps.setString(9,idempotencyKey);ps.setString(10,d.workspaceId().toString());ps.setString(11,d.workspaceId().toString());ps.setString(12,d.toAgentId().toString());if(ps.executeUpdate()!=1)throw new SQLException("workspace or dispatch worker is no longer active"); }
    }

    private long insertMessage(Connection c, TeamMessage m) throws SQLException {
        String sql="""
                INSERT INTO messages(workspace_id,worker_id,type,from_agent_id,to_agent_id,text,status,artifacts,created_at,dispatch_id)
                SELECT ?,?,?,?,?,?,?,?,?,?
                WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL)
                  AND EXISTS(SELECT 1 FROM workers WHERE workspace_id=? AND id=? AND deleted_at IS NULL)
                RETURNING sequence
                """;
        try(PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,m.workspaceId());ps.setString(2,m.workerId());ps.setString(3,m.type());ps.setString(4,m.fromAgentId());ps.setString(5,m.toAgentId());ps.setString(6,m.text());ps.setString(7,m.status());ps.setString(8,json(m.artifacts()));ps.setLong(9,m.createdAt().toEpochMilli());ps.setString(10,m.dispatchId());
            ps.setString(11,m.workspaceId());ps.setString(12,m.workspaceId());ps.setString(13,m.workerId());
            try(ResultSet result=ps.executeQuery()){if(!result.next())throw new SQLException("message sequence was not generated");return result.getLong(1);}
        }
    }

    private Optional<ExistingEnqueue> findExistingEnqueue(Connection connection, Dispatch dispatch,
                                                           String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT dispatch.id,dispatch.to_agent_id,dispatch.text,
                       COALESCE((SELECT MIN(message.sequence) FROM messages message
                                 WHERE message.dispatch_id=dispatch.id),0) AS message_sequence
                FROM dispatches dispatch
                WHERE dispatch.workspace_id=? AND dispatch.from_agent_id=? AND dispatch.idempotency_key=?
                """)) {
            statement.setString(1, dispatch.workspaceId().toString());
            statement.setString(2, dispatch.fromAgentId().orElse(null));
            statement.setString(3, idempotencyKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new ExistingEnqueue(rows.getString("id"),
                        rows.getString("to_agent_id"), rows.getString("text"),
                        rows.getLong("message_sequence")));
            }
        }
    }

    private void insertDelivery(Connection connection, Dispatch dispatch, String runtimePort) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO dispatch_deliveries(
                    dispatch_id,workspace_id,to_agent_id,runtime_port,state,attempt_count,
                    input_attempted,next_attempt_at,created_at,updated_at)
                VALUES(?,?,?,?,?,0,0,?,?,?)
                """)) {
            long createdAt = dispatch.createdAt().toEpochMilli();
            statement.setString(1, dispatch.id().toString());
            statement.setString(2, dispatch.workspaceId().toString());
            statement.setString(3, dispatch.toAgentId().toString());
            statement.setString(4, runtimePort);
            statement.setString(5, DeliveryState.PENDING.wireValue());
            statement.setLong(6, createdAt);
            statement.setLong(7, createdAt);
            statement.setLong(8, createdAt);
            statement.executeUpdate();
        }
    }

    private String transitionAttempt(Connection connection, String attemptId, String state, String error,
                                     Instant nextAttemptAt, boolean inputAttempted,
                                     Instant updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE dispatch_deliveries
                SET state=?,input_attempted=CASE WHEN ? THEN 1 ELSE input_attempted END,
                    last_error=?,next_attempt_at=COALESCE(?,next_attempt_at),
                    lease_owner=NULL,lease_expires_at=NULL,updated_at=?
                WHERE attempt_id=? AND state='delivering'
                RETURNING dispatch_id
                """)) {
            statement.setString(1, state);
            statement.setBoolean(2, inputAttempted);
            statement.setString(3, error);
            if (nextAttemptAt == null) statement.setNull(4, Types.BIGINT);
            else statement.setLong(4, nextAttemptAt.toEpochMilli());
            statement.setLong(5, updatedAt.toEpochMilli());
            statement.setString(6, attemptId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private void closeDelivery(Connection connection, String dispatchId, Instant closedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE dispatch_deliveries
                SET state='closed',lease_owner=NULL,lease_expires_at=NULL,updated_at=?
                WHERE dispatch_id=? AND state<>'closed'
                """)) {
            statement.setLong(1, closedAt.toEpochMilli());
            statement.setString(2, dispatchId);
            statement.executeUpdate();
        }
    }

    private record ExistingEnqueue(String dispatchId, String toAgentId, String text,
                                   long messageSequence) { }

    private List<DispatchSummaryProjection> selectSummaries(Connection c,String sql,String workspace,String state,int limit,int offset)throws SQLException{
        List<DispatchSummaryProjection> result=new ArrayList<>();
        try(PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,workspace);int i=2;if(state!=null)ps.setString(i++,state);ps.setInt(i++,limit);ps.setInt(i,offset);
            try(ResultSet rs=ps.executeQuery()){
                while(rs.next())result.add(new DispatchSummaryProjection(rs.getString("id"),rs.getString("workspace_id"),
                        rs.getString("from_agent_id"),rs.getString("to_agent_id"),rs.getString("text"),rs.getString("status"),
                        rs.getLong("created_at"),nullableLong(rs,"delivered_at"),nullableLong(rs,"submitted_at"),
                        nullableLong(rs,"reported_at"),rs.getString("report_text"),artifacts(rs.getString("artifacts")),
                        rs.getBoolean("truncated"),rs.getString("delivery_state"),
                        rs.getInt("delivery_attempt_count"),rs.getString("delivery_error"),
                        nullableLong(rs,"delivery_next_attempt_at"),rs.getBoolean("delivery_input_attempted")));
            }
        }
        return List.copyOf(result);
    }

    private DispatchDetailProjection mapDetail(ResultSet rs)throws SQLException{
        String id=rs.getString("id");
        String workspaceId=rs.getString("workspace_id");
        String toAgentId=rs.getString("to_agent_id");
        String text=rs.getString("text");
        String status=rs.getString("status");
        if(id==null||workspaceId==null||toAgentId==null||text==null
                ||!rs.getBoolean("from_agent_id_valid")||!rs.getBoolean("artifacts_valid")
                ||!Set.of("queued","submitted","reported","cancelled").contains(status)){
            throw new InvalidDispatchRecord();
        }
        return new DispatchDetailProjection(id,workspaceId,rs.getString("from_agent_id"),
                toAgentId,text,status,rs.getLong("created_at"),
                nullableLong(rs,"delivered_at"),nullableLong(rs,"submitted_at"),nullableLong(rs,"reported_at"),
                rs.getString("report_text"),artifacts(rs.getString("artifacts")),rs.getBoolean("truncated"),
                rs.getString("delivery_state"),rs.getInt("delivery_attempt_count"),rs.getString("delivery_error"),
                nullableLong(rs,"delivery_next_attempt_at"),rs.getBoolean("delivery_input_attempted"));
    }

    private StoredDispatch map(ResultSet rs)throws SQLException{
        try{
            String from=rs.getString("from_agent_id");
            Dispatch d=Dispatch.restore(DispatchId.parse(rs.getString("id")),WorkspaceId.parse(rs.getString("workspace_id")),from,
                    AgentId.parse(rs.getString("to_agent_id")),new TaskText(rs.getString("text")),Instant.ofEpochMilli(rs.getLong("created_at")),
                    DispatchStatus.parse(rs.getString("status")),instant(rs,"submitted_at"),instant(rs,"delivered_at"),instant(rs,"reported_at"),rs.getString("report_text"),artifacts(rs.getString("artifacts")));
            return new StoredDispatch(rs.getLong("sequence"),d);
        }catch(IllegalArgumentException|NullPointerException invalidRecord){
            throw new InvalidDispatchRecord(invalidRecord);
        }
    }

    private static String transitionColumns() {
        return "CASE WHEN length(id) BETWEEN 1 AND " + MAX_DISPATCH_ID_CHARACTERS +
                " THEN id END AS id," +
                "CASE WHEN length(workspace_id) BETWEEN 1 AND " + MAX_WORKSPACE_ID_CHARACTERS +
                " THEN workspace_id END AS workspace_id," +
                "CASE WHEN length(to_agent_id) BETWEEN 1 AND " + MAX_AGENT_ID_CHARACTERS +
                " THEN to_agent_id END AS to_agent_id";
    }

    private static String storedTransitionColumns(String artifactsExpression) {
        return "sequence," + transitionColumns() +
                ",NULL AS from_agent_id," +
                "'persisted dispatch transition' AS text,status," +
                "reported_at AS created_at,NULL AS submitted_at,NULL AS delivered_at,reported_at," +
                "CASE WHEN report_text IS NULL THEN NULL ELSE substr(report_text,1," +
                DETAIL_REPORT_LIMIT + ") END AS report_text," +
                "CASE WHEN length(COALESCE(" + artifactsExpression + ",'[]'))<=" +
                DETAIL_ARTIFACTS_JSON_LIMIT + " THEN COALESCE(" + artifactsExpression +
                ",'[]') ELSE '[]' END AS artifacts";
    }

    private static boolean boundedIdentifier(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static Instant instant(ResultSet rs,String column)throws SQLException{long value=rs.getLong(column);return rs.wasNull()?null:Instant.ofEpochMilli(value);}
    private static Long nullableLong(ResultSet rs,String column)throws SQLException{long value=rs.getLong(column);return rs.wasNull()?null:value;}
    private String json(List<String> values)throws SQLException{try{return json.writeValueAsString(values==null?List.of():values);}catch(JsonProcessingException e){throw new SQLException("invalid artifacts",e);}}
    private List<String> artifacts(String value){
        if(value==null)return List.of();
        try{
            List<String> parsed=json.readValue(value,STRING_LIST);
            if(parsed==null||parsed.stream().anyMatch(Objects::isNull))throw new InvalidDispatchRecord();
            return List.copyOf(parsed);
        }catch(JsonProcessingException e){throw new InvalidDispatchRecord(e);}
    }
}
