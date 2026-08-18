package dev.termestra.workspace.application.port.in;

public interface CreateWorkspaceUseCase {
    CreateWorkspaceResult create(CreateWorkspaceCommand command);
}
