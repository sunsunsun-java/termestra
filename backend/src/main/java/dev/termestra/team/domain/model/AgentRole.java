package dev.termestra.team.domain.model;

public enum AgentRole {
    ORCHESTRATOR("orchestrator"), CODER("coder"), REVIEWER("reviewer"), TESTER("tester"), CUSTOM("custom");
    private final String wireValue;
    AgentRole(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
    public static AgentRole parse(String value) {
        for (AgentRole role : values()) if (role.wireValue.equals(value)) return role;
        throw new IllegalArgumentException("Unsupported worker role: " + value);
    }
    public boolean isWorker() { return this != ORCHESTRATOR; }
}
