package dev.termestra.workspace.application.port.in.browse;

public record PickFolderView(boolean canceled, String error, String path,
                             ProbeView probe, boolean supported) { }
