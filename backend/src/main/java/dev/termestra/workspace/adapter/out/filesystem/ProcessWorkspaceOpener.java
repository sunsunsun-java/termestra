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
        Platform platform = platform();
        String effective = supported(targetId, platform) ? targetId : defaultTarget(platform);
        if (path.isEmpty() || path.indexOf('\0') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            return new OpenResult(false, effective, "invalid-path");
        }
        List<String> command = command(effective, path, platform);
        try {
            BoundedProcessRunner.Result result = PROCESSES.run(command, OPEN_TIMEOUT, MAX_OUTPUT_BYTES);
            if (result.timedOut()) return new OpenResult(false, effective, "unknown");
            String output = result.output();
            int status = result.exitCode();
            if ((platform == Platform.WINDOWS && command.getFirst().equals("explorer")) || status == 0) {
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

    private List<String> command(String target, String path, Platform platform) {
        if (platform == Platform.MAC) return switch (target) {
            case "finder" -> List.of("open", path);
            case "vscode" -> List.of("open", "-a", "Visual Studio Code", path);
            case "intellij-idea" -> List.of("open", "-a", "IntelliJ IDEA", path);
            case "cursor" -> List.of("open", "-a", "Cursor", path);
            case "terminal" -> List.of("open", "-a", "Terminal", path);
            case "ghostty" -> List.of("open", "-a", "Ghostty", path);
            case "zed" -> List.of("open", "-a", "Zed", path);
            default -> List.of("open", path);
        };
        if (platform == Platform.WINDOWS) return switch (target) {
            case "vscode" -> List.of("code", path);
            case "intellij-idea" -> List.of("idea64.exe", path);
            case "cursor" -> List.of("cursor", path);
            case "zed" -> List.of("zed", path);
            default -> List.of("explorer", path);
        };
        if (platform == Platform.LINUX) return switch (target) {
            case "vscode" -> List.of("code", path);
            case "intellij-idea" -> List.of("idea", path);
            case "cursor" -> List.of("cursor", path);
            case "zed" -> List.of("zed", path);
            default -> List.of("xdg-open", path);
        };
        return List.of("open", path);
    }

    private boolean supported(String target, Platform platform) {
        return switch (platform) {
            case MAC -> true;
            case WINDOWS, LINUX -> Set.of("vscode", "intellij-idea", "cursor", "finder", "zed").contains(target);
            case OTHER -> Set.of("vscode", "intellij-idea", "finder").contains(target);
        };
    }
    private String defaultTarget(Platform platform) { return platform == Platform.OTHER ? "vscode" : "finder"; }
    private Platform platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return Platform.MAC;
        if (os.contains("win")) return Platform.WINDOWS;
        if (os.contains("linux")) return Platform.LINUX;
        return Platform.OTHER;
    }
    private enum Platform { MAC, WINDOWS, LINUX, OTHER }
}
