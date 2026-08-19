package dev.termestra.execution.adapter.in.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceShellControllerTest {
    @Test
    void usesComSpecForTheInteractiveWindowsWorkspaceShell() {
        var shell = WorkspaceShellController.defaultShell("Windows 11", Map.of(
                "ComSpec", "C:\\Windows\\System32\\cmd.exe"));

        assertEquals("C:\\Windows\\System32\\cmd.exe", shell.command());
        assertEquals(List.of(), shell.arguments());
    }

    @Test
    void usesALoginFlagForKnownUnixInteractiveShells() {
        var shell = WorkspaceShellController.defaultShell("Mac OS X", Map.of("SHELL", "/bin/zsh"));

        assertEquals("/bin/zsh", shell.command());
        assertEquals(List.of("-l"), shell.arguments());
    }
}
