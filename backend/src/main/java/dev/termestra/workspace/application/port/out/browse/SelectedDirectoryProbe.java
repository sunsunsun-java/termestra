package dev.termestra.workspace.application.port.out.browse;

import dev.termestra.workspace.application.port.in.browse.ProbeView;

/** Inspects the single directory explicitly authorized by the native OS picker. */
public interface SelectedDirectoryProbe {
    ProbeView probe(String path);
}
