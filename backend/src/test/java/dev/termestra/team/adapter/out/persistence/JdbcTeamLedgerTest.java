package dev.termestra.team.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.team.application.exception.InvalidDispatchRecord;
import dev.termestra.team.application.exception.InactiveDeliveryAttempt;
import dev.termestra.team.application.port.out.TeamMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTeamLedgerTest {
    @TempDir Path tempDirectory;

    @Test void openCountsIgnoreMoreThanTheProjectionCapOfOrphanDispatches() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("open-counts.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        String activeWorker = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        database.write("seed active and orphan dispatches", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                workspace.setString(1, workspaceId);
                workspace.setString(2, "Alpha");
                workspace.setString(3, "/tmp/alpha");
                workspace.setLong(4, now);
                workspace.executeUpdate();
            }
            try (var worker = connection.prepareStatement("""
                    INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                worker.setString(1, activeWorker);
                worker.setString(2, workspaceId);
                worker.setString(3, "Active");
                worker.setString(4, "");
                worker.setString(5, "coder");
                worker.setLong(6, now);
                worker.executeUpdate();
            }
            try (var dispatch = connection.prepareStatement("""
                    INSERT INTO dispatches(
                      id,workspace_id,to_agent_id,text,status,created_at,artifacts)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                for (int index = 0; index < 3; index++) {
                    insertDispatch(dispatch, "active-" + index, workspaceId, activeWorker, now + index);
                }
                for (int index = 0; index < 300; index++) {
                    insertDispatch(dispatch, "orphan-" + index, workspaceId,
                            "missing-worker-" + index, now + 10 + index);
                }
            }
            return null;
        });

        Map<String, Integer> counts = new JdbcTeamLedger(database, new ObjectMapper())
                .loadOpenCounts(workspaceId);

        assertEquals(Map.of(activeWorker, 3), counts);
    }

    @Test void openCountsUseTheSameVisibleWorkerSelectionAsTheTeamList() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("visible-open-counts.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        String visibleWorker = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        database.write("seed visible and invalid-role worker dispatches", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                workspace.setString(1, workspaceId);
                workspace.setString(2, "Alpha");
                workspace.setString(3, "/tmp/alpha");
                workspace.setLong(4, now);
                workspace.executeUpdate();
            }
            try (var worker = connection.prepareStatement("""
                         INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                         VALUES(?,?,?,?,?,?)
                         """);
                 var dispatch = connection.prepareStatement("""
                         INSERT INTO dispatches(
                           id,workspace_id,to_agent_id,text,status,created_at,artifacts)
                         VALUES(?,?,?,?,?,?,?)
                         """)) {
                for (int index = 0; index < 256; index++) {
                    String workerId = "invalid-role-" + index;
                    insertWorker(worker, workerId, workspaceId, "Hidden " + index,
                            "orchestrator", now + index);
                    insertDispatch(dispatch, "hidden-dispatch-" + index, workspaceId,
                            workerId, now + index);
                }
                insertWorker(worker, visibleWorker, workspaceId, "Visible", "coder", now + 300);
                insertDispatch(dispatch, "visible-dispatch", workspaceId, visibleWorker, now + 300);
            }
            return null;
        });

        Map<String, Integer> counts = new JdbcTeamLedger(database, new ObjectMapper())
                .loadOpenCounts(workspaceId);

        assertEquals(Map.of(visibleWorker, 1), counts);
    }

    @Test void isolatesLegacyDispatchesWithUnboundedAddressFields() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("dispatch-address-bounds.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        String workerId = UUID.randomUUID().toString();
        String dispatchId = UUID.randomUUID().toString();
        seedWorkspaceAndWorker(database, workspaceId, workerId);
        seedDispatch(database, dispatchId, workspaceId, "F".repeat(2 * 1_024 * 1_024),
                workerId, "task", "queued", "[]");
        JdbcTeamLedger ledger = new JdbcTeamLedger(database, new ObjectMapper());

        assertTrue(ledger.listSummaries(workspaceId, null, 100, 0).isEmpty());
        assertThrows(InvalidDispatchRecord.class,
                () -> ledger.findDetailById(workspaceId, dispatchId));
    }

    @Test void transitionsPoisonedLegacyDispatchesWithoutReadingTheirBody() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("poisoned-dispatch-transition.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        String workerId = UUID.randomUUID().toString();
        String cancelId = UUID.randomUUID().toString();
        String reportId = UUID.randomUUID().toString();
        seedWorkspaceAndWorker(database, workspaceId, workerId);
        seedDispatch(database, cancelId, workspaceId, null, workerId, "", "queued", "{");
        seedDispatch(database, reportId, workspaceId, null, workerId, "", "queued", "{");
        JdbcTeamLedger ledger = new JdbcTeamLedger(database, new ObjectMapper());
        Instant now = Instant.now();

        assertTrue(ledger.cancelOne(workspaceId, cancelId, "obsolete", now).isPresent());
        assertTrue(ledger.cancelOne(workspaceId, cancelId, "obsolete", now).isEmpty());
        TeamMessage message = new TeamMessage(workspaceId, workerId, "report", workerId,
                null, "done", null, List.of(), now);
        assertTrue(ledger.reportOne(workspaceId, workerId, reportId, "done", List.of(), now, message)
                .isPresent());
        assertTrue(ledger.reportOne(workspaceId, workerId, reportId, "done", List.of(), now, message)
                .isEmpty());
    }

    @Test void rejectsASubmittedAcknowledgementWhenItsDeliveryAttemptIsNoLongerActive() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("stale-delivery-ack.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        JdbcTeamLedger ledger = new JdbcTeamLedger(database, new ObjectMapper());

        InactiveDeliveryAttempt failure = assertThrows(InactiveDeliveryAttempt.class,
                () -> ledger.markDeliverySubmitted("stale-attempt", Instant.EPOCH));

        assertEquals("Dispatch delivery attempt is no longer active: stale-attempt",
                failure.getMessage());
    }

    private static void insertDispatch(java.sql.PreparedStatement statement, String id,
                                       String workspaceId, String workerId, long createdAt)
            throws java.sql.SQLException {
        statement.setString(1, id);
        statement.setString(2, workspaceId);
        statement.setString(3, workerId);
        statement.setString(4, "task");
        statement.setString(5, "queued");
        statement.setLong(6, createdAt);
        statement.setString(7, "[]");
        statement.executeUpdate();
    }

    private static void insertWorker(java.sql.PreparedStatement statement, String id,
                                     String workspaceId, String name, String role, long createdAt)
            throws java.sql.SQLException {
        statement.setString(1, id);
        statement.setString(2, workspaceId);
        statement.setString(3, name);
        statement.setString(4, "");
        statement.setString(5, role);
        statement.setLong(6, createdAt);
        statement.executeUpdate();
    }

    private static void seedWorkspaceAndWorker(SqliteDatabase database, String workspaceId,
                                               String workerId) {
        database.write("seed dispatch workspace and worker", connection -> {
            long now = System.currentTimeMillis();
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                workspace.setString(1, workspaceId);
                workspace.setString(2, "Alpha");
                workspace.setString(3, "/tmp/alpha");
                workspace.setLong(4, now);
                workspace.executeUpdate();
            }
            try (var worker = connection.prepareStatement("""
                    INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                worker.setString(1, workerId);
                worker.setString(2, workspaceId);
                worker.setString(3, "Alice");
                worker.setString(4, "");
                worker.setString(5, "coder");
                worker.setLong(6, now);
                worker.executeUpdate();
            }
            return null;
        });
    }

    private static void seedDispatch(SqliteDatabase database, String id, String workspaceId,
                                     String fromAgentId, String workerId, String text,
                                     String status, String artifacts) {
        database.write("seed legacy dispatch", connection -> {
            try (var dispatch = connection.prepareStatement("""
                    INSERT INTO dispatches(
                      id,workspace_id,from_agent_id,to_agent_id,text,status,created_at,artifacts)
                    VALUES(?,?,?,?,?,?,?,?)
                    """)) {
                dispatch.setString(1, id);
                dispatch.setString(2, workspaceId);
                dispatch.setString(3, fromAgentId);
                dispatch.setString(4, workerId);
                dispatch.setString(5, text);
                dispatch.setString(6, status);
                dispatch.setLong(7, System.currentTimeMillis());
                dispatch.setString(8, artifacts);
                dispatch.executeUpdate();
            }
            return null;
        });
    }
}
