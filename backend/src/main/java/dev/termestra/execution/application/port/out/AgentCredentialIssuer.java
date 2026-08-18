package dev.termestra.execution.application.port.out;

public interface AgentCredentialIssuer {
    /** Replaces any older credential for an agent that can own only one run. */
    String issue(String agentId);

    /**
     * Adds a run-scoped credential without invalidating another live run for the
     * same logical agent (workspace shells are intentionally multi-instance).
     */
    default String issueConcurrent(String agentId) { return issue(agentId); }

    void revoke(String agentId, String token);
}
