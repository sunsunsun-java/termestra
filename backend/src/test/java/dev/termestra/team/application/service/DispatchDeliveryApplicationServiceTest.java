package dev.termestra.team.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.team.adapter.out.persistence.JdbcTeamLedger;
import dev.termestra.team.adapter.out.persistence.JdbcTeamMemberRepository;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class DispatchDeliveryApplicationServiceTest {
    @TempDir Path temporaryDirectory;

    @Test void submitsACommittedDeliveryAndPersistsTheAcknowledgementAtomically() {
        Fixture fixture = fixture("success.db", new DeliveryResult(true, null));
        String dispatchId = fixture.enqueue("key-success");

        assertTrue(fixture.service.processNext());
        assertEquals("submitted", fixture.value(dispatchId, "delivery.state"));
        assertEquals("submitted", fixture.value(dispatchId, "dispatch.status"));
        assertEquals(1, fixture.notifierCalls.get());
        assertFalse(fixture.service.processNext());
    }

    @Test void aColdStartedTerminalDeliveryIsNotReapedBeforeItsAcknowledgementCanBePersisted() {
        Fixture fixture = fixture("lease-budget.db", new DeliveryResult(true, null),
                new RuntimeOperationCoordinator(), (ledger, clock) -> {
                    clock.advance(Duration.ofSeconds(70));
                    ledger.claimNextDelivery("competing-scheduler", clock.instant(),
                            clock.instant().plusSeconds(90));
                });
        String dispatchId = fixture.enqueue("key-lease-budget");

        assertTrue(fixture.service.processNext());

        assertEquals("submitted", fixture.value(dispatchId, "delivery.state"));
        assertEquals("submitted", fixture.value(dispatchId, "dispatch.status"));
        assertEquals(1, fixture.notifierCalls.get());
    }

    @Test void uncertainTerminalOutcomeIsNeverAutomaticallyReplayed() {
        Fixture fixture = fixture("uncertain.db", DeliveryResult.uncertain("acknowledgement timed out"));
        String dispatchId = fixture.enqueue("key-uncertain");

        assertTrue(fixture.service.processNext());
        assertEquals("uncertain", fixture.value(dispatchId, "delivery.state"));
        assertEquals("queued", fixture.value(dispatchId, "dispatch.status"));
        assertFalse(fixture.service.processNext());
        assertEquals(1, fixture.notifierCalls.get());
    }

    @Test void definiteFailuresUseBoundedExponentialRetriesThenStop() {
        Fixture fixture = fixture("retry.db", DeliveryResult.unavailable("worker not ready"));
        String dispatchId = fixture.enqueue("key-retry");

        for (int attempt = 1; attempt <= DispatchDeliveryApplicationService.MAX_AUTOMATIC_ATTEMPTS; attempt++) {
            assertTrue(fixture.service.processNext());
            if (attempt < DispatchDeliveryApplicationService.MAX_AUTOMATIC_ATTEMPTS) {
                assertEquals("retry_wait", fixture.value(dispatchId, "delivery.state"));
                fixture.clock.advance(Duration.ofSeconds(1L << (attempt - 1)));
            }
        }
        assertEquals("failed", fixture.value(dispatchId, "delivery.state"));
        assertFalse(fixture.service.processNext());
        assertEquals(DispatchDeliveryApplicationService.MAX_AUTOMATIC_ATTEMPTS,
                fixture.notifierCalls.get());
    }

    @Test void restartMakesAnInFlightAttemptExplicitlyUncertainUntilOperatorRetry() {
        Fixture fixture = fixture("restart.db", new DeliveryResult(true, null));
        String dispatchId = fixture.enqueue("key-restart");
        assertTrue(fixture.ledger.claimNextDelivery("dead-process", fixture.clock.instant(),
                fixture.clock.instant().plusSeconds(30)).isPresent());

        assertEquals(1, fixture.service.recoverInterrupted());
        assertEquals("uncertain", fixture.value(dispatchId, "delivery.state"));
        assertTrue(fixture.service.retry(fixture.workspaceId, dispatchId));
        assertEquals("pending", fixture.value(dispatchId, "delivery.state"));
        assertTrue(fixture.service.processNext());
        assertEquals("submitted", fixture.value(dispatchId, "dispatch.status"));
    }

    @Test void onlyOneDeliveryPerWorkerCanBeLeasedAtATime() {
        Fixture fixture = fixture("serialization.db", new DeliveryResult(true, null));
        fixture.enqueue("key-one");
        fixture.enqueue("key-two");

        assertTrue(fixture.ledger.claimNextDelivery("owner-one", fixture.clock.instant(),
                fixture.clock.instant().plusSeconds(30)).isPresent());
        assertTrue(fixture.ledger.claimNextDelivery("owner-two", fixture.clock.instant(),
                fixture.clock.instant().plusSeconds(30)).isEmpty());
    }

    @Test void aRetryingOlderDispatchPreventsPerWorkerTaskReordering() {
        Fixture fixture = fixture("fifo.db", DeliveryResult.unavailable("worker not ready"));
        String first = fixture.enqueue("key-first");
        String second = fixture.enqueue("key-second");

        assertTrue(fixture.service.processNext());

        assertEquals("retry_wait", fixture.value(first, "delivery.state"));
        assertEquals("pending", fixture.value(second, "delivery.state"));
        assertFalse(fixture.service.processNext(), "the second task must not overtake the first task");
    }

    @Test void workspaceContentionDefersAClaimWithoutConsumingADeliveryAttempt() {
        RuntimeOperationCoordinator operations = new RuntimeOperationCoordinator(Duration.ofMillis(50));
        Fixture fixture = fixture("workspace-busy.db", new DeliveryResult(true, null), operations);
        String dispatchId = fixture.enqueue("key-workspace-busy");

        try (HeldRuntimeOperation ignored = holdWorkspace(operations, fixture.workspaceId)) {
            assertTrue(fixture.service.processNext());
            assertDeferredWithoutInput(fixture, dispatchId);
        }

        fixture.clock.advance(Duration.ofSeconds(1));
        assertTrue(fixture.service.processNext());
        assertEquals("submitted", fixture.value(dispatchId, "delivery.state"));
        assertEquals(1, fixture.intValue(dispatchId, "attempt_count"));
        assertEquals(1, fixture.notifierCalls.get());
    }

    @Test void agentContentionDefersAClaimWithoutConsumingADeliveryAttempt() {
        RuntimeOperationCoordinator operations = new RuntimeOperationCoordinator(Duration.ofMillis(50));
        Fixture fixture = fixture("agent-busy.db", new DeliveryResult(true, null), operations);
        String dispatchId = fixture.enqueue("key-agent-busy");

        try (HeldRuntimeOperation ignored = holdAgent(operations, fixture.workspaceId,
                fixture.worker.id().toString())) {
            assertTrue(fixture.service.processNext());
            assertDeferredWithoutInput(fixture, dispatchId);
        }

        fixture.clock.advance(Duration.ofSeconds(1));
        assertTrue(fixture.service.processNext());
        assertEquals("submitted", fixture.value(dispatchId, "delivery.state"));
        assertEquals(1, fixture.intValue(dispatchId, "attempt_count"));
        assertEquals(1, fixture.notifierCalls.get());
    }

    private static void assertDeferredWithoutInput(Fixture fixture, String dispatchId) {
        assertEquals("retry_wait", fixture.value(dispatchId, "delivery.state"));
        assertEquals(0, fixture.intValue(dispatchId, "attempt_count"));
        assertEquals(0, fixture.intValue(dispatchId, "input_attempted"));
        assertEquals(0, fixture.notifierCalls.get());
    }

    private static HeldRuntimeOperation holdWorkspace(RuntimeOperationCoordinator operations,
                                                       String workspaceId) {
        return hold((acquired, release) -> operations.exclusivelyWithWorkspace(workspaceId, () -> {
            acquired.countDown();
            return await(release);
        }));
    }

    private static HeldRuntimeOperation holdAgent(RuntimeOperationCoordinator operations,
                                                   String workspaceId, String agentId) {
        return hold((acquired, release) -> operations.withAgent(workspaceId, agentId, () -> {
            acquired.countDown();
            return await(release);
        }));
    }

    private static Void await(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release held runtime operation");
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static HeldRuntimeOperation hold(LockHoldingOperation operation) {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                operation.run(acquired, release);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "dispatch-delivery-lock-holder");
        holder.start();
        try {
            assertTrue(acquired.await(1, TimeUnit.SECONDS), "runtime lock holder did not start");
            Thread.sleep(25);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(interrupted);
        }
        return new HeldRuntimeOperation(release, holder, failure);
    }

    @FunctionalInterface
    private interface LockHoldingOperation {
        void run(CountDownLatch acquired, CountDownLatch release);
    }

    private Fixture fixture(String file, DeliveryResult result) {
        return fixture(file, result, new RuntimeOperationCoordinator());
    }

    private Fixture fixture(String file, DeliveryResult result,
                            RuntimeOperationCoordinator operations) {
        return fixture(file, result, operations, (ledger, clock) -> { });
    }

    private Fixture fixture(String file, DeliveryResult result,
                            RuntimeOperationCoordinator operations,
                            BiConsumer<JdbcTeamLedger, MutableClock> duringDelivery) {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve(file));
        new SqliteSchemaMigrator(database, clock).migrate();
        String workspaceId = seedWorkspace(database, clock.instant());
        JdbcTeamMemberRepository members = new JdbcTeamMemberRepository(database);
        TeamMember worker = TeamMember.create(WorkspaceId.parse(workspaceId), "Alice",
                "Implement the assignment", AgentRole.CODER, clock.instant());
        members.save(worker);
        JdbcTeamLedger ledger = new JdbcTeamLedger(database, new ObjectMapper());
        AtomicInteger calls = new AtomicInteger();
        AgentTeamNotifier notifier = new AgentTeamNotifier() {
            @Override public DeliveryResult deliver(Dispatch dispatch, TeamMember member, String runtimePort) {
                calls.incrementAndGet();
                duringDelivery.accept(ledger, clock);
                return result;
            }
            @Override public DeliveryResult report(Dispatch dispatch, TeamMember member) { return DeliveryResult.unavailable("unused"); }
            @Override public DeliveryResult status(String workspace, TeamMember member, String text, List<String> artifacts) { return DeliveryResult.unavailable("unused"); }
            @Override public DeliveryResult cancel(Dispatch dispatch, TeamMember member) { return DeliveryResult.unavailable("unused"); }
        };
        DispatchDeliveryApplicationService service = new DispatchDeliveryApplicationService(ledger,
                members, notifier, operations, clock);
        return new Fixture(database, ledger, worker, workspaceId, clock, calls, service);
    }

    private static String seedWorkspace(SqliteDatabase database, Instant now) {
        String workspaceId = UUID.randomUUID().toString();
        database.write("seed workspace", connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                statement.setString(1, workspaceId);
                statement.setString(2, "Alpha");
                statement.setString(3, "/tmp/alpha");
                statement.setLong(4, now.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
        return workspaceId;
    }

    private record Fixture(SqliteDatabase database, JdbcTeamLedger ledger, TeamMember worker,
                           String workspaceId, MutableClock clock, AtomicInteger notifierCalls,
                           DispatchDeliveryApplicationService service) {
        String enqueue(String key) {
            Instant now = clock.instant();
            Dispatch dispatch = Dispatch.create(WorkspaceId.parse(workspaceId), workspaceId + ":orchestrator",
                    worker.id(), new TaskText("Task " + key), now);
            return ledger.enqueue(dispatch, new TeamMessage(workspaceId, worker.id().toString(), "send",
                    workspaceId + ":orchestrator", worker.id().toString(), dispatch.task().value(),
                    null, List.of(), now), "4010", key).dispatchId();
        }

        String value(String dispatchId, String selector) {
            return database.read("read delivery state", connection -> {
                String sql = switch (selector) {
                    case "delivery.state" -> "SELECT state FROM dispatch_deliveries WHERE dispatch_id=?";
                    case "dispatch.status" -> "SELECT status FROM dispatches WHERE id=?";
                    default -> throw new IllegalArgumentException(selector);
                };
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setString(1, dispatchId);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        return rows.getString(1);
                    }
                }
            });
        }

        int intValue(String dispatchId, String column) {
            if (!List.of("attempt_count", "input_attempted").contains(column)) {
                throw new IllegalArgumentException(column);
            }
            return database.read("read delivery " + column, connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT " + column + " FROM dispatch_deliveries WHERE dispatch_id=?")) {
                    statement.setString(1, dispatchId);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        return rows.getInt(1);
                    }
                }
            });
        }
    }

    private record HeldRuntimeOperation(CountDownLatch release, Thread holder,
                                        AtomicReference<Throwable> failure) implements AutoCloseable {
        @Override public void close() {
            release.countDown();
            try {
                holder.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail(interrupted);
            }
            assertFalse(holder.isAlive(), "runtime lock holder did not stop");
            assertNull(failure.get(), "runtime lock holder failed");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant current) { this.current = current; }
        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
