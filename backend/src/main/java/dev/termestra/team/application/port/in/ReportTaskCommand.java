package dev.termestra.team.application.port.in;

import java.util.List;
public record ReportTaskCommand(String workspaceId, String actorId, String token, String dispatchId,
                                String result, String status, List<String> artifacts) { }
