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
    private final AtomicBoolean pickerOpen = new AtomicBoolean();

    public ProcessNativeFolderPicker() {
        this(new BoundedProcessRunner()::run);
    }

    ProcessNativeFolderPicker(ProcessRunner processes) {
        this.processes = processes;
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
        return execute(List.of("osascript", "-e",
                "POSIX path of (choose folder with prompt \"Select Termestra workspace\")"));
    }

    private NativePickResult execute(List<String> command) {
        try {
            BoundedProcessRunner.Result result = processes.run(command, PICK_TIMEOUT, MAX_OUTPUT_BYTES);
            if (result.timedOut()) return NativePickResult.failed("Folder picker timed out.");
            if (result.outputTruncated()) return NativePickResult.failed("Folder picker output exceeded the limit.");
            String output = result.output().trim();
            int status = result.exitCode();
            if (status == 0 && !output.isBlank()) {
                String path = output.endsWith("/")
                        ? output.substring(0, output.length() - 1) : output;
                return NativePickResult.selected(path);
            }
            if (status != 0 && isCancel(output)) {
                return NativePickResult.canceledSelection();
            }
            if (output.toLowerCase(Locale.ROOT).contains("-1743")) {
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

    private boolean isCancel(String output) {
        String text = output.toLowerCase(Locale.ROOT);
        return text.contains("-128") || text.contains("user canceled");
    }

    @FunctionalInterface
    interface ProcessRunner {
        BoundedProcessRunner.Result run(List<String> command, Duration timeout, int maxOutputBytes)
                throws IOException, InterruptedException;
    }
}
