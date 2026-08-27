package dev.termestra.team.application.service;

import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.team.application.exception.TeamBadRequest;
import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.port.in.AddWorkerCommand;
import dev.termestra.team.application.port.in.TeamInputLimits;
import dev.termestra.team.application.port.out.TeamMemberRepository;
import dev.termestra.team.domain.model.AgentRole;
import dev.termestra.team.domain.model.TeamMember;

import java.time.Clock;
import java.time.Instant;

/** Internal creation policy shared by plain and launch-provisioned TeamMembers. */
final class TeamMemberCreator {
    private TeamMemberCreator(){ }

    static TeamMember create(TeamMemberRepository members,AddWorkerCommand command,Clock clock){
        if(!members.workspaceExists(command.workspaceId())){
            throw new TeamConflict("Workspace not found: "+command.workspaceId());
        }
        String name=TeamInputLimits.memberName(command.name());
        String description=TeamInputLimits.memberDescription(command.description());
        AgentRole role;
        try{role=AgentRole.parse(command.role()==null?"coder":command.role());}
        catch(IllegalArgumentException error){throw new TeamBadRequest(error.getMessage());}
        if(!role.isWorker())throw new TeamBadRequest("Unsupported worker role: "+command.role());
        if(members.findByName(command.workspaceId(),name).isPresent()){
            throw new TeamConflict("Worker already exists: "+name);
        }
        return TeamMember.create(WorkspaceId.parse(command.workspaceId()),name,description,role,
                Instant.now(clock));
    }
}
