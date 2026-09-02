package dev.termestra.workspace.application.port.in.registration;

import dev.termestra.workspace.application.port.in.CreateWorkspaceResult;

public interface WorkspaceRegistrationUseCase {
    CreateWorkspaceResult register(RegisterWorkspaceCommand command);
    RegistrationStatusView status(String registrationId);
}
