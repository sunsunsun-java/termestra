package dev.termestra.team.application.port.in;
public record CancelTaskCommand(String workspaceId, String actorId, String token, String dispatchId, String reason) { }
