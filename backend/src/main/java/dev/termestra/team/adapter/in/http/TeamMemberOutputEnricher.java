package dev.termestra.team.adapter.in.http;

import dev.termestra.execution.application.port.in.AgentExecutionUseCase;
import dev.termestra.team.application.port.in.TeamMemberView;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
final class TeamMemberOutputEnricher {
    private final AgentExecutionUseCase execution;

    TeamMemberOutputEnricher(AgentExecutionUseCase execution) {
        this.execution = execution;
    }

    List<TeamMemberResponse> enrich(String workspaceId, List<TeamMemberView> members) {
        Map<String, String> lastLines = new HashMap<>();
        for (var run : execution.listActiveSummaries(workspaceId)) {
            if (run.lastPtyLine() != null) lastLines.put(run.agentId(), run.lastPtyLine());
        }
        return members.stream()
                .map(member -> TeamMemberResponse.from(member, lastLines.get(member.id())))
                .toList();
    }
}
