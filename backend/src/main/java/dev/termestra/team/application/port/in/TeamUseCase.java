package dev.termestra.team.application.port.in;

import java.util.List;
public interface TeamUseCase {
    TeamOperationResult send(SendTaskCommand command);
    TeamOperationResult cancel(CancelTaskCommand command);
    TeamOperationResult report(ReportTaskCommand command);
    TeamOperationResult status(StatusTaskCommand command);
    List<TeamMemberView> listForAgent(String workspaceId, String actorId, String token);
}
