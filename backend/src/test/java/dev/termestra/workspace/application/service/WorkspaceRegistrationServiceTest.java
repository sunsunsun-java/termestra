package dev.termestra.workspace.application.service;

import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.workspace.adapter.out.persistence.JdbcWorkspaceRegistrationLedger;
import dev.termestra.workspace.adapter.out.persistence.JdbcWorkspaceRepository;
import dev.termestra.workspace.application.exception.GitRegistrationFailure;
import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.application.port.in.registration.RegisterWorkspaceCommand;
import dev.termestra.workspace.application.port.in.registration.RevisionSelection;
import dev.termestra.workspace.application.port.out.GitWorktreeAccess;
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
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

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

    @Test void aSelectionThatBecomesStaleAfterReservationFailsAndReleasesThePathClaim() {
        Fixture fixture = fixture(path -> { });
        GitWorktreeAccess.Inspection selected = inspection("main", "main-oid", "feature", "selected-oid");
        GitWorktreeAccess.Inspection advanced = inspection("main", "main-oid", "feature", "advanced-oid");
        fixture.git().inspect(selected, advanced);
        String token = fixture.tokens().issueSelection(selected, selected.localBranches().getFirst());
        String registrationId = UUID.randomUUID().toString();

        GitRegistrationFailure failure = assertThrows(GitRegistrationFailure.class,
                () -> fixture.service().register(command(registrationId, token)));

        assertEquals("GIT_SELECTION_STALE", failure.errorCode());
        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("failed", attempt.state());
        assertEquals("not_attempted", attempt.checkoutOutcome());
        assertNull(attempt.workspaceId());
        assertEquals(0, fixture.git().switchCount());
        assertTrue(fixture.workspaces().findAll().isEmpty());
    }

    @Test void anAppliedCheckoutWithTheWrongOidRemainsUncertainAndCannotActivate() {
        Fixture fixture = fixture(path -> { });
        GitWorktreeAccess.Inspection selected = inspection("main", "main-oid", "feature", "selected-oid");
        GitWorktreeAccess.Inspection wrong = inspection("feature", "wrong-oid", "feature", "wrong-oid");
        fixture.git().inspect(selected, selected)
                .checkout(new GitWorktreeAccess.Applied(wrong));
        String token = fixture.tokens().issueSelection(selected, selected.localBranches().getFirst());
        String registrationId = UUID.randomUUID().toString();

        GitRegistrationFailure failure = assertThrows(GitRegistrationFailure.class,
                () -> fixture.service().register(command(registrationId, token)));

        assertEquals("GIT_OPERATION_OUTCOME_UNKNOWN", failure.errorCode());
        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("uncertain", attempt.state());
        assertEquals("unknown", attempt.checkoutOutcome());
        assertNotNull(attempt.workspaceId());
        assertEquals(1, fixture.git().switchCount());
        assertTrue(fixture.workspaces().findAll().isEmpty());
    }

    @Test void recoveryTurnsMetadataFailureIntoATerminalAttemptAndReleasesItsClaim() {
        Fixture fixture = fixture(path -> { throw new IllegalStateException("disk is read-only"); });
        String registrationId = UUID.randomUUID().toString();
        Workspace workspace = Workspace.create(new WorkspaceName("Recovery"), workspacePath(), NOW);
        fixture.ledger().begin(new WorkspaceRegistrationLedger.Intent(
                registrationId, "hash", workspace, "current", null, null, NOW));
        fixture.ledger().recordCurrent(registrationId, NOW.plusMillis(1));

        fixture.service().recover();

        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("failed", attempt.state());
        assertEquals("WORKSPACE_METADATA_INITIALIZATION_FAILED", attempt.errorCode());
        assertNull(attempt.workspaceId());
        assertFalse(fixture.workspaces().findByCanonicalPath(workspacePath().value()).isPresent());
    }

    @Test void checkoutEvidenceWriteFailureRetainsTheClaimAsUncertain() {
        Fixture fixture = fixture(path -> { }, true);
        GitWorktreeAccess.Inspection selected = inspection("main", "main-oid", "feature", "selected-oid");
        GitWorktreeAccess.Inspection applied =
                inspection("feature", "selected-oid", "feature", "selected-oid");
        fixture.git().inspect(selected, selected)
                .checkout(new GitWorktreeAccess.Applied(applied));
        String token = fixture.tokens().issueSelection(selected, selected.localBranches().getFirst());
        String registrationId = UUID.randomUUID().toString();

        GitRegistrationFailure failure = assertThrows(GitRegistrationFailure.class,
                () -> fixture.service().register(command(registrationId, token)));

        assertEquals("GIT_OPERATION_OUTCOME_UNKNOWN", failure.errorCode());
        WorkspaceRegistrationLedger.Attempt attempt = fixture.ledger().find(registrationId).orElseThrow();
        assertEquals("uncertain", attempt.state());
        assertEquals("unknown", attempt.checkoutOutcome());
        assertNotNull(attempt.workspaceId());
    }

    private Fixture fixture(WorkspaceMetadataInitializer metadata) {
        return fixture(metadata, false);
    }

    private Fixture fixture(WorkspaceMetadataInitializer metadata, boolean failCheckoutEvidence) {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve(UUID.randomUUID() + ".db"));
        new SqliteSchemaMigrator(database, CLOCK).migrate();
        JdbcWorkspaceRegistrationLedger ledger = new JdbcWorkspaceRegistrationLedger(database);
        JdbcWorkspaceRepository workspaces = new JdbcWorkspaceRepository(database);
        WorkspaceRegistrationLedger serviceLedger = failCheckoutEvidence
                ? new FailingCheckoutLedger(ledger) : ledger;
        FakeGitWorktreeAccess git = new FakeGitWorktreeAccess();
        WorkspaceRegistrationTokenCodec tokens = new WorkspaceRegistrationTokenCodec(CLOCK);
        WorkspaceRegistrationService service = new WorkspaceRegistrationService(
                serviceLedger, workspaces, raw -> new WorkspacePath(raw), git, tokens, metadata,
                (workspace,startupCommand,commandPresetId,modelId,expectedPresetRevision,autostart) ->
                        OrchestratorStartView.disabled(),
                new RuntimeOperationCoordinator(), CLOCK);
        return new Fixture(service, ledger, workspaces, git, tokens);
    }

    private RegisterWorkspaceCommand command(String registrationId, String token) {
        return new RegisterWorkspaceCommand(registrationId,workspacePath().value(),"Test",null,
                null,null,null,false,new RevisionSelection.LocalBranch("feature",token));
    }

    private WorkspacePath workspacePath() {
        return new WorkspacePath(temporaryDirectory.toAbsolutePath().normalize().toString());
    }

    private GitWorktreeAccess.Inspection inspection(
            String headBranch, String headOid, String selectedBranch, String selectedOid) {
        String path = workspacePath().value();
        return new GitWorktreeAccess.Inspection(path, path + "/.git",
                new GitWorktreeAccess.BranchHead(headBranch, headOid),
                new GitWorktreeAccess.ChangeSummary(
                        GitWorktreeAccess.ChangeState.CLEAN, 0, "exact"),
                List.of(new GitWorktreeAccess.LocalBranch(selectedBranch, selectedOid, false)));
    }

    private record Fixture(
            WorkspaceRegistrationService service,
            JdbcWorkspaceRegistrationLedger ledger,
            JdbcWorkspaceRepository workspaces,
            FakeGitWorktreeAccess git,
            WorkspaceRegistrationTokenCodec tokens) { }

    private static final class FakeGitWorktreeAccess implements GitWorktreeAccess {
        private final ArrayDeque<Inspection> inspections = new ArrayDeque<>();
        private CheckoutOutcome checkout;
        private int switchCount;

        FakeGitWorktreeAccess inspect(Inspection... values) {
            inspections.addAll(List.of(values));
            return this;
        }

        FakeGitWorktreeAccess checkout(CheckoutOutcome value) {
            checkout = value;
            return this;
        }

        int switchCount() {
            return switchCount;
        }

        @Override public Inspection inspect(WorkspacePath path) {
            if (inspections.isEmpty()) throw new IllegalStateException("No Git inspection configured");
            return inspections.size() == 1 ? inspections.getFirst() : inspections.removeFirst();
        }

        @Override public CheckoutOutcome switchToExistingLocalBranch(
                WorkspacePath path, String branch, String expectedOid) {
            switchCount++;
            if (checkout == null) throw new IllegalStateException("No checkout outcome configured");
            return checkout;
        }
    }

    private record FailingCheckoutLedger(WorkspaceRegistrationLedger delegate)
            implements WorkspaceRegistrationLedger {
        @Override public BeginResult begin(Intent intent) { return delegate.begin(intent); }
        @Override public void markSwitching(String registrationId, Instant now) {
            delegate.markSwitching(registrationId, now);
        }
        @Override public void recordCurrent(String registrationId, Instant now) {
            delegate.recordCurrent(registrationId, now);
        }
        @Override public void recordCheckout(
                String registrationId, CheckoutEvidence evidence, Instant now) {
            throw new IllegalStateException("simulated checkout evidence write failure");
        }
        @Override public void confirmCheckout(
                String registrationId, CheckoutEvidence evidence, Instant now) {
            delegate.confirmCheckout(registrationId, evidence, now);
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
        @Override public List<Attempt> recoverable(int limit) { return delegate.recoverable(limit); }
    }
}
