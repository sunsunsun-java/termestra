package dev.termestra.team.application.port.out;

import dev.termestra.team.domain.model.Dispatch;
import dev.termestra.team.domain.model.TeamMember;
import java.util.List;

public interface AgentTeamNotifier {
    DeliveryResult deliver(Dispatch dispatch, TeamMember worker, String runtimePort);
    DeliveryResult report(Dispatch dispatch, TeamMember worker);
    DeliveryResult status(String workspaceId, TeamMember worker, String text, List<String> artifacts);
    DeliveryResult cancel(Dispatch dispatch, TeamMember worker);
}
