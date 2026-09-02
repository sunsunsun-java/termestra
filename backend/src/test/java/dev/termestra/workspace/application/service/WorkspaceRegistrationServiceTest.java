package dev.termestra.workspace.application.service;

import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.workspace.adapter.out.persistence.JdbcWorkspaceRegistrationLedger;
import dev.termestra.workspace.adapter.out.persistence.JdbcWorkspaceRepository;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationFailure;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationConflict;
import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.application.port.in.registration.RegisterWorkspaceCommand;
import dev.termestra.workspace.application.port.out.WorkspaceMetadataInitializer;
import dev.termestra.workspace.application.port.out.WorkspaceRegistrationLedger;
import dev.termestra.workspace.domain.model.Workspace;
import dev.termestra.workspace.domain.model.WorkspaceName;
import dev.termestra.workspace.domain.model.WorkspacePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceRegistrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test void registersTheDirectorysCurrentCheckoutWithoutGitSelection() {
        Fixture fixture = fixture(path -> { });
        String registrationId = UUID.randomUUID().toString();

        var result = fixture.service().register(command(registrationId));

        assertTrue(result.created());
        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("completed", attempt.state());
        assertEquals("active", workspaceState(fixture.database(), result.workspace().id()));
    }

    @Test void metadataFailureIsTerminalAndReleasesThePathClaim() {
        Fixture fixture = fixture(path -> { throw new IllegalStateException("disk is read-only"); });
        String registrationId = UUID.randomUUID().toString();

        WorkspaceRegistrationFailure failure = assertThrows(
                WorkspaceRegistrationFailure.class,
                () -> fixture.service().register(command(registrationId)));

        assertEquals("WORKSPACE_METADATA_INITIALIZATION_FAILED", failure.errorCode());
        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("failed", attempt.state());
        assertNull(attempt.workspaceId());
        assertTrue(fixture.workspaces().findAll().isEmpty());
    }

    @Test void sourceReadyWriteFailureIsTerminalAndReleasesThePathClaim() {
        Fixture fixture = fixture(path -> { },
                ledger -> new FailingSourceReadyLedger(ledger, false));
        String registrationId = UUID.randomUUID().toString();

        WorkspaceRegistrationFailure failure = assertThrows(
                WorkspaceRegistrationFailure.class,
                () -> fixture.service().register(command(registrationId)));

        assertEquals("WORKSPACE_REGISTRATION_LEDGER_FAILED", failure.errorCode());
        assertTrue(failure.retryable());
        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("failed", attempt.state());
        assertNull(attempt.workspaceId());
        assertTrue(fixture.workspaces().findAll().isEmpty());
        assertTrue(fixture.ledger().begin(new WorkspaceRegistrationLedger.Intent(
                UUID.randomUUID().toString(), "new-hash",
                Workspace.create(new WorkspaceName("Retry"), workspacePath(), NOW), NOW))
                instanceof WorkspaceRegistrationLedger.Begun);
    }

    @Test void sourceReadyWriteThatCommittedBeforeThrowingContinuesIdempotently() {
        Fixture fixture = fixture(path -> { },
                ledger -> new FailingSourceReadyLedger(ledger, true));
        String registrationId = UUID.randomUUID().toString();

        var result = fixture.service().register(command(registrationId));

        assertTrue(result.created());
        assertEquals("completed", fixture.ledger().find(registrationId).orElseThrow().state());
        assertEquals("active", workspaceState(fixture.database(), result.workspace().id()));
    }

    @Test void recoveryReleasesAReservedPathClaim() {
        Fixture fixture = fixture(path -> { });
        String registrationId = UUID.randomUUID().toString();
        fixture.ledger().begin(new WorkspaceRegistrationLedger.Intent(
                registrationId, "hash", Workspace.create(
                        new WorkspaceName("Reserved"), workspacePath(), NOW), NOW));

        fixture.service().recover();

        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("failed", attempt.state());
        assertEquals("WORKSPACE_REGISTRATION_INTERRUPTED", attempt.errorCode());
        assertNull(attempt.workspaceId());
        assertTrue(fixture.workspaces().findAll().isEmpty());
    }

    @Test void recoveryCompletesASourceReadyRegistration() {
        Fixture fixture = fixture(path -> { });
        String registrationId = UUID.randomUUID().toString();
        fixture.ledger().begin(new WorkspaceRegistrationLedger.Intent(
                registrationId, "hash", Workspace.create(
                        new WorkspaceName("Ready"), workspacePath(), NOW), NOW));
        fixture.ledger().markSourceReady(registrationId, NOW.plusMillis(1));

        fixture.service().recover();

        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("completed", attempt.state());
        assertNotNull(attempt.workspaceId());
        assertEquals("active", workspaceState(fixture.database(), attempt.workspaceId()));
    }

    @Test void recoveryTurnsSourceReadyMetadataFailureIntoATerminalAttempt() {
        Fixture fixture = fixture(path -> { throw new IllegalStateException("disk is read-only"); });
        String registrationId = UUID.randomUUID().toString();
        fixture.ledger().begin(new WorkspaceRegistrationLedger.Intent(
                registrationId, "hash", Workspace.create(
                        new WorkspaceName("Ready"), workspacePath(), NOW), NOW));
        fixture.ledger().markSourceReady(registrationId, NOW.plusMillis(1));

        fixture.service().recover();

        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("failed", attempt.state());
        assertEquals("WORKSPACE_METADATA_INITIALIZATION_FAILED", attempt.errorCode());
        assertNull(attempt.workspaceId());
        assertTrue(fixture.workspaces().findAll().isEmpty());
    }

    @Test void recoveryReleasesALegacySwitchingClaimAndKeepsItsDiagnostics() {
        Fixture fixture = fixture(path -> { });
        String registrationId = UUID.randomUUID().toString();
        Workspace workspace = Workspace.create(new WorkspaceName("Legacy"), workspacePath(), NOW);
        fixture.ledger().begin(new WorkspaceRegistrationLedger.Intent(
                registrationId, "hash", workspace, NOW));
        fixture.database().write("seed legacy switching state", connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE workspace_registration_attempts
                    SET selection_kind='local_branch',selected_branch='feature',
                        selected_ref_oid='oid',state='switching',checkout_outcome='unknown',
                        observed_head_kind='branch',observed_branch='feature',
                        observed_head_oid='oid',updated_at=?
                    WHERE registration_id=?
                    """)) {
                update.setLong(1, NOW.plusMillis(1).toEpochMilli());
                update.setString(2, registrationId);
                update.executeUpdate();
            }
            return null;
        });

        fixture.service().recover();

        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("failed", attempt.state());
        assertEquals("WORKSPACE_REGISTRATION_INTERRUPTED", attempt.errorCode());
        assertNull(attempt.workspaceId());
        assertFalse(fixture.workspaces().findByCanonicalPath(workspacePath().value()).isPresent());
        var status = fixture.service().status(registrationId);
        assertEquals("failed", status.status());
        assertNull(status.sourceRevisionChanged());
        assertEquals(new dev.termestra.workspace.application.port.in.registration.RegistrationStatusView.BranchHead(
                "feature", "oid"), status.observedHead());
    }

    @Test void legacyCurrentRequestHashStillReplaysAndDifferentRequestStillConflicts() {
        Fixture fixture = fixture(path -> { });
        String registrationId = UUID.randomUUID().toString();
        WorkspacePath legacyPath = new WorkspacePath("/tmp/legacy-current");
        String legacyHash = "e109f16aadc7d47791c9f864a57256f7a66ef6b86940dcea841c8d08bfdb459b";
        Workspace workspace = Workspace.create(new WorkspaceName("Legacy"), legacyPath, NOW);
        fixture.ledger().begin(new WorkspaceRegistrationLedger.Intent(
                registrationId, legacyHash, workspace, NOW));
        fixture.ledger().markSourceReady(registrationId, NOW.plusMillis(1));
        fixture.ledger().activate(registrationId, NOW.plusMillis(2));
        RegisterWorkspaceCommand legacyCommand = new RegisterWorkspaceCommand(
                registrationId, legacyPath.value(), "Legacy", "codex", "preset",
                "gpt", 7L, true);

        var replay = fixture.service().register(legacyCommand);

        assertFalse(replay.created());
        assertEquals(workspace.id().toString(), replay.workspace().id());
        RegisterWorkspaceCommand changed = new RegisterWorkspaceCommand(
                registrationId, legacyPath.value(), "Changed", "codex", "preset",
                "gpt", 7L, true);
        WorkspaceRegistrationConflict conflict = assertThrows(
                WorkspaceRegistrationConflict.class, () -> fixture.service().register(changed));
        assertEquals("WORKSPACE_REGISTRATION_ID_REUSED", conflict.errorCode());
    }

    private Fixture fixture(WorkspaceMetadataInitializer metadata) {
        return fixture(metadata, Function.identity());
    }

    private Fixture fixture(
            WorkspaceMetadataInitializer metadata,
            Function<WorkspaceRegistrationLedger, WorkspaceRegistrationLedger> decorateLedger) {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve(UUID.randomUUID() + ".db"));
        new SqliteSchemaMigrator(database, CLOCK).migrate();
        JdbcWorkspaceRegistrationLedger ledger = new JdbcWorkspaceRegistrationLedger(database);
        JdbcWorkspaceRepository workspaces = new JdbcWorkspaceRepository(database);
        WorkspaceRegistrationService service = new WorkspaceRegistrationService(
                decorateLedger.apply(ledger), workspaces, raw -> new WorkspacePath(raw), metadata,
                (workspace, startupCommand, commandPresetId, modelId,
                 expectedPresetRevision, autostart) -> OrchestratorStartView.disabled(),
                new RuntimeOperationCoordinator(), CLOCK);
        return new Fixture(service, ledger, workspaces, database);
    }

    private RegisterWorkspaceCommand command(String registrationId) {
        return new RegisterWorkspaceCommand(registrationId, workspacePath().value(), "Test",
                null, null, null, null, false);
    }

    private WorkspacePath workspacePath() {
        return new WorkspacePath(temporaryDirectory.toAbsolutePath().normalize().toString());
    }

    private static String workspaceState(SqliteDatabase database, String workspaceId) {
        return database.read("read workspace state", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT lifecycle_state FROM workspaces WHERE id=?")) {
                statement.setString(1, workspaceId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            }
        });
    }

    private record Fixture(
            WorkspaceRegistrationService service,
            JdbcWorkspaceRegistrationLedger ledger,
            JdbcWorkspaceRepository workspaces,
            SqliteDatabase database) { }

    private record FailingSourceReadyLedger(
            WorkspaceRegistrationLedger delegate,
            boolean afterCommit) implements WorkspaceRegistrationLedger {
        @Override public BeginResult begin(Intent intent) { return delegate.begin(intent); }
        @Override public void markSourceReady(String registrationId, Instant now) {
            if (afterCommit) delegate.markSourceReady(registrationId, now);
            throw new IllegalStateException("simulated source-ready write failure");
        }
        @Override public Workspace activate(String registrationId, Instant now) {
            return delegate.activate(registrationId, now);
        }
        @Override public void fail(String registrationId, Failure failure, Instant now) {
            delegate.fail(registrationId, failure, now);
        }
        @Override public java.util.Optional<Attempt> find(String registrationId) {
            return delegate.find(registrationId);
        }
        @Override public java.util.List<Attempt> recoverable(int limit) {
            return delegate.recoverable(limit);
        }
    }
}
