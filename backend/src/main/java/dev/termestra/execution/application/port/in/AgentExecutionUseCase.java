package dev.termestra.execution.application.port.in;

import java.util.List;
public interface AgentExecutionUseCase {
    void configure(ConfigureAgentCommand command);
    AgentRunView configureAndStart(ConfigureAgentCommand configuration, StartAgentCommand start);
    AgentRunView start(StartAgentCommand command);
    void stop(String runId);
    void write(String runId,byte[] input);
    void resize(String runId,int columns,int rows);
    void pauseOutput(String runId);
    void resumeOutput(String runId);
    AgentRunView get(String runId);
    AgentRunSummaryView getSummary(String runId);
    List<AgentRunSummaryView> listActiveSummaries(String workspaceId);
    void forgetWorkspace(String workspaceId);
    void forgetAgent(String workspaceId,String agentId);
    void forgetRun(String runId);
}
