package dev.termestra.execution.application.port.in;

public record AgentRunSummaryView(String runId, String agentId, String agentName, String status,
                                  String terminalInputProfile, String lastPtyLine, Integer exitCode) {
    public static final int MAX_LAST_PTY_LINE_CODE_POINTS = 60;

    public AgentRunSummaryView {
        if (lastPtyLine != null) {
            int length = lastPtyLine.codePointCount(0, lastPtyLine.length());
            if (length > MAX_LAST_PTY_LINE_CODE_POINTS) {
                lastPtyLine = lastPtyLine.substring(
                        0, lastPtyLine.offsetByCodePoints(0, MAX_LAST_PTY_LINE_CODE_POINTS));
            }
        }
    }
}
