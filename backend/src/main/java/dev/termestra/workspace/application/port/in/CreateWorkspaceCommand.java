package dev.termestra.workspace.application.port.in;

public record CreateWorkspaceCommand(String path, String name, String startupCommand,
                                     String commandPresetId, boolean autostartOrchestrator) {
    public CreateWorkspaceCommand {
        WorkspaceInputLimits.validateName(name);
        WorkspaceInputLimits.validatePath(path);
    }
}
