package dev.termestra.workspace.application.port.in;

public record OpenWorkspaceView(boolean ok, String effectiveTargetId, String errorCode) { }
