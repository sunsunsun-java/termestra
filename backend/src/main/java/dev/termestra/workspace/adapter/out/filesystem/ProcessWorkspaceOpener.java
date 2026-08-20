package dev.termestra.workspace.adapter.out.filesystem;

import dev.termestra.platform.process.BoundedProcessRunner;
import dev.termestra.workspace.application.port.out.WorkspaceOpener;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

public final class ProcessWorkspaceOpener implements WorkspaceOpener {
    private static final BoundedProcessRunner PROCESSES = new BoundedProcessRunner();
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_OUTPUT_BYTES = 16 * 1_024;

    @Override public OpenResult open(String path, String targetId) {
        String effective = supported(targetId) ? targetId : "finder";
        if (path.isEmpty() || path.indexOf('\0') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            return new OpenResult(false, effective, "invalid-path");
        }
        List<String> command = command(effective, path);
        try {
            BoundedProcessRunner.Result result = PROCESSES.run(command, OPEN_TIMEOUT, MAX_OUTPUT_BYTES);
            if (result.timedOut()) return new OpenResult(false, effective, "unknown");
            String output = result.output();
            int status = result.exitCode();
            if (status == 0) {
                return new OpenResult(true, effective, null);
            }
            String lower = output.toLowerCase(Locale.ROOT);
            String code = lower.contains("unable to find application") || lower.contains("can't find")
                    || lower.contains("application can’t be found") ? "app-not-installed" : "unknown";
            return new OpenResult(false, effective, code);
        } catch (IOException error) {
            return new OpenResult(false, effective, "command-not-in-path");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new OpenResult(false, effective, "unknown");
        }
    }

    private List<String> command(String target, String path) {
        return switch (target) {
            case "finder" -> List.of("open", path);
            case "vscode" -> List.of("open", "-a", "Visual Studio Code", path);
            case "intellij-idea" -> List.of("open", "-a", "IntelliJ IDEA", path);
            case "cursor" -> List.of("open", "-a", "Cursor", path);
            case "terminal" -> List.of("open", "-a", "Terminal", path);
            case "ghostty" -> List.of("open", "-a", "Ghostty", path);
            case "zed" -> List.of("open", "-a", "Zed", path);
            default -> List.of("open", path);
        };
    }

    private boolean supported(String target) {
        return Set.of("vscode", "intellij-idea", "cursor", "finder", "terminal", "ghostty", "zed")
                .contains(target);
    }
}
