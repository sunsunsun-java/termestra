package dev.termestra.team.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.team.adapter.out.persistence.JdbcTeamLedger;
import dev.termestra.team.adapter.out.persistence.JdbcTeamMemberRepository;
import dev.termestra.team.application.port.in.SendTaskCommand;
import dev.termestra.team.application.port.out.AgentTeamNotifier;
import dev.termestra.team.application.port.out.DeliveryResult;
import dev.termestra.team.domain.model.AgentRole;
import dev.termestra.team.domain.model.Dispatch;
import dev.termestra.team.domain.model.TeamMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReliableDispatchAcceptanceTest {
    @TempDir Path temporaryDirectory;

    @Test void acceptsOnceByIdempotencyKeyBeforeAnyTerminalDelivery() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("reliable-dispatch.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = seedWorkspace(database);
        JdbcTeamMemberRepository members = new JdbcTeamMemberRepository(database);
        TeamMember worker = TeamMember.create(WorkspaceId.parse(workspaceId), "Alice",
                "Implement the assignment", AgentRole.CODER, Instant.now());
        members.save(worker);
        JdbcTeamLedger ledger = new JdbcTeamLedger(database, new ObjectMapper());
        AtomicInteger terminalDeliveries = new AtomicInteger();
        AgentTeamNotifier notifier = new AgentTeamNotifier() {
            @Override public DeliveryResult deliver(Dispatch dispatch, TeamMember member, String runtimePort) {
                terminalDeliveries.incrementAndGet();
                return new DeliveryResult(true, null);
            }
            @Override public DeliveryResult report(Dispatch dispatch, TeamMember member) {
                return DeliveryResult.unavailable("unused");
            }
            @Override public DeliveryResult status(String workspace, TeamMember member, String text,
                                                   List<String> artifacts) {
                return DeliveryResult.unavailable("unused");
            }
            @Override public DeliveryResult cancel(Dispatch dispatch, TeamMember member) {
                return DeliveryResult.unavailable("unused");
            }
        };
        AtomicInteger wakes = new AtomicInteger();
        TeamApplicationService service = new TeamApplicationService(ledger, members,
                (agentId, token) -> true, notifier, ignored -> java.util.Set.of(),
                new PendingTaskProjection(ledger), Clock.systemUTC(),
                new dev.termestra.shared.concurrency.RuntimeOperationCoordinator(), wakes::incrementAndGet);
        SendTaskCommand command = new SendTaskCommand(workspaceId, workspaceId + ":orchestrator",
                "token", "Alice", "Build it", "4010", "request-123");

        var first = service.send(command);
        var replay = service.send(command);

        assertEquals(first.dispatchId(), replay.dispatchId());
        assertFalse(first.forwarded(), "acceptance does not claim that terminal delivery already happened");
        assertEquals(0, terminalDeliveries.get(), "the HTTP/CLI request path must never call the PTY");
        assertEquals(1, wakes.get(), "only a newly committed delivery wakes the runtime");
        database.read("verify reliable dispatch graph", connection -> {
            assertEquals(1, count(connection, "messages"));
            assertEquals(1, count(connection, "dispatches"));
            assertEquals(1, count(connection, "dispatch_deliveries"));
            try (var statement = connection.prepareStatement("""
                    SELECT dispatch_id,state,attempt_count,input_attempted
                    FROM dispatch_deliveries
                    """); var result = statement.executeQuery()) {
                result.next();
                assertEquals(first.dispatchId(), result.getString("dispatch_id"));
                assertEquals("pending", result.getString("state"));
                assertEquals(0, result.getInt("attempt_count"));
                assertFalse(result.getBoolean("input_attempted"));
            }
            return null;
        });
    }

    private static String seedWorkspace(SqliteDatabase database) {
        String workspaceId = UUID.randomUUID().toString();
        database.write("seed workspace", connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                statement.setString(1, workspaceId);
                statement.setString(2, "Alpha");
                statement.setString(3, "/tmp/alpha");
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
        return workspaceId;
    }

    private static int count(java.sql.Connection connection, String table) throws java.sql.SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
