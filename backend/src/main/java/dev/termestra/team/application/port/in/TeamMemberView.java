package dev.termestra.team.application.port.in;

public record TeamMemberView(String id, String name, String role, String status, int pendingTaskCount,
                             String lastPtyLine, String commandPresetId) { }
