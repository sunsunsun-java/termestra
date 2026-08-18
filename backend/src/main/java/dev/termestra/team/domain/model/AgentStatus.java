package dev.termestra.team.domain.model;

public enum AgentStatus {
    IDLE("idle"), WORKING("working"), STOPPED("stopped");
    private final String wireValue;
    AgentStatus(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }

    public static AgentStatus derive(boolean runtimeActive, int pendingTaskCount) {
        if (pendingTaskCount < 0) throw new IllegalArgumentException("pending task count must not be negative");
        if (!runtimeActive) return STOPPED;
        return pendingTaskCount == 0 ? IDLE : WORKING;
    }
}
