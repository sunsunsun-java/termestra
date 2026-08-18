package dev.termestra.workspace.application.port.in;

public record OrchestratorStartView(boolean ok, String error, String runId) {
    public static OrchestratorStartView disabled() { return new OrchestratorStartView(false, null, null); }
}
