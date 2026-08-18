package dev.termestra.workspace.application.port.in;

public interface OpenWorkspaceUseCase {
    boolean supports(String targetId);
    OpenWorkspaceView open(String workspaceId, String targetId);
}
