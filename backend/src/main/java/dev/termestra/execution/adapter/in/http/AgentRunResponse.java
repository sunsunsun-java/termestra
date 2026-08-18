package dev.termestra.execution.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.execution.application.port.in.AgentRunView;

public record AgentRunResponse(@JsonProperty("run_id") String runId,@JsonProperty("agent_id") String agentId,
                               @JsonProperty("agent_name") String agentName,String status,String output,
                               @JsonProperty("exit_code") Integer exitCode,Long pid,
                               @JsonProperty("terminal_input_profile") String terminalInputProfile) {
    static AgentRunResponse from(AgentRunView view){return new AgentRunResponse(view.runId(),view.agentId(),view.agentName(),view.status(),view.output(),view.exitCode(),view.pid(),view.terminalInputProfile());}
}
