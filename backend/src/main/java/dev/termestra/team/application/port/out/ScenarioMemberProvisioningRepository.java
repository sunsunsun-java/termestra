package dev.termestra.team.application.port.out;

import dev.termestra.team.domain.model.TeamMember;

/** Persists one scenario member and its fresh launch configuration atomically. */
@FunctionalInterface
public interface ScenarioMemberProvisioningRepository {
    void saveWithLaunch(TeamMember member, WorkerLaunchPlan launchPlan);
}
