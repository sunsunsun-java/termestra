package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRecoverySummaryTest {
    @Test void rebuildsRecentConversationOpenTasksTasksFileAndWorkers() {
        AgentDescriptor orchestrator=new AgentDescriptor("workspace-1","Learning Lab","/tmp/lab",
                "workspace-1:orchestrator","Orchestrator","Coordinate","orchestrator");
        RecoveryMessage user=new RecoveryMessage("user_input",null,"workspace-1:orchestrator","继续修复恢复链路",null);
        RecoveryMessage send=new RecoveryMessage("send","workspace-1:orchestrator","worker-1","审查 session fallback",null);
        RecoveryWorker worker=new RecoveryWorker("worker-1","Alice","reviewer",1);

        String summary=AgentRecoverySummary.build(orchestrator,new RecoveryContext("- [ ] Layer B",List.of(user,send),List.of(send),List.of(worker)));

        assertAll(
                ()->assertTrue(summary.startsWith("[Termestra 系统消息：")),
                ()->assertTrue(summary.contains("无法通过原生 session resume 恢复")),
                ()->assertTrue(summary.contains("继续修复恢复链路")),
                ()->assertTrue(summary.contains("Alice: 审查 session fallback")),
                ()->assertTrue(summary.contains("- [ ] Layer B")),
                ()->assertTrue(summary.contains("实时状态请以 `team list` 为准")),
                ()->assertTrue(summary.contains("Alice (reviewer, pending_task_count: 1)")));
    }

    @Test void workerOnlyRecoversItsOwnTaskQueue() {
        AgentDescriptor alice=new AgentDescriptor("workspace-1","Learning Lab","/tmp/lab","worker-1","Alice","Review","reviewer");
        RecoveryMessage aliceTask=new RecoveryMessage("send","workspace-1:orchestrator","worker-1","Review API",null);
        RecoveryMessage bobTask=new RecoveryMessage("send","workspace-1:orchestrator","worker-2","Build UI",null);
        RecoveryWorker aliceState=new RecoveryWorker("worker-1","Alice","reviewer",1);
        RecoveryWorker bobState=new RecoveryWorker("worker-2","Bob","coder",1);

        String summary=AgentRecoverySummary.build(alice,new RecoveryContext("",List.of(aliceTask,bobTask),List.of(aliceTask,bobTask),List.of(aliceState,bobState)));

        assertTrue(summary.contains("Alice: Review API"));
        assertFalse(summary.contains("Bob: Build UI"));
    }
}
