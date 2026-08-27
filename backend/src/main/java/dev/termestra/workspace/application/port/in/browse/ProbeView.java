package dev.termestra.workspace.application.port.in.browse;

public record ProbeView(boolean ok, String path, boolean exists, boolean directory,
                        boolean gitRepository, String currentBranch, String suggestedName,
                        String gitInspectionToken) { }
