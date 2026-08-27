package dev.termestra.team.application.port.in;

public record CreateWorkerCommand(String workspaceId,String name,String description,String role,
                                  WorkerLaunchIntent launch,boolean autostart,String runtimePort) { }
