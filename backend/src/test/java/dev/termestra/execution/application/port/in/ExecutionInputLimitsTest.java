package dev.termestra.execution.application.port.in;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionInputLimitsTest {
    @Test void acceptsAndDefensivelyCopiesAReasonableLaunchConfiguration() {
        var command = new ConfigureAgentCommand("workspace", "agent", " codex ",
                List.of("--yolo"), "codex", "codex", false,
                null, null, Map.of("TERM_PROFILE", "safe"));

        assertEquals("codex", command.command());
        assertEquals(List.of("--yolo"), command.arguments());
        assertEquals(Map.of("TERM_PROFILE", "safe"), command.environment());
    }

    @Test void rejectsArgumentAndEnvironmentAmplificationBeforeProcessLaunch() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigureAgentCommand(
                "workspace", "agent", "codex",
                java.util.Collections.nCopies(ExecutionInputLimits.MAX_ARGUMENTS + 1, "x"),
                "codex", "codex", false, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigureAgentCommand(
                "workspace", "agent", "codex", List.of(), "codex", "codex", false,
                null, null, Map.of("KEY", "x".repeat(
                        ExecutionInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS + 1))));
    }

    @Test void rejectsOversizedTerminalUserInput() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionInputLimits.userInput(
                "x".repeat(ExecutionInputLimits.MAX_USER_INPUT_CHARACTERS + 1)));
    }
}
