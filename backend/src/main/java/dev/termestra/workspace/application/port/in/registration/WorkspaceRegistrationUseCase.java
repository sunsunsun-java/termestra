package dev.termestra.workspace.application.port.in.registration;

import dev.termestra.workspace.application.port.in.CreateWorkspaceResult;

public interface WorkspaceRegistrationUseCase {
    RegistrationOptionsView inspect(String inspectionToken, String query, int limit, String cursor);
    CreateWorkspaceResult register(RegisterWorkspaceCommand command);
    RegistrationStatusView status(String registrationId);
}
