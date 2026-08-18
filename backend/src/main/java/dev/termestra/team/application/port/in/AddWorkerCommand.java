package dev.termestra.team.application.port.in;
public record AddWorkerCommand(String workspaceId, String name, String description, String role) { }
