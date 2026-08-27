package dev.termestra.execution.application.port.out;

import dev.termestra.execution.domain.model.*;
import java.time.Instant;
import java.util.Optional;

public interface AgentExecutionRepository {
    boolean saveConfiguration(String workspaceId,String agentId,AgentLaunchConfiguration configuration,Instant at);
    Optional<AgentLaunchConfiguration> copyConfigurationSnapshot(String workspaceId,String sourceAgentId,
                                                                 String targetAgentId,Long expectedSourceRevision,
                                                                 Instant at);
    Optional<AgentLaunchConfiguration> findConfiguration(String workspaceId,String agentId);
    boolean insertRun(String runId,String workspaceId,String agentId,long pid,RunStatus status,Instant startedAt);
    boolean markRunning(String runId,Instant at);
    boolean finishRun(String runId,RunStatus status,Integer exitCode,Instant endedAt,
                      String workspaceId,String agentId,String failedResumeSessionId);
    void markUnfinishedRunsStale(Instant at);
    Optional<String> findLastSession(String workspaceId,String agentId);
    /** Saves a captured session only while the run that discovered it is still active. */
    boolean saveLastSession(String workspaceId,String agentId,String runId,String sessionId,Instant at);
    void clearLastSession(String workspaceId,String agentId);
}
