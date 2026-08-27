package dev.termestra.workspace.application.service;

import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.workspace.application.exception.GitRegistrationFailure;
import dev.termestra.workspace.application.exception.GitWorktreeAccessFailure;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationConflict;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationNotFound;
import dev.termestra.workspace.application.port.in.CreateWorkspaceResult;
import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.application.port.in.WorkspaceView;
import dev.termestra.workspace.application.port.in.registration.*;
import dev.termestra.workspace.application.port.out.*;
import dev.termestra.workspace.domain.model.Workspace;
import dev.termestra.workspace.domain.model.WorkspaceName;
import dev.termestra.workspace.domain.model.WorkspacePath;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class WorkspaceRegistrationService implements WorkspaceRegistrationUseCase {
    private static final int DEFAULT_BRANCH_LIMIT = 50;
    private static final int MAX_BRANCH_LIMIT = 100;
    private static final int MAX_QUERY_CHARACTERS = 128;
    private static final int RECOVERY_BATCH = 256;

    private final WorkspaceRegistrationLedger ledger;
    private final WorkspaceRepository workspaces;
    private final WorkspacePathResolver paths;
    private final GitWorktreeAccess git;
    private final WorkspaceRegistrationTokenCodec tokens;
    private final WorkspaceMetadataInitializer metadata;
    private final OrchestratorStarter orchestrator;
    private final RuntimeOperationCoordinator operations;
    private final Clock clock;

    public WorkspaceRegistrationService(
            WorkspaceRegistrationLedger ledger,
            WorkspaceRepository workspaces,
            WorkspacePathResolver paths,
            GitWorktreeAccess git,
            WorkspaceRegistrationTokenCodec tokens,
            WorkspaceMetadataInitializer metadata,
            OrchestratorStarter orchestrator,
            RuntimeOperationCoordinator operations,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.paths = Objects.requireNonNull(paths);
        this.git = Objects.requireNonNull(git);
        this.tokens = Objects.requireNonNull(tokens);
        this.metadata = Objects.requireNonNull(metadata);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.operations = Objects.requireNonNull(operations);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RegistrationOptionsView inspect(
            String inspectionToken, String query, int requestedLimit, String cursor) {
        WorkspacePath path = paths.resolveDirectory(tokens.requirePath(inspectionToken));
        GitWorktreeAccess.Inspection inspection = git.inspect(path);
        String filter = Objects.requireNonNullElse(query, "").trim();
        if (filter.codePointCount(0, filter.length()) > MAX_QUERY_CHARACTERS) {
            throw new IllegalArgumentException("branch query exceeds " + MAX_QUERY_CHARACTERS + " characters");
        }
        int limit = requestedLimit <= 0 ? DEFAULT_BRANCH_LIMIT
                : Math.min(requestedLimit, MAX_BRANCH_LIMIT);
        String after = decodeCursor(cursor, filter);
        String lowered = filter.toLowerCase(Locale.ROOT);
        List<GitWorktreeAccess.LocalBranch> candidates = inspection.localBranches().stream()
                .filter(branch -> lowered.isEmpty()
                        || branch.name().toLowerCase(Locale.ROOT).contains(lowered))
                .filter(branch -> after == null || branch.name().compareTo(after) > 0)
                .sorted(Comparator.comparing(GitWorktreeAccess.LocalBranch::name))
                .toList();
        List<RegistrationOptionsView.BranchView> page = new ArrayList<>();
        int pageSize = Math.min(limit, candidates.size());
        for (int index = 0; index < pageSize; index++) {
            GitWorktreeAccess.LocalBranch branch = candidates.get(index);
            boolean current = inspection.head() instanceof GitWorktreeAccess.BranchHead head
                    && head.name().equals(branch.name());
            boolean selectable = current || !branch.checkedOutElsewhere();
            page.add(new RegistrationOptionsView.BranchView(
                    branch.name(), current, selectable,
                    selectable ? null : "checked_out_elsewhere",
                    selectable ? tokens.issueSelection(inspection, branch) : null));
        }
        String next = candidates.size() > pageSize && pageSize > 0
                ? encodeCursor(filter, candidates.get(pageSize - 1).name()) : null;
        return new RegistrationOptionsView(path.value(), view(inspection.head()),
                new RegistrationOptionsView.ChangeSummary(
                        inspection.changes().state().name().toLowerCase(Locale.ROOT),
                        inspection.changes().count(), inspection.changes().countAccuracy()),
                List.copyOf(page), next);
    }

    @Override
    public CreateWorkspaceResult register(RegisterWorkspaceCommand command) {
        WorkspacePath path = paths.resolveDirectory(command.path());
        return operations.exclusivelyRegisteringWorkspacePath(
                path.value(), () -> registerLocked(command, path));
    }

    private CreateWorkspaceResult registerLocked(RegisterWorkspaceCommand command, WorkspacePath path) {
        WorkspaceRegistrationLedger.Attempt prior = ledger.find(command.registrationId()).orElse(null);
        if (prior == null) {
            Workspace active = workspaces.findByCanonicalPath(path.value()).orElse(null);
            if (active != null) {
                RevisionPreparation requested = unverifiedExistingRevision(command.revisionSelection());
                return existing(command, active, requested);
            }
        }
        RevisionPreparation revision = prior == null
                ? prepareRevision(path, command.revisionSelection())
                : replayRevision(command.revisionSelection(), prior);
        String effectiveName = command.name() == null || command.name().isBlank()
                ? defaultName(path) : command.name();
        Workspace candidate = Workspace.create(new WorkspaceName(effectiveName), path, Instant.now(clock));
        String requestHash = requestHash(command, path, revision);
        WorkspaceRegistrationLedger.BeginResult begun = ledger.begin(
                new WorkspaceRegistrationLedger.Intent(
                        command.registrationId(), requestHash, candidate,
                        revision.kind(), revision.branch(), revision.targetOid(),
                        Instant.now(clock)));

        if (begun instanceof WorkspaceRegistrationLedger.Existing existing) {
            return existing(command, existing.workspace(), revision);
        }
        if (begun instanceof WorkspaceRegistrationLedger.Replay replay) {
            if ("uncertain".equals(replay.attempt().state())) {
                Workspace activated = reconcileUncertain(command, replay.attempt());
                return prepareOrchestrator(command, activated, true);
            }
            if ("checkout_applied".equals(replay.attempt().state())) {
                Workspace activated = recoverApplied(replay.attempt());
                return prepareOrchestrator(command, activated, true);
            }
            return replay(command, replay.attempt());
        }
        WorkspaceRegistrationLedger.Begun created = (WorkspaceRegistrationLedger.Begun) begun;
        Workspace workspace = created.workspace();
        Workspace activated = operations.exclusivelyWithWorkspace(
                workspace.id().toString(), () -> completeRegistration(command, workspace, revision));
        return prepareOrchestrator(command, activated, true);
    }

    private CreateWorkspaceResult prepareOrchestrator(
            RegisterWorkspaceCommand command, Workspace activated, boolean created) {
        OrchestratorStartView start = operations.withWorkspace(activated.id().toString(), () ->
                orchestrator.prepare(activated, command.startupCommand(), command.commandPresetId(),
                        command.autostartOrchestrator()));
        return new CreateWorkspaceResult(WorkspaceView.from(activated), start, created);
    }

    @Override
    public RegistrationStatusView status(String registrationId) {
        String normalized;
        try {
            normalized = java.util.UUID.fromString(registrationId).toString();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("registration_id must be a UUID", invalid);
        }
        WorkspaceRegistrationLedger.Attempt attempt = ledger.find(normalized)
                .orElseThrow(() -> new WorkspaceRegistrationNotFound(normalized));
        return statusView(attempt);
    }

    public void recover() {
        for (WorkspaceRegistrationLedger.Attempt attempt : ledger.recoverable(RECOVERY_BATCH)) {
            try {
                switch (attempt.state()) {
                    case "reserved" -> ledger.fail(attempt.registrationId(),
                            failure("failed", "not_attempted", "WORKSPACE_REGISTRATION_INTERRUPTED",
                                    (GitWorktreeAccess.Inspection) null, true), Instant.now(clock));
                    case "switching" -> ledger.fail(attempt.registrationId(),
                            failure("uncertain", "unknown", "GIT_OPERATION_OUTCOME_UNKNOWN",
                                    head(attempt), false), Instant.now(clock));
                    case "checkout_applied" -> recoverApplied(attempt);
                    default -> { }
                }
            } catch (RuntimeException ignored) {
                // The bounded recovery query will surface the attempt again on the next startup.
            }
        }
    }

    private Workspace completeRegistration(RegisterWorkspaceCommand command, Workspace workspace,
                                           RevisionPreparation revision) {
        GitWorktreeAccess.Inspection observed = null;
        boolean mutationInvoked = false;
        try {
            if (revision.branch() == null) {
                ledger.recordCurrent(command.registrationId(), Instant.now(clock));
            } else {
                ledger.markSwitching(command.registrationId(), Instant.now(clock));
                GitWorktreeAccess.Inspection fresh = git.inspect(workspace.path());
                GitWorktreeAccess.LocalBranch target = fresh.localBranches().stream()
                        .filter(branch -> branch.name().equals(revision.branch()))
                        .findFirst()
                        .orElseThrow(() -> stale(command.registrationId(), fresh.head()));
                try {
                    tokens.requireSelection(revision.selectionToken(), fresh, target);
                } catch (IllegalArgumentException stale) {
                    throw stale(command.registrationId(), fresh.head());
                }
                mutationInvoked = true;
                GitWorktreeAccess.CheckoutOutcome outcome = git.switchToExistingLocalBranch(
                        workspace.path(), revision.branch(), revision.targetOid());
                if (outcome instanceof GitWorktreeAccess.Applied applied) {
                    observed = applied.observed();
                    boolean selectedObserved = observed.head() instanceof GitWorktreeAccess.BranchHead head
                            && head.name().equals(revision.branch())
                            && head.oid().equals(revision.targetOid());
                    if (!selectedObserved) {
                        ledger.fail(command.registrationId(), failure("uncertain", "unknown",
                                "GIT_OPERATION_OUTCOME_UNKNOWN", observed, false), Instant.now(clock));
                        throw failure(command.registrationId(), "GIT_OPERATION_OUTCOME_UNKNOWN",
                                "Git reported success but the selected revision was not observed",
                                false, null, observed.head());
                    }
                    ledger.recordCheckout(command.registrationId(), evidence("applied", observed),
                            Instant.now(clock));
                } else if (outcome instanceof GitWorktreeAccess.Rejected rejected) {
                    observed = rejected.observed();
                    ledger.fail(command.registrationId(), failure("failed", "rejected",
                            rejected.errorCode(), observed, true), Instant.now(clock));
                    throw failure(command.registrationId(), rejected.errorCode(), rejected.diagnostic(),
                            false, false, observed.head());
                } else if (outcome instanceof GitWorktreeAccess.Unknown unknown) {
                    observed = unknown.observed();
                    ledger.fail(command.registrationId(), failure("uncertain", "unknown",
                            unknown.errorCode(), observed, false), Instant.now(clock));
                    throw failure(command.registrationId(), unknown.errorCode(), unknown.diagnostic(),
                            false, null, observed.head());
                }
            }
            try {
                metadata.initialize(workspace.path());
            } catch (RuntimeException error) {
                ledger.fail(command.registrationId(), failure("failed",
                        revision.branch() == null ? "not_attempted" : "applied",
                        "WORKSPACE_METADATA_INITIALIZATION_FAILED", observed, true), Instant.now(clock));
                throw failure(command.registrationId(), "WORKSPACE_METADATA_INITIALIZATION_FAILED",
                        error.getMessage(), false, revision.branch() != null,
                        observed == null ? null : observed.head());
            }
            return ledger.activate(command.registrationId(), Instant.now(clock));
        } catch (GitRegistrationFailure failure) {
            WorkspaceRegistrationLedger.Attempt current = ledger.find(command.registrationId()).orElse(null);
            if (current != null && "switching".equals(current.state())
                    && "GIT_SELECTION_STALE".equals(failure.errorCode())) {
                ledger.fail(command.registrationId(), failure("failed", "not_attempted",
                        failure.errorCode(), failure.observedHead(), true), Instant.now(clock));
            }
            throw failure;
        } catch (GitWorktreeAccessFailure failure) {
            String state = mutationInvoked ? "uncertain" : "failed";
            String outcome = mutationInvoked ? "unknown" : "not_attempted";
            String errorCode = mutationInvoked
                    ? "GIT_OPERATION_OUTCOME_UNKNOWN" : failure.errorCode();
            ledger.fail(command.registrationId(), failure(state, outcome,
                    errorCode, observed, !mutationInvoked), Instant.now(clock));
            if (mutationInvoked) {
                throw failure(command.registrationId(), errorCode, failure.getMessage(),
                        false, null, observed == null ? null : observed.head());
            }
            throw failure(command.registrationId(), failure.errorCode(), failure.getMessage(),
                    failure.retryable(), false, observed == null ? null : observed.head());
        } catch (RuntimeException error) {
            WorkspaceRegistrationLedger.Attempt current = ledger.find(command.registrationId()).orElse(null);
            if (current != null && List.of("reserved", "switching").contains(current.state())) {
                ledger.fail(command.registrationId(), mutationInvoked
                        ? failure("uncertain", "unknown", "GIT_OPERATION_OUTCOME_UNKNOWN",
                                observed, false)
                        : failure("failed", "not_attempted", "WORKSPACE_REGISTRATION_FAILED",
                                observed, true), Instant.now(clock));
                if (mutationInvoked) {
                    throw failure(command.registrationId(), "GIT_OPERATION_OUTCOME_UNKNOWN",
                            error.getMessage(), false, null,
                            observed == null ? null : observed.head());
                }
            }
            throw error;
        }
    }

    private CreateWorkspaceResult existing(RegisterWorkspaceCommand command, Workspace workspace,
                                           RevisionPreparation revision) {
        if (revision.branch() != null) {
            GitWorktreeAccess.Inspection inspection = git.inspect(workspace.path());
            if (!(inspection.head() instanceof GitWorktreeAccess.BranchHead head)
                    || !head.name().equals(revision.branch())) {
                throw new WorkspaceRegistrationConflict(
                        "WORKSPACE_SOURCE_SELECTION_CONFLICT",
                        "Workspace path is already registered on a different checkout",
                        workspace.id().toString());
            }
        }
        metadata.initialize(workspace.path());
        return new CreateWorkspaceResult(WorkspaceView.from(workspace),
                OrchestratorStartView.disabled(), false);
    }

    private CreateWorkspaceResult replay(RegisterWorkspaceCommand command,
                                         WorkspaceRegistrationLedger.Attempt attempt) {
        if ("completed".equals(attempt.state()) && attempt.workspaceId() != null) {
            Workspace workspace = workspaces.find(attempt.workspaceId())
                    .orElseThrow(() -> new IllegalStateException("Completed registration lost its Workspace"));
            return new CreateWorkspaceResult(WorkspaceView.from(workspace),
                    OrchestratorStartView.disabled(), false);
        }
        if (List.of("failed", "uncertain").contains(attempt.state())) {
            throw failure(command.registrationId(),
                    Objects.requireNonNullElse(attempt.errorCode(), "WORKSPACE_REGISTRATION_FAILED"),
                    "Workspace registration did not complete", false,
                    "applied".equals(attempt.checkoutOutcome()) ? true
                            : "unknown".equals(attempt.checkoutOutcome()) ? null : false,
                    head(attempt));
        }
        throw new WorkspaceRegistrationConflict(
                "WORKSPACE_REGISTRATION_IN_PROGRESS",
                "Workspace registration is still in progress", attempt.workspaceId());
    }

    private RevisionPreparation prepareRevision(WorkspacePath path, RevisionSelection selection) {
        if (selection instanceof RevisionSelection.Current current) {
            return new RevisionPreparation("current", null, null, current.selectionToken());
        }
        RevisionSelection.LocalBranch selected = (RevisionSelection.LocalBranch) selection;
        GitWorktreeAccess.Inspection inspection = git.inspect(path);
        GitWorktreeAccess.LocalBranch branch = inspection.localBranches().stream()
                .filter(value -> value.name().equals(selected.name()))
                .findFirst()
                .orElseThrow(() -> stale(null, inspection.head()));
        if (branch.checkedOutElsewhere()) {
            throw failure(null, "GIT_BRANCH_CHECKED_OUT_ELSEWHERE",
                    "Selected branch is checked out by another worktree", false, false,
                    inspection.head());
        }
        try {
            tokens.requireSelection(selected.selectionToken(), inspection, branch);
        } catch (IllegalArgumentException stale) {
            throw stale(null, inspection.head());
        }
        return new RevisionPreparation("local_branch", branch.name(), branch.oid(),
                selected.selectionToken());
    }

    private static RevisionPreparation unverifiedExistingRevision(RevisionSelection selection) {
        if (selection instanceof RevisionSelection.LocalBranch branch) {
            return new RevisionPreparation("local_branch", branch.name(), null, branch.selectionToken());
        }
        return new RevisionPreparation("current", null, null, selection.selectionToken());
    }

    private static RevisionPreparation replayRevision(
            RevisionSelection selection, WorkspaceRegistrationLedger.Attempt prior) {
        if (selection instanceof RevisionSelection.LocalBranch branch
                && "local_branch".equals(prior.selectionKind())
                && branch.name().equals(prior.selectedBranch())) {
            return new RevisionPreparation(prior.selectionKind(), prior.selectedBranch(),
                    prior.selectedRefOid(), branch.selectionToken());
        }
        if (selection instanceof RevisionSelection.Current
                && "current".equals(prior.selectionKind())) {
            return new RevisionPreparation("current", null, null, selection.selectionToken());
        }
        return unverifiedExistingRevision(selection);
    }

    private Workspace recoverApplied(WorkspaceRegistrationLedger.Attempt attempt) {
        if (attempt.workspaceId() == null) {
            throw new IllegalStateException("Recoverable registration lost its Workspace claim");
        }
        Workspace workspace = findPreparingWorkspace(attempt);
        return operations.exclusivelyWithWorkspace(workspace.id().toString(), () -> {
            try {
                metadata.initialize(workspace.path());
            } catch (RuntimeException error) {
                ledger.fail(attempt.registrationId(), failure("failed", attempt.checkoutOutcome(),
                        "WORKSPACE_METADATA_INITIALIZATION_FAILED", head(attempt), true),
                        Instant.now(clock));
                throw error;
            }
            return ledger.activate(attempt.registrationId(), Instant.now(clock));
        });
    }

    private Workspace reconcileUncertain(
            RegisterWorkspaceCommand command, WorkspaceRegistrationLedger.Attempt attempt) {
        if (attempt.workspaceId() == null || attempt.selectedBranch() == null) {
            throw failure(command.registrationId(), "GIT_OPERATION_OUTCOME_UNKNOWN",
                    "Workspace registration still requires attention", false, null, head(attempt));
        }
        Workspace workspace = findPreparingWorkspace(attempt);
        return operations.exclusivelyWithWorkspace(workspace.id().toString(), () -> {
            GitWorktreeAccess.Inspection inspection = git.inspect(workspace.path());
            boolean selectedObserved = inspection.head() instanceof GitWorktreeAccess.BranchHead branch
                    && branch.name().equals(attempt.selectedBranch())
                    && branch.oid().equals(attempt.selectedRefOid());
            if (!selectedObserved) {
                ledger.fail(command.registrationId(), failure("failed", "rejected",
                        "GIT_OPERATION_RECONCILED_NOT_APPLIED", inspection, true), Instant.now(clock));
                throw failure(command.registrationId(), "GIT_OPERATION_RECONCILED_NOT_APPLIED",
                        "The selected branch was not observed; inspect the worktree before trying again",
                        true, null, inspection.head());
            }
            ledger.confirmCheckout(command.registrationId(), evidence("applied", inspection),
                    Instant.now(clock));
            try {
                metadata.initialize(workspace.path());
            } catch (RuntimeException error) {
                ledger.fail(command.registrationId(), failure("failed", "applied",
                        "WORKSPACE_METADATA_INITIALIZATION_FAILED", inspection, true), Instant.now(clock));
                throw failure(command.registrationId(), "WORKSPACE_METADATA_INITIALIZATION_FAILED",
                        error.getMessage(), false, true, inspection.head());
            }
            return ledger.activate(command.registrationId(), Instant.now(clock));
        });
    }

    private Workspace findPreparingWorkspace(WorkspaceRegistrationLedger.Attempt attempt) {
        return new Workspace(dev.termestra.shared.id.WorkspaceId.parse(attempt.workspaceId()),
                new WorkspaceName(defaultName(new WorkspacePath(attempt.canonicalPath()))),
                new WorkspacePath(attempt.canonicalPath()), attempt.createdAt());
    }

    private static WorkspaceRegistrationLedger.CheckoutEvidence evidence(
            String outcome, GitWorktreeAccess.Inspection inspection) {
        return new WorkspaceRegistrationLedger.CheckoutEvidence(outcome,
                inspection == null ? null : kind(inspection.head()),
                inspection == null ? null : name(inspection.head()),
                inspection == null ? null : oid(inspection.head()));
    }

    private static WorkspaceRegistrationLedger.Failure failure(
            String state, String outcome, String errorCode,
            GitWorktreeAccess.Inspection inspection, boolean release) {
        return new WorkspaceRegistrationLedger.Failure(state, outcome, errorCode,
                inspection == null ? null : kind(inspection.head()),
                inspection == null ? null : name(inspection.head()),
                inspection == null ? null : oid(inspection.head()), release);
    }

    private static WorkspaceRegistrationLedger.Failure failure(
            String state, String outcome, String errorCode,
            RegistrationOptionsView.HeadView head, boolean release) {
        return new WorkspaceRegistrationLedger.Failure(state, outcome, errorCode,
                head == null ? null : viewKind(head),
                head instanceof RegistrationOptionsView.BranchHead value ? value.name()
                        : head instanceof RegistrationOptionsView.UnbornHead value ? value.name() : null,
                head instanceof RegistrationOptionsView.BranchHead value ? value.oid()
                        : head instanceof RegistrationOptionsView.DetachedHead value ? value.oid() : null,
                release);
    }

    private static GitRegistrationFailure stale(String registrationId, GitWorktreeAccess.Head head) {
        return failure(registrationId, "GIT_SELECTION_STALE", "Git selection is stale",
                true, false, head);
    }

    private static GitRegistrationFailure failure(
            String registrationId, String code, String message, boolean retryable,
            Boolean changed, GitWorktreeAccess.Head head) {
        return new GitRegistrationFailure(registrationId, code,
                Objects.requireNonNullElse(message, code), retryable, changed,
                head == null ? null : view(head));
    }

    private static GitRegistrationFailure failure(
            String registrationId, String code, String message, boolean retryable,
            Boolean changed, RegistrationOptionsView.HeadView head) {
        return new GitRegistrationFailure(registrationId, code,
                Objects.requireNonNullElse(message, code), retryable, changed, head);
    }

    private static RegistrationStatusView statusView(WorkspaceRegistrationLedger.Attempt attempt) {
        String status = switch (attempt.state()) {
            case "completed" -> "completed";
            case "failed" -> "failed";
            case "uncertain" -> "needs_attention";
            default -> "processing";
        };
        Boolean changed = switch (attempt.checkoutOutcome()) {
            case "applied" -> true;
            case "unknown" -> null;
            default -> false;
        };
        return new RegistrationStatusView(attempt.registrationId(), status,
                attempt.workspaceId(), attempt.errorCode(), changed, head(attempt));
    }

    private static RegistrationOptionsView.HeadView head(WorkspaceRegistrationLedger.Attempt attempt) {
        if (attempt.observedHeadKind() == null) return null;
        return switch (attempt.observedHeadKind()) {
            case "branch" -> new RegistrationOptionsView.BranchHead(
                    attempt.observedBranch(), attempt.observedHeadOid());
            case "detached" -> new RegistrationOptionsView.DetachedHead(attempt.observedHeadOid());
            case "unborn" -> new RegistrationOptionsView.UnbornHead(attempt.observedBranch());
            default -> null;
        };
    }

    private static RegistrationOptionsView.HeadView view(GitWorktreeAccess.Head head) {
        if (head instanceof GitWorktreeAccess.BranchHead value) {
            return new RegistrationOptionsView.BranchHead(value.name(), value.oid());
        }
        if (head instanceof GitWorktreeAccess.DetachedHead value) {
            return new RegistrationOptionsView.DetachedHead(value.oid());
        }
        return new RegistrationOptionsView.UnbornHead(((GitWorktreeAccess.UnbornHead) head).name());
    }

    private static String kind(GitWorktreeAccess.Head head) {
        if (head instanceof GitWorktreeAccess.BranchHead) return "branch";
        if (head instanceof GitWorktreeAccess.DetachedHead) return "detached";
        return "unborn";
    }

    private static String name(GitWorktreeAccess.Head head) {
        if (head instanceof GitWorktreeAccess.BranchHead value) return value.name();
        if (head instanceof GitWorktreeAccess.UnbornHead value) return value.name();
        return null;
    }

    private static String oid(GitWorktreeAccess.Head head) {
        if (head instanceof GitWorktreeAccess.BranchHead value) return value.oid();
        if (head instanceof GitWorktreeAccess.DetachedHead value) return value.oid();
        return null;
    }

    private static String viewKind(RegistrationOptionsView.HeadView head) {
        if (head instanceof RegistrationOptionsView.BranchHead) return "branch";
        if (head instanceof RegistrationOptionsView.DetachedHead) return "detached";
        return "unborn";
    }

    private static String requestHash(RegisterWorkspaceCommand command, WorkspacePath path,
                                      RevisionPreparation revision) {
        String value = String.join("\0", path.value(), Objects.requireNonNullElse(command.name(), ""),
                Objects.requireNonNullElse(command.startupCommand(), ""),
                Objects.requireNonNullElse(command.commandPresetId(), ""),
                Boolean.toString(command.autostartOrchestrator()), revision.kind(),
                Objects.requireNonNullElse(revision.branch(), ""),
                Objects.requireNonNullElse(revision.targetOid(), ""));
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encodeCursor(String query, String after) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (query + "\0" + after).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String cursor, String query) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 2_048) throw new IllegalArgumentException("branch cursor is too long");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('\0');
            if (separator < 0 || !decoded.substring(0, separator).equals(query)) {
                throw new IllegalArgumentException("branch cursor does not match this query");
            }
            return decoded.substring(separator + 1);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("branch cursor is invalid", error);
        }
    }

    private static String defaultName(WorkspacePath workspacePath) {
        java.nio.file.Path selected = java.nio.file.Path.of(workspacePath.value());
        java.nio.file.Path fileName = selected.getFileName();
        return fileName == null ? workspacePath.value() : fileName.toString();
    }

    private record RevisionPreparation(
            String kind, String branch, String targetOid, String selectionToken) { }

}
