package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.in.AgentRunSummaryView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PtyLastLineTrackerTest {
    @Test void tracksOverwrittenAndAnsiStyledLinesWithoutRetainingHistory() {
        PtyLastLineTracker tracker = new PtyLastLineTracker();

        tracker.write("old line\nprogress 10%\rprogress 90%\n\u001b[31mready\u001b[0m");

        assertEquals("ready", tracker.lastLine());
    }

    @Test void summaryBoundaryCapsTheProjectedLineByCodePoint() {
        String line = "好".repeat(AgentRunSummaryView.MAX_LAST_PTY_LINE_CODE_POINTS + 5);

        AgentRunSummaryView summary = new AgentRunSummaryView(
                "run", "agent", "Agent", "running", "default", line, null);

        assertEquals(AgentRunSummaryView.MAX_LAST_PTY_LINE_CODE_POINTS,
                summary.lastPtyLine().codePointCount(0, summary.lastPtyLine().length()));
    }
}
