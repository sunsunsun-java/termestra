package dev.termestra.execution.application.service;

import java.util.*;

final class AgentPromptBuilder {
    private static final String ORCHESTRATOR_REMINDER = "<termestra-system-reminder>\n"
            + "You are the Termestra Orchestrator. Reply by either: (a) `team send \"<worker-name>\" \"<task>\"` to dispatch follow-up work, (b) `team cancel --dispatch <id> \"<reason>\"` to cancel obsolete work, or (c) plain text to the user. Never call built-in Task / Explore subagents because they bypass Termestra.\n"
            + "</termestra-system-reminder>";
    private AgentPromptBuilder(){ }
    static String dispatch(String sender,String description,String id,String text){return bounded(String.join("\n",
            "[Termestra 系统消息：来自 @"+sender+" 的派单]","","你的角色："+description,"","你必须遵守：",
            "- 完成、失败、阻塞或部分完成后，执行 `team report \"<result>\" --dispatch "+id+"`",
            "- 不要做无关的事，做完就 report","","dispatch_id: "+id,"","任务内容：",text,"",
            "<termestra-system-reminder>","You are a Termestra Worker. Do not launch nested Task / Explore subagents. Finish the task yourself and report with `team report \"<result>\" --dispatch "+id+"` or `team report --stdin --dispatch "+id+"` for long bodies.","</termestra-system-reminder>",""));}
    static String report(String worker,String text,List<String> artifacts){return orchestrator("汇报",worker,text,artifacts);}
    static String status(String worker,String text,List<String> artifacts){return orchestrator("状态更新",worker,text,artifacts);}
    private static String orchestrator(String type,String worker,String text,List<String> artifacts){List<String> lines=new ArrayList<>();lines.add("[Termestra 系统消息：来自 @"+worker+" 的"+type+"]");lines.add(text);for(String artifact:artifacts)lines.add("artifact: "+artifact);lines.add("");lines.add(ORCHESTRATOR_REMINDER);lines.add("");return bounded(String.join("\n",lines));}
    static String userInput(String text){return bounded(String.join("\n",text,"",ORCHESTRATOR_REMINDER,""));}
    static String cancel(String id,String reason){return bounded(String.join("\n","[Termestra 系统消息：dispatch "+id+" 已取消]","","请停止执行这条派单，不要再为它调用 team report。","","取消原因：",reason,""));}
    private static String bounded(String prompt){return AutomaticPromptLimits.requireWithinLimit(prompt);}
}
