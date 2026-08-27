package dev.termestra.workspace.application.service;

import dev.termestra.workspace.application.port.in.*;
import dev.termestra.workspace.application.port.out.*;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;

import java.util.List;

public final class WorkspaceApplicationService implements ListWorkspacesQuery, DeleteWorkspaceUseCase {
    private final WorkspaceRepository repository;
    private final WorkspaceRuntimeCleaner runtimeCleaner;
    private final RuntimeOperationCoordinator operations;

    public WorkspaceApplicationService(WorkspaceRepository repository,
                                       WorkspaceRuntimeCleaner runtimeCleaner,
                                       RuntimeOperationCoordinator operations) {
        this.repository = repository;
        this.runtimeCleaner = runtimeCleaner;
        this.operations = operations;
    }

    @Override public List<WorkspaceView> list() {
        return repository.findAll().stream().map(WorkspaceView::from).toList();
    }
    @Override public void delete(String workspaceId){operations.deletingWorkspace(workspaceId,()->{if(!repository.delete(workspaceId))throw new IllegalArgumentException("Workspace not found: "+workspaceId);runtimeCleaner.stopAndForget(workspaceId);});}

}
