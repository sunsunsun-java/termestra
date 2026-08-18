package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider.RecoveryContext;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider.RecoveryMessage;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider.RecoveryWorker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertTrue(AgentStartupPrompt.build(descriptor).length() <= 131_072);
        String recovery=AgentRecoverySummary.build(descriptor, context);
        assertTrue(recovery.length() <= 131_072);
        assertTrue(recovery.contains("请基于此继续。如果不确定，问 user。"));
    }
}
