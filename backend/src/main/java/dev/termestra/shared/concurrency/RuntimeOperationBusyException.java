package dev.termestra.shared.concurrency;

import java.time.Duration;

/** A runtime resource remained owned for the coordinator's bounded acquisition window. */
public final class RuntimeOperationBusyException extends RuntimeException {
    private final String resourceType;
    private final String workspaceId;
    private final String agentId;
    private final Duration timeout;

    private RuntimeOperationBusyException(String resourceType, String workspaceId,
                                          String agentId, Duration timeout) {
        super("Another runtime operation is still finishing for this " + resourceType + '.');
        this.resourceType = resourceType;
        this.workspaceId = workspaceId;
        this.agentId = agentId;
        this.timeout = timeout;
    }

    static RuntimeOperationBusyException workspace(String workspaceId, Duration timeout) {
        return new RuntimeOperationBusyException("workspace", workspaceId, null, timeout);
    }

    static RuntimeOperationBusyException agent(String workspaceId, String agentId, Duration timeout) {
        return new RuntimeOperationBusyException("agent", workspaceId, agentId, timeout);
    }

    static RuntimeOperationBusyException workspacePath(String canonicalPath, Duration timeout) {
        return new RuntimeOperationBusyException("workspace_path", canonicalPath, null, timeout);
    }

    public String resourceType() { return resourceType; }
    public String workspaceId() { return workspaceId; }
    public String agentId() { return agentId; }
    public Duration timeout() { return timeout; }
}
