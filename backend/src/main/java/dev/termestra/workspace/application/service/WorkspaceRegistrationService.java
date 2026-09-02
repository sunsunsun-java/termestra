package dev.termestra.workspace.application.service;

import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationConflict;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationFailure;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationNotFound;
import dev.termestra.workspace.application.port.in.CreateWorkspaceResult;
import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.application.port.in.WorkspaceView;
import dev.termestra.workspace.application.port.in.registration.RegisterWorkspaceCommand;
import dev.termestra.workspace.application.port.in.registration.RegistrationStatusView;
import dev.termestra.workspace.application.port.in.registration.WorkspaceRegistrationUseCase;
import dev.termestra.workspace.application.port.out.OrchestratorStarter;
import dev.termestra.workspace.application.port.out.WorkspaceMetadataInitializer;
import dev.termestra.workspace.application.port.out.WorkspacePathResolver;
import dev.termestra.workspace.application.port.out.WorkspaceRegistrationLedger;
import dev.termestra.workspace.application.port.out.WorkspaceRepository;
import dev.termestra.workspace.domain.model.Workspace;
import dev.termestra.workspace.domain.model.WorkspaceName;
import dev.termestra.workspace.domain.model.WorkspacePath;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class WorkspaceRegistrationService implements WorkspaceRegistrationUseCase {
    private static final int RECOVERY_BATCH = 256;

    private final WorkspaceRegistrationLedger ledger;
    private final WorkspaceRepository workspaces;
    private final WorkspacePathResolver paths;
    private final WorkspaceMetadataInitializer metadata;
    private final OrchestratorStarter orchestrator;
    private final RuntimeOperationCoordinator operations;
    private final Clock clock;

    public WorkspaceRegistrationService(
            WorkspaceRegistrationLedger ledger,
            WorkspaceRepository workspaces,
            WorkspacePathResolver paths,
            WorkspaceMetadataInitializer metadata,
            OrchestratorStarter orchestrator,
            RuntimeOperationCoordinator operations,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.paths = Objects.requireNonNull(paths);
        this.metadata = Objects.requireNonNull(metadata);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.operations = Objects.requireNonNull(operations);
        this.clock = Objects.requireNonNull(clock);
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
            if (active != null) return existing(command, active);
        }

        String effectiveName = command.name() == null || command.name().isBlank()
                ? defaultName(path) : command.name();
        Workspace candidate = Workspace.create(new WorkspaceName(effectiveName), path, Instant.now(clock));
        WorkspaceRegistrationLedger.BeginResult result = ledger.begin(
                new WorkspaceRegistrationLedger.Intent(
                        command.registrationId(), requestHash(command, path), candidate,
                        Instant.now(clock)));

        if (result instanceof WorkspaceRegistrationLedger.Existing existing) {
            return existing(command, existing.workspace());
        }
        if (result instanceof WorkspaceRegistrationLedger.Replay replay) {
            if ("checkout_applied".equals(replay.attempt().state())) {
                Workspace activated = recoverApplied(replay.attempt());
                return prepareOrchestrator(command, activated, true);
            }
            return replay(command, replay.attempt());
        }

        Workspace workspace = ((WorkspaceRegistrationLedger.Begun) result).workspace();
        Workspace activated = operations.exclusivelyWithWorkspace(
                workspace.id().toString(), () -> completeRegistration(command, workspace));
        return prepareOrchestrator(command, activated, true);
    }

    private Workspace completeRegistration(RegisterWorkspaceCommand command, Workspace workspace) {
        markSourceReady(command.registrationId());
        try {
            metadata.initialize(workspace.path());
        } catch (RuntimeException error) {
            ledger.fail(command.registrationId(), failure(
                    "failed", "not_attempted", "WORKSPACE_METADATA_INITIALIZATION_FAILED", true),
                    Instant.now(clock));
            throw new WorkspaceRegistrationFailure(
                    command.registrationId(), "WORKSPACE_METADATA_INITIALIZATION_FAILED",
                    Objects.requireNonNullElse(error.getMessage(), "Workspace metadata initialization failed"),
                    false);
        }
        return ledger.activate(command.registrationId(), Instant.now(clock));
    }

    private void markSourceReady(String registrationId) {
        try {
            ledger.markSourceReady(registrationId, Instant.now(clock));
        } catch (RuntimeException transitionFailure) {
            WorkspaceRegistrationLedger.Attempt current;
            try {
                current = ledger.find(registrationId).orElse(null);
            } catch (RuntimeException inspectionFailure) {
                transitionFailure.addSuppressed(inspectionFailure);
                throw transitionFailure;
            }
            // The write may have committed even when its caller observed an error. Advancing
            // from checkout_applied is idempotent because this version performs no Git mutation.
            if (current != null && "checkout_applied".equals(current.state())) return;
            if (current != null && "reserved".equals(current.state())) {
                try {
                    ledger.fail(registrationId, failure(
                            "failed", "not_attempted", "WORKSPACE_REGISTRATION_LEDGER_FAILED", true),
                            Instant.now(clock));
                } catch (RuntimeException cleanupFailure) {
                    transitionFailure.addSuppressed(cleanupFailure);
                    throw transitionFailure;
                }
                throw new WorkspaceRegistrationFailure(
                        registrationId,
                        "WORKSPACE_REGISTRATION_LEDGER_FAILED",
                        Objects.requireNonNullElse(
                                transitionFailure.getMessage(), "Workspace registration ledger failed"),
                        true);
            }
            throw transitionFailure;
        }
    }

    private CreateWorkspaceResult existing(RegisterWorkspaceCommand command, Workspace workspace) {
        metadata.initialize(workspace.path());
        return prepareMissingOrchestrator(command, workspace);
    }

    private CreateWorkspaceResult replay(
            RegisterWorkspaceCommand command, WorkspaceRegistrationLedger.Attempt attempt) {
        if ("completed".equals(attempt.state()) && attempt.workspaceId() != null) {
            Workspace workspace = workspaces.find(attempt.workspaceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Completed registration lost its Workspace"));
            return prepareMissingOrchestrator(command, workspace);
        }
        if ("uncertain".equals(attempt.state())) {
            ledger.fail(attempt.registrationId(), failure(
                    "failed", attempt.checkoutOutcome(),
                    "WORKSPACE_REGISTRATION_INTERRUPTED", true),
                    Instant.now(clock));
            throw new WorkspaceRegistrationFailure(
                    command.registrationId(),
                    "WORKSPACE_REGISTRATION_INTERRUPTED",
                    "Legacy Workspace registration requires inspection before retrying with a new registration_id",
                    true);
        }
        if ("failed".equals(attempt.state())) {
            throw new WorkspaceRegistrationFailure(
                    command.registrationId(),
                    Objects.requireNonNullElse(attempt.errorCode(), "WORKSPACE_REGISTRATION_FAILED"),
                    "Workspace registration did not complete", false);
        }
        throw new WorkspaceRegistrationConflict(
                "WORKSPACE_REGISTRATION_IN_PROGRESS",
                "Workspace registration is still in progress", attempt.workspaceId());
    }

    private CreateWorkspaceResult prepareOrchestrator(
            RegisterWorkspaceCommand command, Workspace workspace, boolean created) {
        OrchestratorStartView start = operations.withWorkspace(workspace.id().toString(), () ->
                orchestrator.prepare(workspace, command.startupCommand(), command.commandPresetId(),
                        command.modelId(), command.expectedPresetRevision(),
                        command.autostartOrchestrator()));
        return new CreateWorkspaceResult(WorkspaceView.from(workspace), start, created);
    }

    private CreateWorkspaceResult prepareMissingOrchestrator(
            RegisterWorkspaceCommand command, Workspace workspace) {
        OrchestratorStartView start = operations.withWorkspace(workspace.id().toString(), () ->
                orchestrator.prepareIfMissing(workspace, command.startupCommand(),
                        command.commandPresetId(), command.modelId(),
                        command.expectedPresetRevision(), command.autostartOrchestrator()));
        return new CreateWorkspaceResult(WorkspaceView.from(workspace), start, false);
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
        String status = switch (attempt.state()) {
            case "completed" -> "completed";
            case "failed" -> "failed";
            case "uncertain" -> "needs_attention";
            default -> "processing";
        };
        return new RegistrationStatusView(
                attempt.registrationId(), status, attempt.workspaceId(), attempt.errorCode(),
                sourceRevisionChanged(attempt), observedHead(attempt));
    }

    public void recover() {
        for (WorkspaceRegistrationLedger.Attempt attempt : ledger.recoverable(RECOVERY_BATCH)) {
            try {
                switch (attempt.state()) {
                    case "reserved" -> ledger.fail(attempt.registrationId(), failure(
                            "failed", "not_attempted", "WORKSPACE_REGISTRATION_INTERRUPTED", true),
                            Instant.now(clock));
                    // switching/uncertain rows can only have been written by an older version.
                    // Preserve their diagnostic evidence, but release the invisible preparing
                    // Workspace so the canonical path can be registered again deliberately.
                    case "switching", "uncertain" -> ledger.fail(attempt.registrationId(), failure(
                            "failed", "unknown", "WORKSPACE_REGISTRATION_INTERRUPTED", true),
                            Instant.now(clock));
                    case "checkout_applied" -> recoverApplied(attempt);
                    default -> { }
                }
            } catch (RuntimeException ignored) {
                // The bounded recovery query will surface the attempt again on the next startup.
            }
        }
    }

    private Workspace recoverApplied(WorkspaceRegistrationLedger.Attempt attempt) {
        if (attempt.workspaceId() == null) {
            throw new IllegalStateException("Recoverable registration lost its Workspace claim");
        }
        Workspace workspace = new Workspace(
                WorkspaceId.parse(attempt.workspaceId()),
                new WorkspaceName(defaultName(new WorkspacePath(attempt.canonicalPath()))),
                new WorkspacePath(attempt.canonicalPath()), attempt.createdAt());
        return operations.exclusivelyWithWorkspace(workspace.id().toString(), () -> {
            try {
                metadata.initialize(workspace.path());
            } catch (RuntimeException error) {
                ledger.fail(attempt.registrationId(), failure(
                        "failed", attempt.checkoutOutcome(),
                        "WORKSPACE_METADATA_INITIALIZATION_FAILED", true), Instant.now(clock));
                throw error;
            }
            return ledger.activate(attempt.registrationId(), Instant.now(clock));
        });
    }

    private static WorkspaceRegistrationLedger.Failure failure(
            String state, String outcome, String errorCode, boolean release) {
        return new WorkspaceRegistrationLedger.Failure(state, outcome, errorCode, release);
    }

    private static Boolean sourceRevisionChanged(WorkspaceRegistrationLedger.Attempt attempt) {
        return switch (attempt.checkoutOutcome()) {
            case "applied" -> true;
            case "unknown" -> null;
            default -> false;
        };
    }

    private static RegistrationStatusView.ObservedHead observedHead(
            WorkspaceRegistrationLedger.Attempt attempt) {
        if (attempt.observedHeadKind() == null) return null;
        return switch (attempt.observedHeadKind()) {
            case "branch" -> new RegistrationStatusView.BranchHead(
                    attempt.observedBranch(), attempt.observedHeadOid());
            case "detached" -> new RegistrationStatusView.DetachedHead(attempt.observedHeadOid());
            case "unborn" -> new RegistrationStatusView.UnbornHead(attempt.observedBranch());
            default -> null;
        };
    }

    private static String requestHash(RegisterWorkspaceCommand command, WorkspacePath path) {
        // Keep the former "current" request shape so retries created by the previous version
        // remain idempotent after branch selection is removed.
        String value = String.join("\0", path.value(), Objects.requireNonNullElse(command.name(), ""),
                Objects.requireNonNullElse(command.startupCommand(), ""),
                Objects.requireNonNullElse(command.commandPresetId(), ""),
                Objects.requireNonNullElse(command.modelId(), ""),
                Objects.toString(command.expectedPresetRevision(), ""),
                Boolean.toString(command.autostartOrchestrator()), "current", "", "");
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String defaultName(WorkspacePath workspacePath) {
        java.nio.file.Path selected = java.nio.file.Path.of(workspacePath.value());
        java.nio.file.Path fileName = selected.getFileName();
        return fileName == null ? workspacePath.value() : fileName.toString();
    }
}
