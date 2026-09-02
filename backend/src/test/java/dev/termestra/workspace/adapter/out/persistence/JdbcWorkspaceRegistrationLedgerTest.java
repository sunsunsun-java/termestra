package dev.termestra.workspace.adapter.out.persistence;

import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.workspace.application.exception.WorkspaceLimitReached;
import dev.termestra.workspace.application.port.out.WorkspaceRegistrationLedger;
import dev.termestra.workspace.domain.model.Workspace;
import dev.termestra.workspace.domain.model.WorkspaceName;
import dev.termestra.workspace.domain.model.WorkspacePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class JdbcWorkspaceRegistrationLedgerTest {
    @TempDir Path temporaryDirectory;

    @Test void hidesPreparingWorkspaceAndActivatesWorkspaceWithItsAttemptAtomically() {
        SqliteDatabase database = database();
        JdbcWorkspaceRegistrationLedger ledger = new JdbcWorkspaceRegistrationLedger(database);
        JdbcWorkspaceRepository workspaces = new JdbcWorkspaceRepository(database);
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Workspace workspace = Workspace.create(new WorkspaceName("Alpha"),
                new WorkspacePath("/tmp/alpha-registration"), now);

        WorkspaceRegistrationLedger.BeginResult result = ledger.begin(
                new WorkspaceRegistrationLedger.Intent(
                        "registration-1", "request-hash", workspace, now));

        assertInstanceOf(WorkspaceRegistrationLedger.Begun.class, result);
        assertTrue(workspaces.findAll().isEmpty());
        ledger.markSourceReady("registration-1", now.plusMillis(1));
        Workspace activated = ledger.activate("registration-1", now.plusMillis(2));

        assertEquals(workspace.id(), activated.id());
        assertEquals(java.util.List.of(workspace), workspaces.findAll());
        WorkspaceRegistrationLedger.Attempt completed = ledger.find("registration-1").orElseThrow();
        assertEquals("completed", completed.state());
        assertEquals("not_attempted", completed.checkoutOutcome());
    }

    @Test void aTerminalFailureReleasesOnlyThePreparingWorkspaceClaim() {
        SqliteDatabase database = database();
        JdbcWorkspaceRegistrationLedger ledger = new JdbcWorkspaceRegistrationLedger(database);
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Workspace workspace = Workspace.create(new WorkspaceName("Failed"),
                new WorkspacePath("/tmp/failed-registration"), now);
        ledger.begin(new WorkspaceRegistrationLedger.Intent(
                "registration-failed", "hash", workspace, now));

        ledger.fail("registration-failed",
                new WorkspaceRegistrationLedger.Failure("failed", "not_attempted",
                        "WORKSPACE_REGISTRATION_FAILED", true), now.plusMillis(1));
        WorkspaceRegistrationLedger.Attempt failed = ledger.find("registration-failed").orElseThrow();

        assertEquals("failed", failed.state());
        assertNull(failed.workspaceId());
        assertTrue(new JdbcWorkspaceRepository(database).findAll().isEmpty());
        assertInstanceOf(WorkspaceRegistrationLedger.Begun.class,
                ledger.begin(new WorkspaceRegistrationLedger.Intent("registration-retry", "hash-2",
                        Workspace.create(new WorkspaceName("Retry"), workspace.path(), now.plusMillis(2)),
                        now.plusMillis(2))));
    }

    @Test void recordsCurrentSelectionWithoutEnteringTheMutationState() {
        JdbcWorkspaceRegistrationLedger ledger = new JdbcWorkspaceRegistrationLedger(database());
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Workspace workspace = Workspace.create(new WorkspaceName("Current"),
                new WorkspacePath("/tmp/current-registration"), now);
        ledger.begin(new WorkspaceRegistrationLedger.Intent(
                "registration-current", "hash", workspace, now));

        ledger.markSourceReady("registration-current", now.plusMillis(1));

        WorkspaceRegistrationLedger.Attempt attempt =
                ledger.find("registration-current").orElseThrow();
        assertEquals("checkout_applied", attempt.state());
        assertEquals("not_attempted", attempt.checkoutOutcome());
    }

    @Test void recoveryIncludesLegacyUncertainAttemptsSoTheirPathClaimsCanBeReleased() {
        JdbcWorkspaceRegistrationLedger ledger = new JdbcWorkspaceRegistrationLedger(database());
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Workspace uncertain = Workspace.create(new WorkspaceName("Uncertain"),
                new WorkspacePath("/tmp/recovery-uncertain"), now);
        ledger.begin(new WorkspaceRegistrationLedger.Intent(
                "registration-uncertain-only", "hash-1", uncertain, now));
        ledger.fail("registration-uncertain-only", new WorkspaceRegistrationLedger.Failure(
                "uncertain", "unknown", "GIT_OPERATION_OUTCOME_UNKNOWN",
                false), now.plusMillis(2));
        Workspace actionable = Workspace.create(new WorkspaceName("Actionable"),
                new WorkspacePath("/tmp/recovery-actionable"), now.plusMillis(3));
        ledger.begin(new WorkspaceRegistrationLedger.Intent(
                "registration-actionable", "hash-2", actionable, now.plusMillis(3)));

        assertEquals(java.util.List.of("registration-uncertain-only", "registration-actionable"),
                ledger.recoverable(256).stream()
                .map(WorkspaceRegistrationLedger.Attempt::registrationId).toList());
    }

    @Test void pruningMakesRoomWhenNonTerminalAttemptsShareTheCapacity() {
        SqliteDatabase database = database();
        JdbcWorkspaceRegistrationLedger ledger = new JdbcWorkspaceRegistrationLedger(database);
        database.write("seed retained registration attempts", connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO workspace_registration_attempts(
                        registration_id,request_hash,canonical_path,selection_kind,
                        state,checkout_outcome,created_at,updated_at)
                    VALUES(?,?,?,'current',?,? ,?,?)
                    """)) {
                for (int index = 0; index < JdbcWorkspaceRegistrationLedger.MAX_RETAINED_ATTEMPTS - 1; index++) {
                    insert.setString(1, "terminal-" + index);
                    insert.setString(2, "hash-" + index);
                    insert.setString(3, "/tmp/terminal-" + index);
                    insert.setString(4, "completed");
                    insert.setString(5, "not_attempted");
                    insert.setLong(6, index);
                    insert.setLong(7, index);
                    insert.addBatch();
                }
                insert.setString(1, "retained-uncertain");
                insert.setString(2, "uncertain-hash");
                insert.setString(3, "/tmp/retained-uncertain");
                insert.setString(4, "uncertain");
                insert.setString(5, "unknown");
                insert.setLong(6, Long.MAX_VALUE - 1);
                insert.setLong(7, Long.MAX_VALUE - 1);
                insert.addBatch();
                insert.executeBatch();
            }
            return null;
        });
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Workspace workspace = Workspace.create(new WorkspaceName("New"),
                new WorkspacePath("/tmp/pruning-new"), now);

        assertInstanceOf(WorkspaceRegistrationLedger.Begun.class,
                ledger.begin(new WorkspaceRegistrationLedger.Intent("registration-new", "new-hash",
                        workspace, now)));
        assertTrue(ledger.find("terminal-0").isEmpty());
        assertTrue(ledger.find("retained-uncertain").isPresent());
    }

    @Test void enforcesTheWorkspaceLimitAtTheRegistrationBoundary() {
        SqliteDatabase database = database();
        database.write("seed workspace capacity", connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO workspaces(id,name,path,created_at,canonical_path,canonical_path_owner)
                    VALUES(?,?,?,?,?,1)
                    """)) {
                for (int index = 0; index < JdbcWorkspaceRepository.MAX_ACTIVE_WORKSPACES; index++) {
                    String path = "/tmp/workspace-limit-" + index;
                    statement.setString(1, java.util.UUID.randomUUID().toString());
                    statement.setString(2, "Workspace " + index);
                    statement.setString(3, path);
                    statement.setLong(4, index);
                    statement.setString(5, path);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Workspace extra = Workspace.create(new WorkspaceName("Extra"),
                new WorkspacePath("/tmp/workspace-limit-extra"), now);

        WorkspaceLimitReached failure = assertThrows(WorkspaceLimitReached.class,
                () -> new JdbcWorkspaceRegistrationLedger(database).begin(
                        new WorkspaceRegistrationLedger.Intent("registration-over-limit", "hash",
                                extra, now)));

        assertEquals("Workspace limit reached: " + JdbcWorkspaceRepository.MAX_ACTIVE_WORKSPACES,
                failure.getMessage());
        assertTrue(new JdbcWorkspaceRegistrationLedger(database)
                .find("registration-over-limit").isEmpty());
    }

    private SqliteDatabase database() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("registrations.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        return database;
    }
}
