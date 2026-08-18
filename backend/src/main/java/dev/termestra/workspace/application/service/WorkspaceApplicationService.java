package dev.termestra.workspace.application.service;

import dev.termestra.workspace.application.port.in.*;
import dev.termestra.workspace.application.port.out.*;
import dev.termestra.workspace.domain.model.*;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class WorkspaceApplicationService implements CreateWorkspaceUseCase, ListWorkspacesQuery,DeleteWorkspaceUseCase {
    private final WorkspaceRepository repository;
    private final WorkspacePathResolver pathResolver;
    private final OrchestratorStarter orchestratorStarter;
    private final WorkspaceMetadataInitializer metadataInitializer;
    private final Clock clock;
    private final WorkspaceRuntimeCleaner runtimeCleaner;
    private final RuntimeOperationCoordinator operations;
    private final ConcurrentHashMap<String, PathCreationLock> pathCreations = new ConcurrentHashMap<>();

    public WorkspaceApplicationService(WorkspaceRepository repository, WorkspacePathResolver pathResolver,
                                       OrchestratorStarter orchestratorStarter,
                                       WorkspaceMetadataInitializer metadataInitializer,
                                       WorkspaceRuntimeCleaner runtimeCleaner, Clock clock) {
        this(repository,pathResolver,orchestratorStarter,metadataInitializer,runtimeCleaner,clock,
                new RuntimeOperationCoordinator());
    }

    public WorkspaceApplicationService(WorkspaceRepository repository, WorkspacePathResolver pathResolver,
                                       OrchestratorStarter orchestratorStarter,
                                       WorkspaceMetadataInitializer metadataInitializer,
                                       WorkspaceRuntimeCleaner runtimeCleaner, Clock clock,
                                       RuntimeOperationCoordinator operations) {
        this.repository = repository; this.pathResolver = pathResolver; this.orchestratorStarter = orchestratorStarter;
        this.metadataInitializer=metadataInitializer;this.runtimeCleaner=runtimeCleaner; this.clock = clock;
        this.operations=operations;
    }

    @Override public CreateWorkspaceResult create(CreateWorkspaceCommand command) {
        WorkspacePath path = pathResolver.resolveDirectory(command.path());
        PathCreationLock retained = pathCreations.compute(path.value(), (ignored, current) -> {
            PathCreationLock value = current == null ? new PathCreationLock() : current;
            value.references++;
            return value;
        });
        retained.lock.lock();
        try {
            return createSingleFlight(command, path);
        } finally {
            retained.lock.unlock();
            releasePathCreation(path.value(), retained);
        }
    }

    private CreateWorkspaceResult createSingleFlight(CreateWorkspaceCommand command, WorkspacePath path) {
        String requestedName = command.name();
        String effectiveName = requestedName == null || requestedName.isBlank()
                ? defaultName(path) : requestedName;
        Workspace candidate = Workspace.create(new WorkspaceName(effectiveName), path, Instant.now(clock));
        WorkspaceRegistration registration = repository.register(candidate);
        Workspace workspace = registration.workspace();
        CreateWorkspaceResult initialized = operations.exclusivelyWithWorkspace(workspace.id().toString(),()->{
            if(repository.find(workspace.id().toString()).isEmpty())throw new IllegalArgumentException("Workspace was deleted while it was being created: "+workspace.id());
            initializeMetadata(registration);
            if (!registration.created()) {
                return new CreateWorkspaceResult(WorkspaceView.from(workspace), OrchestratorStartView.disabled(), false);
            }
            return new CreateWorkspaceResult(WorkspaceView.from(workspace), OrchestratorStartView.disabled(), true);
        });
        if (!initialized.created()) return initialized;

        // Preparing the Orchestrator may launch a CLI and wait for its prompt. Keep deletion
        // excluded while that happens, but do not freeze ordinary workspace reads or other agents.
        try {
            return operations.withWorkspace(workspace.id().toString(), () -> {
                if (repository.find(workspace.id().toString()).isEmpty()) {
                    throw new IllegalArgumentException(
                            "Workspace was deleted while its Orchestrator was being prepared: " + workspace.id());
                }
                OrchestratorStartView start = orchestratorStarter.prepare(workspace, command.startupCommand(),
                        command.commandPresetId(), command.autostartOrchestrator());
                return new CreateWorkspaceResult(WorkspaceView.from(workspace), start, true);
            });
        } catch (RuntimeException preparationFailure) {
            rollbackPreparedRegistration(workspace, preparationFailure);
            throw preparationFailure;
        }
    }

    private void initializeMetadata(WorkspaceRegistration registration) {
        try {
            metadataInitializer.initialize(registration.workspace().path());
        } catch (RuntimeException initializationFailure) {
            if (registration.created()) rollbackRegistration(registration.workspace(), initializationFailure);
            throw initializationFailure;
        }
    }

    private void rollbackRegistration(Workspace workspace, RuntimeException initializationFailure) {
        try {
            if (!repository.delete(workspace.id().toString())) {
                initializationFailure.addSuppressed(new IllegalStateException(
                        "Workspace registration disappeared before initialization rollback: " + workspace.id()));
            }
        } catch (RuntimeException rollbackFailure) {
            initializationFailure.addSuppressed(rollbackFailure);
        }
    }

    private void rollbackPreparedRegistration(Workspace workspace, RuntimeException preparationFailure) {
        try {
            operations.exclusivelyWithWorkspace(workspace.id().toString(), () -> {
                if (!repository.delete(workspace.id().toString())) {
                    preparationFailure.addSuppressed(new IllegalStateException(
                            "Workspace registration disappeared before preparation rollback: " + workspace.id()));
                    return;
                }
                try {
                    runtimeCleaner.stopAndForget(workspace.id().toString());
                } catch (RuntimeException cleanupFailure) {
                    preparationFailure.addSuppressed(cleanupFailure);
                }
            });
        } catch (RuntimeException rollbackFailure) {
            if (rollbackFailure != preparationFailure) preparationFailure.addSuppressed(rollbackFailure);
        }
    }

    @Override public List<WorkspaceView> list() {
        return repository.findAll().stream().map(WorkspaceView::from).toList();
    }
    @Override public void delete(String workspaceId){operations.deletingWorkspace(workspaceId,()->{if(!repository.delete(workspaceId))throw new IllegalArgumentException("Workspace not found: "+workspaceId);runtimeCleaner.stopAndForget(workspaceId);});}

    private static String defaultName(WorkspacePath workspacePath) {
        Path selectedPath = Path.of(workspacePath.value());
        Path fileName = selectedPath.getFileName();
        return fileName == null ? workspacePath.value() : fileName.toString();
    }

    private void releasePathCreation(String path, PathCreationLock retained) {
        pathCreations.compute(path, (ignored, current) -> {
            if (current != retained) throw new IllegalStateException(
                    "Workspace creation lock identity changed unexpectedly");
            return --current.references == 0 ? null : current;
        });
    }

    private static final class PathCreationLock {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;
    }
}
