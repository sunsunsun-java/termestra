package dev.termestra.team.application.port.in;

public record ApplyTeamScenarioCommand(String workspaceId, String scenarioId, String goal,
                                       String locale, String runtimePort) {
    public ApplyTeamScenarioCommand {
        TeamInputLimits.goal(goal);
    }
}
