package dev.termestra.execution.adapter.in.http;

import dev.termestra.execution.application.port.in.*;
import dev.termestra.team.application.exception.TeamBadRequest;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

@RestController
public final class AgentExecutionController {
    private final AgentExecutionUseCase execution;
    private final AgentMessagingUseCase messaging;
    public AgentExecutionController(AgentExecutionUseCase execution,AgentMessagingUseCase messaging){this.execution=execution;this.messaging=messaging;}

    @PostMapping("/api/workspaces/{workspaceId}/agents/{agentId}/config") @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> configure(@PathVariable String workspaceId,@PathVariable String agentId,@RequestBody ExecutionRequests.Configure request){return blocking(()->{if(request.command()==null||request.command().isBlank())throw new TeamBadRequest("Missing command");execution.configure(new ConfigureAgentCommand(workspaceId,agentId,request.command(),request.args(),request.commandPresetId(),request.interactiveCommand(),false,null,null,request.env()));return null;});}
    @PostMapping("/api/workspaces/{workspaceId}/agents/{agentId}/start") @ResponseStatus(HttpStatus.CREATED)
    Mono<Map<String,String>> start(@PathVariable String workspaceId,@PathVariable String agentId,@RequestBody(required=false) ExecutionRequests.Start body,ServerHttpRequest request){return blocking(()->{String port=body!=null&&body.runtimePort()!=null?body.runtimePort():Integer.toString(Objects.requireNonNull(request.getLocalAddress()).getPort());AgentRunView run=execution.start(new StartAgentCommand(workspaceId,agentId,port));return Map.of("run_id",run.runId());});}
    @PostMapping("/api/runtime/runs/{runId}/stop") @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Map<String,Boolean>> stop(@PathVariable String runId){return blocking(()->{execution.stop(runId);return Map.of("ok",true);});}
    @GetMapping("/api/runtime/runs/{runId}") Mono<AgentRunResponse> get(@PathVariable String runId){return blocking(()->AgentRunResponse.from(execution.get(runId)));}
    @GetMapping("/api/ui/workspaces/{workspaceId}/runs") Mono<List<TerminalRunSummaryResponse>> list(@PathVariable String workspaceId){return blocking(()->execution.listActiveSummaries(workspaceId).stream().map(TerminalRunSummaryResponse::from).toList());}
    @PostMapping("/api/workspaces/{workspaceId}/user-input") @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Map<String,Boolean>> userInput(@PathVariable String workspaceId,@RequestBody Map<String,Object> body){return blocking(()->{String text=body.get("text") instanceof String value?value:null;if(text==null||text.isBlank())throw new TeamBadRequest("text is required");MessageDeliveryResult result=messaging.userInput(workspaceId,text);if(!result.delivered())throw new dev.termestra.execution.application.exception.ExecutionConflict(result.error());return Map.of("ok",true);});}
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work){return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());}
}
