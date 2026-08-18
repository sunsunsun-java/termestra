package dev.termestra.team.application.port.in;

import java.util.List;
public record StatusTaskCommand(String workspaceId, String actorId, String token, String result, List<String> artifacts) { }
