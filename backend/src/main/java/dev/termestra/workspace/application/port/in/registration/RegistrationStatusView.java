package dev.termestra.workspace.application.port.in.registration;

public record RegistrationStatusView(
        String registrationId,
        String status,
        String workspaceId,
        String errorCode,
        Boolean sourceRevisionChanged,
        RegistrationOptionsView.HeadView observedHead) { }
