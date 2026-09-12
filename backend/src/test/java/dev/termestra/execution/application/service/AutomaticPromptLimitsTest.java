package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider.RecoveryContext;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider.RecoveryMessage;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider.RecoveryWorker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutomaticPromptLimitsTest {
    @Test
    void recoveryAndStartupPromptsHaveOneFinalCharacterBudget() {
        AgentDescriptor descriptor = new AgentDescriptor(
                "workspace", "w".repeat(256), "/" + "p".repeat(4_095), "workspace:orchestrator",
                "Orchestrator", "d".repeat(4_096), "orchestrator");
        List<RecoveryWorker> workers = new ArrayList<>();
        List<RecoveryMessage> messages = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            String id = "worker-" + index;
            workers.add(new RecoveryWorker(id, "n".repeat(128), "r".repeat(64), 1));
            messages.add(new RecoveryMessage("send", "workspace:orchestrator", id,
                    "t".repeat(4_096), null));
        }
        RecoveryContext context = new RecoveryContext("x".repeat(1_536), messages, messages, workers);

        assertTrue(AgentStartupPrompt.build(descriptor).length() <= AutomaticPromptLimits.MAX_AUTOMATIC_PROMPT_CHARACTERS);
        String recovery=AgentRecoverySummary.build(descriptor, context);
        assertTrue(recovery.length() <= AutomaticPromptLimits.MAX_AUTOMATIC_PROMPT_CHARACTERS);
        assertTrue(recovery.contains("请基于此继续。如果不确定，问 user。"));
    }
    @Test
    void acceptsMaximumRoleBodyWithWorstCaseXmlEscapingAndPreservesTheLastInstruction() {
        String description = "&".repeat(65_532) + "TAIL";
        AgentDescriptor descriptor = new AgentDescriptor("w".repeat(256), "&".repeat(256),
                "/" + "&".repeat(4_095), "a".repeat(256), "&".repeat(128), description, "custom");
        String prompt = AgentStartupPrompt.build(descriptor);
        assertTrue(prompt.contains("&amp;".repeat(65_532) + "TAIL"));
        assertTrue(prompt.length() <= AutomaticPromptLimits.MAX_AUTOMATIC_PROMPT_CHARACTERS);
    }

    @Test
    void acceptsMaximumRoleAndTaskTogetherWithoutDroppingEitherTail() {
        String role = "角".repeat(65_532) + "ROLE";
        String task = "任".repeat(65_532) + "TASK";
        String prompt = AgentPromptBuilder.dispatch("s".repeat(128), role, "d".repeat(256), task);
        assertTrue(prompt.contains(role));
        assertTrue(prompt.contains(task));
        assertTrue(prompt.length() <= AutomaticPromptLimits.MAX_AUTOMATIC_PROMPT_CHARACTERS);
        assertThrows(dev.termestra.execution.application.exception.ExecutionConflict.class,
                () -> AutomaticPromptLimits.requireWithinLimit(
                        "x".repeat(AutomaticPromptLimits.MAX_AUTOMATIC_PROMPT_CHARACTERS + 1)));
    }
}
