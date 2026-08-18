package dev.termestra.workspace.application.port.in;

public record CreateWorkspaceResult(WorkspaceView workspace, OrchestratorStartView orchestratorStart,
                                    boolean created) { }
