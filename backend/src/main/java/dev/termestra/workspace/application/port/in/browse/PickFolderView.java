package dev.termestra.workspace.application.port.in.browse;

public record PickFolderView(boolean canceled, String error, String path,
                             ProbeView probe, boolean supported) {
    public static PickFolderView unsupported(String error) {
        return new PickFolderView(false, error, null, null, false);
    }
}
