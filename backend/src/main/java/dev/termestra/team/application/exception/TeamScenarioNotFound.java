package dev.termestra.team.application.exception;

public final class TeamScenarioNotFound extends RuntimeException {
    public TeamScenarioNotFound(String scenarioId) { super("Unknown scenario: " + scenarioId); }
}
