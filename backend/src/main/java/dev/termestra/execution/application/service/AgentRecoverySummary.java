package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider.*;

import java.util.*;

final class AgentRecoverySummary {
    private static final int TASKS_HEAD_LIMIT=1536;
    private AgentRecoverySummary() { }

    static String build(AgentDescriptor agent, RecoveryContext context) {
        RecoveryWorker current=context.workers().stream().filter(worker->worker.id().equals(agent.agentId())).findFirst().orElse(new RecoveryWorker(agent.agentId(),agent.name(),agent.role(),0));
        List<RecoveryWorker> workers=context.workers().stream().filter(worker->!worker.id().equals(agent.agentId())).toList();
        List<String> lines=new ArrayList<>(List.of(
                "你是 "+agent.workspaceName()+" 的 "+agent.name()+"（"+agent.role()+"）。",
                "你刚被 Termestra 重启了，且无法通过原生 session resume 恢复。下面是接力上下文。", "",
                "## 最近 1 小时与 user 的对话"));
        addUserInputs(lines,context.recentMessages());
        lines.add("");lines.add("orchestrator".equals(agent.role())?"## 你已派出的任务":"## 最近派给你的任务");
        addTaskEvents(lines,context.recentMessages(),agent);
        lines.add("");lines.add("## 当前未完成任务");addOpenTasks(lines,context.allTaskMessages(),agent,current,workers);
        lines.add("");lines.add("## 当前 .termestra/tasks.md 状态");
        String tasks=Objects.requireNonNullElse(context.tasksContent(),"");lines.add(tasks.isEmpty()?"(空)":tasks.substring(0,Math.min(TASKS_HEAD_LIMIT,tasks.length())));
        lines.add("");lines.add("## 当前团队成员（实时状态请以 `team list` 为准）");
        if(workers.isEmpty())lines.add("- 当前没有其他 worker");else workers.forEach(worker->lines.add("- "+worker.name()+" ("+worker.role()+", pending_task_count: "+worker.pendingTaskCount()+")"));
        lines.add("");lines.add("orchestrator".equals(agent.role())?"## Termestra worker 派单规则":"## Termestra worker 边界");
        if("orchestrator".equals(agent.role()))lines.addAll(List.of("- 先用 `team list` 确认真实 worker。","- 使用 `team send <worker-name> \"<task>\"` 派单；不要调用 CLI 内置 subagent。","- 方向变更时用 `team cancel --dispatch <id> \"<reason>\"` 关闭旧任务。"));
        else lines.addAll(List.of("- 不要调用 team send，也不要启动嵌套 subagent。","- 完成、阻塞或失败时必须用 `team report` 汇报。","- 没有明确任务时用 `team status` 汇报状态。"));
        lines.add("");lines.add("请基于此继续。如果不确定，问 user。");
        return AutomaticPromptLimits.boundedRecovery("[Termestra 系统消息："+String.join("\n",lines)+"]");
    }

    private static void addUserInputs(List<String> lines,List<RecoveryMessage> messages){List<RecoveryMessage> filtered=messages.stream().filter(message->"user_input".equals(message.type())).toList();if(filtered.isEmpty())lines.add("- （最近 1 小时没有新的 user_input）");else tail(filtered,5).forEach(message->lines.add("- user: "+message.text()));}
    private static void addTaskEvents(List<String> lines,List<RecoveryMessage> messages,AgentDescriptor agent){List<RecoveryMessage> filtered=messages.stream().filter(message->taskEvent(message,agent)).toList();if(filtered.isEmpty()){lines.add("- （最近没有任务事件）");return;}tail(filtered,8).forEach(message->{if("send".equals(message.type()))lines.add("- send -> "+message.toAgentId()+": "+message.text());else if("status".equals(message.type()))lines.add("- status <- "+message.fromAgentId()+": "+message.text());else lines.add("- report <- "+message.fromAgentId()+(message.status()==null?"":" ["+message.status()+"]")+": "+message.text());});}
    private static boolean taskEvent(RecoveryMessage message,AgentDescriptor agent){if("orchestrator".equals(agent.role()))return "send".equals(message.type())&&agent.agentId().equals(message.fromAgentId())||Set.of("report","status").contains(message.type());return "send".equals(message.type())&&(agent.agentId().equals(message.toAgentId())||agent.agentId().equals(message.fromAgentId()))||Set.of("report","status").contains(message.type())&&agent.agentId().equals(message.fromAgentId());}
    private static void addOpenTasks(List<String> lines,List<RecoveryMessage> messages,AgentDescriptor agent,RecoveryWorker current,List<RecoveryWorker> workers){List<RecoveryWorker> targets="orchestrator".equals(agent.role())?workers:List.of(current);Map<String,Deque<RecoveryMessage>> queues=new LinkedHashMap<>();targets.forEach(worker->queues.put(worker.id(),new ArrayDeque<>()));for(RecoveryMessage message:messages){if("send".equals(message.type())&&queues.containsKey(message.toAgentId()))queues.get(message.toAgentId()).addLast(message);else if("report".equals(message.type())&&queues.containsKey(message.fromAgentId()))queues.get(message.fromAgentId()).pollFirst();}int before=lines.size();for(RecoveryWorker worker:targets){Deque<RecoveryMessage> queue=queues.get(worker.id());List<RecoveryMessage> open=tail(new ArrayList<>(queue),8);open.forEach(message->lines.add("- "+worker.name()+": "+message.text()));if(worker.pendingTaskCount()>queue.size())lines.add("- "+worker.name()+": "+(worker.pendingTaskCount()-queue.size())+" 个 pending 无可恢复详情");}if(lines.size()==before)lines.add("- （当前没有未完成任务）");}
    private static <T> List<T> tail(List<T> values,int count){return values.subList(Math.max(0,values.size()-count),values.size());}
}
