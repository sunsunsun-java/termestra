package dev.termestra.team.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentStatusTest {
    @Test void derivesThePublicThreeStateModelFromRuntimeAndPendingWork() {
        assertEquals(AgentStatus.STOPPED, AgentStatus.derive(false, 0));
        assertEquals(AgentStatus.STOPPED, AgentStatus.derive(false, 3));
        assertEquals(AgentStatus.IDLE, AgentStatus.derive(true, 0));
        assertEquals(AgentStatus.WORKING, AgentStatus.derive(true, 1));
        assertThrows(IllegalArgumentException.class, () -> AgentStatus.derive(true, -1));
    }
}
