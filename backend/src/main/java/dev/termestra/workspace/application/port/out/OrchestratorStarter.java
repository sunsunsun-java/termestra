package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.domain.model.Workspace;

public interface OrchestratorStarter {
    OrchestratorStartView prepare(Workspace workspace, String startupCommand,
                                  String commandPresetId,String modelId,
                                  Long expectedPresetRevision,boolean autostart);

    /** Completes preparation without replacing an existing launch or starting a duplicate Run. */
    default OrchestratorStartView prepareIfMissing(
            Workspace workspace, String startupCommand, String commandPresetId,
            String modelId, Long expectedPresetRevision, boolean autostart) {
        return OrchestratorStartView.disabled();
    }
}
