package dev.termestra.execution.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.execution.application.port.in.AgentRunSummaryView;

public record TerminalRunSummaryResponse(@JsonProperty("run_id") String runId,
                                         @JsonProperty("agent_id") String agentId,
                                         @JsonProperty("agent_name") String agentName,
                                         String status,
                                         @JsonProperty("terminal_input_profile") String terminalInputProfile) {
    static TerminalRunSummaryResponse from(AgentRunSummaryView view) {
        return new TerminalRunSummaryResponse(view.runId(), view.agentId(), view.agentName(), view.status(),
                view.terminalInputProfile());
    }
}
