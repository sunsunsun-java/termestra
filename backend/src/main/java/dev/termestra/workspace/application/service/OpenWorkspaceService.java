package dev.termestra.workspace.application.service;

import dev.termestra.workspace.application.exception.WorkspaceNotFound;
import dev.termestra.workspace.application.port.in.*;
import dev.termestra.workspace.application.port.out.*;

import java.util.Set;

public final class OpenWorkspaceService implements OpenWorkspaceUseCase {
    private static final Set<String> TARGETS = Set.of(
            "vscode", "intellij-idea", "cursor", "finder", "terminal", "ghostty", "zed");
    private final WorkspaceRepository workspaces;
    private final WorkspaceOpener opener;

    public OpenWorkspaceService(WorkspaceRepository workspaces, WorkspaceOpener opener) {
        this.workspaces = workspaces;
        this.opener = opener;
    }

    @Override public boolean supports(String targetId) { return TARGETS.contains(targetId); }

    @Override public OpenWorkspaceView open(String workspaceId, String targetId) {
        if (!supports(targetId)) throw new IllegalArgumentException("Unknown open target");
        var workspace = workspaces.find(workspaceId).orElseThrow(() -> new WorkspaceNotFound(workspaceId));
        WorkspaceOpener.OpenResult result = opener.open(workspace.path().value(), targetId);
        return new OpenWorkspaceView(result.ok(), result.effectiveTargetId(), result.errorCode());
    }
}
