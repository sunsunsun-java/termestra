package dev.termestra.auth.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCredentialServiceTest {
    @Test
    void anOldRunCannotRevokeTheReplacementToken() {
        AgentCredentialService credentials = new AgentCredentialService();
        String oldToken = credentials.issue("worker-1");
        String replacement = credentials.issue("worker-1");

        credentials.revoke("worker-1", oldToken);

        assertFalse(credentials.validate("worker-1", oldToken));
        assertTrue(credentials.validate("worker-1", replacement));
    }

    @Test
    void revokesTheMatchingToken() {
        AgentCredentialService credentials = new AgentCredentialService();
        String token = credentials.issue("worker-1");

        credentials.revoke("worker-1", token);

        assertFalse(credentials.validate("worker-1", token));
    }

    @Test
    void concurrentRunsKeepIndependentTokensUntilEachOneIsRevoked() {
        AgentCredentialService credentials = new AgentCredentialService();
        String first = credentials.issueConcurrent("workspace-1:shell");
        String second = credentials.issueConcurrent("workspace-1:shell");

        assertTrue(credentials.validate("workspace-1:shell", first));
        assertTrue(credentials.validate("workspace-1:shell", second));

        credentials.revoke("workspace-1:shell", first);

        assertFalse(credentials.validate("workspace-1:shell", first));
        assertTrue(credentials.validate("workspace-1:shell", second));
    }
}
