package dev.termestra.team.application.port.out;

import dev.termestra.team.domain.model.TeamMember;

/** Persists one TeamMember and its launch configuration in one transaction. */
@FunctionalInterface
public interface MemberProvisioningRepository {
    void saveWithLaunch(TeamMember member, WorkerLaunchProvisioning launch);
}
