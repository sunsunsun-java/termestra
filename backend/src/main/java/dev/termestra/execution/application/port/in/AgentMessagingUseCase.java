package dev.termestra.execution.application.port.in;

import java.util.List;
public interface AgentMessagingUseCase {
    MessageDeliveryResult userInput(String workspaceId,String text);
    MessageDeliveryResult deliver(String workspaceId,String workerId,String dispatchId,String senderName,String workerDescription,String text,String runtimePort);
    MessageDeliveryResult report(String workspaceId,String workerName,String text,List<String> artifacts);
    MessageDeliveryResult status(String workspaceId,String workerName,String text,List<String> artifacts);
    MessageDeliveryResult cancel(String workspaceId,String workerId,String dispatchId,String reason);
}
