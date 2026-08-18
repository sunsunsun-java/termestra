package dev.termestra.team.domain.model;

import dev.termestra.shared.id.AgentId;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.team.domain.exception.InvalidDispatchTransition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T00:00:00Z");

    @Test
    void followsTheSubmittedDeliveredReportedLifecycle() {
        Dispatch dispatch = newDispatch();
        Instant submittedAt = CREATED_AT.plusSeconds(1);
        Instant deliveredAt = CREATED_AT.plusSeconds(2);
        Instant reportedAt = CREATED_AT.plusSeconds(3);

        dispatch.markSubmitted(submittedAt);
        dispatch.markDelivered(deliveredAt);
        dispatch.report("done", List.of("src/App.java"), reportedAt);

        assertEquals(DispatchStatus.REPORTED, dispatch.status());
        assertEquals(submittedAt, dispatch.submittedAt().orElseThrow());
        assertEquals(deliveredAt, dispatch.deliveredAt().orElseThrow());
        assertEquals(reportedAt, dispatch.reportedAt().orElseThrow());
        assertEquals("done", dispatch.reportText().orElseThrow());
        assertEquals(List.of("src/App.java"), dispatch.artifacts());
    }

    @Test
    void acceptsAReportForAQueuedDispatchForProtocolCompatibility() {
        Dispatch dispatch = newDispatch();
        dispatch.report("completed before delivery acknowledgement", List.of(), CREATED_AT);
        assertEquals(DispatchStatus.REPORTED, dispatch.status());
    }

    @Test
    void rejectsASecondTerminalTransition() {
        Dispatch dispatch = newDispatch();
        dispatch.cancel("no longer needed", CREATED_AT);

        InvalidDispatchTransition error = assertThrows(
                InvalidDispatchTransition.class,
                () -> dispatch.report("late report", List.of(), CREATED_AT));

        assertTrue(error.getMessage().contains("cancelled"));
        assertEquals(DispatchStatus.CANCELLED, dispatch.status());
    }

    private static Dispatch newDispatch() {
        return Dispatch.create(
                WorkspaceId.newId(), AgentId.newId(), AgentId.newId(),
                new TaskText("implement compatibility"), CREATED_AT);
    }
}
