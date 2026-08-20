package dev.termestra.execution.adapter.in.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceShellControllerTest {
    @Test
    void usesALoginFlagForKnownMacInteractiveShells() {
        var shell = WorkspaceShellController.defaultShell(Map.of("SHELL", "/bin/zsh"));

        assertEquals("/bin/zsh", shell.command());
        assertEquals(List.of("-l"), shell.arguments());
    }
}
