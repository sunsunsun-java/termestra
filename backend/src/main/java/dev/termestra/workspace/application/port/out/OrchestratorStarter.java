package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.domain.model.Workspace;

public interface OrchestratorStarter {
    OrchestratorStartView prepare(Workspace workspace, String startupCommand,
                                  String commandPresetId, boolean autostart);
}
