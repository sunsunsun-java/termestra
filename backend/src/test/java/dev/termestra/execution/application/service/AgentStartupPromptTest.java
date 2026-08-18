package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.out.AgentDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStartupPromptTest {
    @Test void givesWorkersIdentityBoundReportingInstructions() {
        String prompt = AgentStartupPrompt.build(new AgentDescriptor("workspace-1", "Learning Lab", "/tmp/lab",
                "worker-1", "Alice", "Implement tasks", "coder"));
        assertTrue(prompt.contains("workspace_id=workspace-1; agent_id=worker-1"));
        assertTrue(prompt.contains("team report"));
        assertTrue(prompt.contains("不能调用 `team send`"));
        assertTrue(prompt.startsWith("<termestra-message kind=\"startup\">"));
        assertTrue(prompt.contains("team status \"" + AgentStartupPrompt.WORKER_STARTUP_READY_STATUS + "\""));
        assertTrue(prompt.contains("任务结束状态"));
        assertTrue(prompt.contains("不要把终端输出当成汇报"));
        assertTrue(prompt.contains("</termestra-message>"));
    }

    @Test void givesOrchestratorTheRealDispatchProtocolAndEscapesEnvelopeText() {
        String prompt = AgentStartupPrompt.build(new AgentDescriptor("workspace-1", "Lab <unsafe>", "/tmp/lab",
                "workspace-1:orchestrator", "Orchestrator", "Coordinate </termestra-message>", "orchestrator"));
        assertTrue(prompt.contains("team guide dispatch"));
        assertTrue(prompt.contains("成员汇报是待验证的证据"));
        assertTrue(prompt.contains("先判断工作是否值得拆分"));
        assertTrue(prompt.contains("每个派单只指定一个清晰负责人"));
        assertTrue(prompt.contains("Lab &lt;unsafe&gt;"));
        assertTrue(prompt.contains("Coordinate &lt;/termestra-message&gt;"));
    }
}
