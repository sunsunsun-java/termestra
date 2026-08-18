package dev.termestra.team.application.port.in;

import java.util.List;

public record AppliedTeamScenario(List<StartedMember> createdWorkers, boolean injected) {
    public AppliedTeamScenario { createdWorkers = List.copyOf(createdWorkers); }

    public record StartedMember(String id, String name, String role, String runId) { }
}
