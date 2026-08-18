package dev.termestra.workspace.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.workspace.application.port.in.*;

public record WorkspaceResponse(String id, String name, String path,
                                @JsonProperty("orchestrator_start") OrchestratorStartResponse orchestratorStart) {
    static WorkspaceResponse from(CreateWorkspaceResult result) {
        WorkspaceView workspace = result.workspace();
        return new WorkspaceResponse(workspace.id(), WorkspaceInputLimits.boundedName(workspace.name()),
                workspace.path(), OrchestratorStartResponse.from(result.orchestratorStart()));
    }

    public record OrchestratorStartResponse(boolean ok, String error, @JsonProperty("run_id") String runId) {
        static OrchestratorStartResponse from(OrchestratorStartView view) { return new OrchestratorStartResponse(view.ok(), view.error(), view.runId()); }
    }
}
