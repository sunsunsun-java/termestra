package dev.termestra.team.application.port.in;

import java.util.List;
public interface TeamAdminUseCase {
    TeamMemberView addWorker(AddWorkerCommand command);
    List<TeamMemberView> listForUi(String workspaceId);
    TeamMemberView renameWorker(String workspaceId,String workerId,String name);
    void deleteWorker(String workspaceId,String workerId);
}
