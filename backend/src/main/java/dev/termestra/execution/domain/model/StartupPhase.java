package dev.termestra.execution.domain.model;

/** Startup readiness is separate from whether the supervised process is alive. */
public enum StartupPhase {
    INITIALIZING("initializing"), WAITING_FOR_USER("waiting_for_user"), READY("ready"), FAILED("failed");

    private final String wireValue;
    StartupPhase(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
}
