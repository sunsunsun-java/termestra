package dev.termestra.auth.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UiSessionServiceTest {
    @Test void reusesOneBoundedProcessTokenAndRejectsForeignTokens() {
        UiSessionService sessions = new UiSessionService();

        String first = sessions.issue();
        String second = sessions.issue();

        assertEquals(first, second);
        assertTrue(sessions.isValid(first));
        assertFalse(sessions.isValid(null));
        assertFalse(sessions.isValid("not-the-process-token"));
        assertFalse(new UiSessionService().isValid(first),
                "a runtime restart must rotate the process-scoped token");
    }
}
