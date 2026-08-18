package dev.termestra.workspace.adapter.out.filesystem.browse;

import dev.termestra.platform.process.BoundedProcessRunner;
import dev.termestra.workspace.application.port.out.browse.NativeFolderPicker;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProcessNativeFolderPicker implements NativeFolderPicker {
    private static final Duration PICK_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_OUTPUT_BYTES = 16 * 1_024;
    private final ProcessRunner processes;
    private final String operatingSystem;
    private final AtomicBoolean pickerOpen = new AtomicBoolean();

    public ProcessNativeFolderPicker() {
        this(new BoundedProcessRunner()::run, System.getProperty("os.name", ""));
    }

    ProcessNativeFolderPicker(ProcessRunner processes, String operatingSystem) {
        this.processes = processes;
        this.operatingSystem = operatingSystem;
    }

    @Override public NativePickResult pick() {
        if (!pickerOpen.compareAndSet(false, true)) {
            return NativePickResult.failed("A folder picker is already open.");
        }
        try {
            return pickOnce();
        } finally {
            pickerOpen.set(false);
        }
    }

    private NativePickResult pickOnce() {
        String os = operatingSystem.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return execute(List.of("osascript", "-e",
                "POSIX path of (choose folder with prompt \"Select Termestra workspace\")"), Platform.MAC);
        if (os.contains("win")) return execute(List.of("powershell.exe", "-NoProfile", "-STA",
                "-ExecutionPolicy", "Bypass", "-Command", windowsScript()), Platform.WINDOWS);
        if (os.contains("linux")) return execute(List.of("zenity", "--file-selection", "--directory",
                "--title=Select Termestra workspace"), Platform.LINUX);
        return NativePickResult.unsupported(
                "Native folder picker not supported on this platform. Use Advanced: paste path.");
    }

    private NativePickResult execute(List<String> command, Platform platform) {
        try {
            BoundedProcessRunner.Result result = processes.run(command, PICK_TIMEOUT, MAX_OUTPUT_BYTES);
            if (result.timedOut()) return NativePickResult.failed("Folder picker timed out.");
            if (result.outputTruncated()) return NativePickResult.failed("Folder picker output exceeded the limit.");
            String output = result.output().trim();
            int status = result.exitCode();
            if (status == 0 && !output.isBlank()) {
                String path = platform == Platform.MAC && output.endsWith("/")
                        ? output.substring(0, output.length() - 1) : output;
                return NativePickResult.selected(path);
            }
            if (status != 0 && isCancel(platform, output, status)) {
                return NativePickResult.canceledSelection();
            }
            if (platform == Platform.MAC && output.toLowerCase(Locale.ROOT).contains("-1743")) {
                return NativePickResult.failed("macOS denied folder-picker automation (error -1743). "
                        + "Allow Termestra or its launcher in System Settings > Privacy & Security > "
                        + "Automation, then retry.");
            }
            String detail = output.isBlank() ? "exit code " + status : output;
            return NativePickResult.failed("Folder picker failed: " + detail);
        } catch (IOException error) {
            return NativePickResult.unsupported(command.getFirst() + " is unavailable on this host.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return NativePickResult.failed("Folder picker was interrupted.");
        }
    }

    private boolean isCancel(Platform platform, String output, int status) {
        String text = output.toLowerCase(Locale.ROOT);
        if (platform == Platform.LINUX) return status == 1;
        if (platform == Platform.MAC) return text.contains("-128") || text.contains("user canceled");
        return platform == Platform.WINDOWS && output.isBlank();
    }

    private String windowsScript() {
        return String.join("; ", "Add-Type -AssemblyName System.Windows.Forms",
                "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog",
                "$dialog.Description = 'Select Termestra workspace'", "$dialog.ShowNewFolderButton = $false",
                "$result = $dialog.ShowDialog()",
                "if ($result -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.WriteLine($dialog.SelectedPath); exit 0 }",
                "exit 1");
    }

    @FunctionalInterface
    interface ProcessRunner {
        BoundedProcessRunner.Result run(List<String> command, Duration timeout, int maxOutputBytes)
                throws IOException, InterruptedException;
    }

    private enum Platform { MAC, WINDOWS, LINUX }
}
