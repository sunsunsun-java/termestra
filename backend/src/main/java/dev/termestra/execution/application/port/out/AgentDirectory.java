package dev.termestra.execution.application.port.out;
import java.util.Optional;
public interface AgentDirectory { Optional<AgentDescriptor> find(String workspaceId,String agentId); }
