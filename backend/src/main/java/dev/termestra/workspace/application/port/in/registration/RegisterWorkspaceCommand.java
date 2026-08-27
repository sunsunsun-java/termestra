package dev.termestra.workspace.application.port.in.registration;

import dev.termestra.workspace.application.port.in.WorkspaceInputLimits;

import java.util.UUID;

public record RegisterWorkspaceCommand(
        String registrationId,
        String path,
        String name,
        String startupCommand,
        String commandPresetId,
        boolean autostartOrchestrator,
        RevisionSelection revisionSelection) {

    public RegisterWorkspaceCommand {
        WorkspaceInputLimits.validateName(name);
        WorkspaceInputLimits.validatePath(path);
        registrationId = registrationId == null || registrationId.isBlank()
                ? UUID.randomUUID().toString() : registrationId.trim();
        if (registrationId.length() > 128) {
            throw new IllegalArgumentException("registration_id exceeds 128 characters");
        }
        try {
            registrationId = UUID.fromString(registrationId).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("registration_id must be a UUID", invalid);
        }
        revisionSelection = revisionSelection == null
                ? new RevisionSelection.Current(null) : revisionSelection;
    }
}
