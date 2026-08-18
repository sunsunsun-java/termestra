package dev.termestra.team.application.port.out;

import dev.termestra.team.domain.model.TeamMember;
import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository {
    int MAX_MEMBERS_PER_WORKSPACE = 256;
    boolean workspaceExists(String workspaceId);
    void save(TeamMember member);
    Optional<TeamMember> findById(String workspaceId, String agentId);
    Optional<TeamMember> findByName(String workspaceId, String name);
    List<TeamMemberSummary> list(String workspaceId);
    boolean rename(String workspaceId,String agentId,String name);
    boolean delete(String workspaceId,String agentId);
}
