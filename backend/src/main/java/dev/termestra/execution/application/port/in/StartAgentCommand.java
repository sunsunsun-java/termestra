package dev.termestra.execution.application.port.in;
public record StartAgentCommand(String workspaceId,String agentId,String runtimePort) { }
