package dev.termestra.execution.application.port.in;

public record AgentRunView(String runId,String agentId,String agentName,String workspaceId,Long pid,String status,
                           String output,Integer exitCode,long startedAt,Long endedAt,String terminalInputProfile) { }
