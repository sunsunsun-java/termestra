package dev.termestra.workspace.application.service.browse;

import dev.termestra.workspace.application.port.in.browse.*;
import dev.termestra.workspace.application.port.out.browse.NativeFolderPicker;
import dev.termestra.workspace.application.port.out.browse.SelectedDirectoryProbe;

public final class FilesystemPickerService implements FilesystemPickerUseCase {
    private final NativeFolderPicker picker;
    private final SelectedDirectoryProbe selectedDirectoryProbe;

    public FilesystemPickerService(NativeFolderPicker picker, SelectedDirectoryProbe selectedDirectoryProbe) {
        this.picker = picker;
        this.selectedDirectoryProbe = selectedDirectoryProbe;
    }

    @Override public PickFolderView pick() {
        NativeFolderPicker.NativePickResult selected = picker.pick();
        if (selected.path() == null) {
            return new PickFolderView(selected.canceled(), selected.error(), null, null, selected.supported());
        }
        ProbeView probe = selectedDirectoryProbe.probe(selected.path());
        if (!probe.ok() || !probe.directory()) {
            return new PickFolderView(false,
                    "Selected path does not exist, is inaccessible, or is not a directory.",
                    selected.path(), probe, true);
        }
        return new PickFolderView(false, null, probe.path(), probe, true);
    }
}
