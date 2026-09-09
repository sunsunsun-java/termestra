package dev.termestra.team.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.platform.persistence.sqlite.*;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.team.adapter.out.persistence.*;
import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.port.in.*;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReportDeliveryAcceptanceTest {
    @TempDir Path temporaryDirectory;
    SqliteDatabase database;
    JdbcTeamLedger ledger;
    JdbcTeamMemberRepository members;
    TeamMember worker;
    String workspace;
    MutableClock clock = new MutableClock();
    RuntimeOperationCoordinator operations = new RuntimeOperationCoordinator();
    AtomicInteger notifications = new AtomicInteger();
    java.util.function.Supplier<DeliveryResult> notification = () -> new DeliveryResult(true, null);
    AgentTeamNotifier notifier = new AgentTeamNotifier() {
        public DeliveryResult deliver(Dispatch d, TeamMember m, String port) { return DeliveryResult.deferred("worker busy"); }
        public DeliveryResult report(Dispatch d, TeamMember m) { notifications.incrementAndGet(); return notification.get(); }
        public DeliveryResult status(String w, TeamMember m, String t, List<String> a) { return DeliveryResult.unavailable("unused"); }
        public DeliveryResult cancel(Dispatch d, TeamMember m) { return DeliveryResult.unavailable("unused"); }
    };
    TeamApplicationService team;
    DispatchDeliveryApplicationService deliveries;

    @BeforeEach void setup() {
        database = new SqliteDatabase(temporaryDirectory.resolve("reports.db"));
        new SqliteSchemaMigrator(database, clock).migrate();
        workspace = UUID.randomUUID().toString();
        database.write("seed workspace", c -> {
            try (var s = c.prepareStatement("INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                s.setString(1, workspace); s.setString(2, "Test"); s.setString(3, temporaryDirectory.toString());
                s.setLong(4, clock.millis()); s.executeUpdate();
            }
            return null;
        });
        members = new JdbcTeamMemberRepository(database);
        worker = TeamMember.create(WorkspaceId.parse(workspace), "Alice", "Build", AgentRole.CODER, clock.instant());
        members.save(worker);
        ledger = new JdbcTeamLedger(database, new ObjectMapper());
        team = new TeamApplicationService(ledger, members, (a,t) -> true, notifier, ignored -> Set.of(),
                new PendingTaskProjection(ledger), clock, operations, () -> {});
        deliveries = new DispatchDeliveryApplicationService(ledger, members, notifier, operations, clock);
    }

    String send() {
        return team.send(new SendTaskCommand(workspace, workspace + ":orchestrator", "token", "Alice", "Build", "3000", null)).dispatchId();
    }
    ReportTaskCommand report(String id, String result, String status) {
        return new ReportTaskCommand(workspace, worker.id().toString(), "token", id, result, status, List.of("diagram.html"));
    }

    @Test void acknowledgesAndReplaysWhileTheOrchestratorNotificationIsBlocked() throws Exception {
        String id = send();
        var command = report(id, "done", "complete");
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> team.report(command));
        assertEquals(0, notifications.get());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        notification = () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return new DeliveryResult(true, null);
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sending = executor.submit(deliveries::processNext);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> team.report(command));
                assertFalse(deliveries.processNext(), "one report notification per workspace may be in flight");
            } finally { release.countDown(); }
            assertTrue(sending.get(3, TimeUnit.SECONDS));
        }
        team.report(command);
        assertFalse(deliveries.processNext());
        assertEquals(1, notifications.get());
        assertEquals("submitted", notificationState(id));
        assertThrows(TeamConflict.class, () -> team.report(report(id, "changed", "complete")));
        assertThrows(TeamConflict.class, () -> team.report(report(id, "done", "changed")));
        assertEquals("done", ledger.findDetailById(workspace, id).orElseThrow().reportText());
    }

    @Test void acceptsLegacyReportReplayWithoutCreatingANewNotification() {
        String id = send();
        var command = report(id, "done", "complete");
        team.report(command);
        database.write("simulate a report accepted before schema 35", c -> {
            try (var s = c.createStatement()) {
                s.executeUpdate("DELETE FROM report_deliveries");
                s.executeUpdate("UPDATE messages SET dispatch_id=NULL WHERE type='report'");
            }
            return null;
        });
        assertEquals(id, team.report(command).dispatchId());
        assertNull(notificationState(id));
        assertFalse(deliveries.processNext(), "legacy replay must not repeat the already attempted notification");
    }

    @Test void sameTimestampReportsKeepTheirOwnStatusAndArtifactIdentity() {
        String first = send(), second = send();
        var firstReport = report(first, "done", "first");
        var secondReport = report(second, "done", "second");
        team.report(firstReport); team.report(secondReport);
        assertEquals(first, team.report(firstReport).dispatchId());
        assertEquals(second, team.report(secondReport).dispatchId());
        assertThrows(TeamConflict.class, () -> team.report(new ReportTaskCommand(workspace,
                worker.id().toString(), "token", first, "done", "first", List.of("different.html"))));
    }

    @Test void pendingReportsSurviveRestartAndInterruptedWritesAreNeverAutomaticallyReplayed() {
        String id = send(); team.report(report(id, "done", null));
        var reopened = new JdbcTeamLedger(new SqliteDatabase(temporaryDirectory.resolve("reports.db")), new ObjectMapper());
        assertEquals(0, reopened.recoverInterruptedDeliveries(clock.instant()));
        var claimed = reopened.claimNextReportDelivery(clock.instant(), clock.instant().plusSeconds(90)).orElseThrow();
        assertEquals(id, claimed.dispatch().dispatch().id().toString());
        assertEquals(1, reopened.recoverInterruptedDeliveries(clock.instant()));
        assertEquals("uncertain", notificationState(id));
        assertTrue(reopened.claimNextReportDelivery(clock.instant().plusSeconds(100), clock.instant().plusSeconds(200)).isEmpty());
        team.report(report(id, "done", null));
        assertEquals("uncertain", notificationState(id), "an idempotent report must not authorize repeating an uncertain write");
    }

    @Test void busyWorkersAndOrchestratorsDoNotExhaustTheFailureBudget() {
        String id = send();
        for (int attempt = 0; attempt < 8; attempt++) {
            assertTrue(deliveries.processNext());
            var detail = ledger.findDetailById(workspace, id).orElseThrow();
            assertEquals(0, detail.deliveryAttemptCount());
            assertFalse(detail.deliveryInputAttempted());
            clock.advance();
        }
        team.report(report(id, "done", null));
        notification = () -> DeliveryResult.deferred("orchestrator busy");
        for (int attempt = 0; attempt < 8; attempt++) {
            assertTrue(deliveries.processNext());
            assertEquals("pending", notificationState(id));
            clock.advance();
        }
        notification = () -> new DeliveryResult(true, null);
        assertTrue(deliveries.processNext());
        assertEquals("submitted", notificationState(id));
        database.read("busy report does not consume attempts", c -> {
            try (var s=c.createStatement(); var r=s.executeQuery("SELECT attempt_count FROM report_deliveries")) {
                assertTrue(r.next()); assertEquals(1, r.getInt(1));
            }
            return null;
        });
    }

    @Test void definiteFailuresAreBoundedAndExpiredLeasesPreserveUncertainty() {
        String failed = send(); team.report(report(failed, "done", null));
        notification = () -> DeliveryResult.unavailable("no process");
        for (int attempt = 0; attempt < 5; attempt++) { assertTrue(deliveries.processNext()); clock.advance(); }
        assertEquals("failed", notificationState(failed));
        assertFalse(deliveries.processNext());
        String expired = send(); team.report(report(expired, "done", null));
        var lease = ledger.claimNextReportDelivery(clock.instant(), clock.instant().plusSeconds(1)).orElseThrow();
        clock.advance();
        assertTrue(ledger.claimNextReportDelivery(clock.instant(), clock.instant().plusSeconds(90)).isEmpty());
        ledger.finishReportDelivery(lease.attemptId(), ReportDeliveryWork.Outcome.SUBMITTED, null, clock.instant(), clock.instant());
        assertEquals("uncertain", notificationState(expired), "late acknowledgements cannot overwrite an expired lease");
    }

    @Test void reportAndOutboxCommitAtomicallyAndFollowDispatchDeletion() {
        String id = send();
        database.write("reject report outbox", c -> { try(var s=c.createStatement()) {
            s.execute("CREATE TRIGGER reject_report BEFORE INSERT ON report_deliveries BEGIN SELECT RAISE(ABORT,'test failure'); END");
        } return null; });
        assertThrows(RuntimeException.class, () -> team.report(report(id, "done", null)));
        assertEquals("queued", ledger.findDetailById(workspace, id).orElseThrow().state());
        database.write("allow report outbox", c -> { try(var s=c.createStatement()) { s.execute("DROP TRIGGER reject_report"); } return null; });
        team.report(report(id, "done", null));
        database.write("delete dispatch", c -> { try(var s=c.prepareStatement("DELETE FROM dispatches WHERE id=?")) {
            s.setString(1,id); s.executeUpdate();
        } return null; });
        assertNull(notificationState(id));
        assertFalse(deliveries.processNext());
    }

    String notificationState(String id) {
        return database.read("report notification state", c -> {
            try (var s=c.prepareStatement("SELECT state FROM report_deliveries WHERE dispatch_id=?")) {
                s.setString(1,id); try(var r=s.executeQuery()) { return r.next()?r.getString(1):null; }
            }
        });
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        void advance() { now = now.plusSeconds(20); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
