package dev.termestra.workspace.application.port.out;

public interface WorkspaceOpener {
    OpenResult open(String path, String targetId);

    record OpenResult(boolean ok, String effectiveTargetId, String errorCode) { }
}
