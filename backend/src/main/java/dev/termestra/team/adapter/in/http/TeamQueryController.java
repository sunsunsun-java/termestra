package dev.termestra.team.adapter.in.http;

import dev.termestra.team.application.exception.TeamBadRequest;
import dev.termestra.execution.application.exception.InvalidLaunchRequest;
import dev.termestra.team.application.port.in.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

@RestController
public final class TeamQueryController {
    private static final Set<String> STATES=Set.of("queued","submitted","reported","cancelled");
    private final TeamUseCase team; private final TeamAdminUseCase admin; private final CreateWorkerUseCase createWorker;private final RemoveWorkerUseCase removeWorker; private final DispatchQuery dispatches;private final TeamMemberOutputEnricher outputEnricher;
    public TeamQueryController(TeamUseCase team,TeamAdminUseCase admin,CreateWorkerUseCase createWorker,RemoveWorkerUseCase removeWorker,DispatchQuery dispatches,TeamMemberOutputEnricher outputEnricher){this.team=team;this.admin=admin;this.createWorker=createWorker;this.removeWorker=removeWorker;this.dispatches=dispatches;this.outputEnricher=outputEnricher;}

    @GetMapping("/api/workspaces/{workspaceId}/team")
    Mono<List<TeamMemberResponse>> listForAgent(@PathVariable String workspaceId,
            @RequestHeader(value="x-termestra-agent-id",required=false) String agentId,
            @RequestHeader(value="x-termestra-agent-token",required=false) String token){
        return blocking(()->outputEnricher.enrich(workspaceId,
                team.listForAgent(workspaceId,agentId,token)));
    }

    @GetMapping("/api/ui/workspaces/{workspaceId}/team")
    Mono<List<TeamMemberResponse>> listForUi(@PathVariable String workspaceId){return blocking(()->outputEnricher.enrich(workspaceId,admin.listForUi(workspaceId)));}

    @PostMapping("/api/workspaces/{workspaceId}/workers") @ResponseStatus(HttpStatus.CREATED)
    Mono<Map<String,Object>> addWorker(@PathVariable String workspaceId,@RequestBody TeamRequests.Worker request,org.springframework.http.server.reactive.ServerHttpRequest serverRequest){return blocking(()->{
        if(request.launch()!=null&&(request.startupCommand()!=null||request.commandPresetId()!=null))throw new InvalidLaunchRequest("LAUNCH_CONTRACT_CONFLICT","launch cannot be combined with legacy fields");
        boolean autostart=Boolean.TRUE.equals(request.autostart());
        String port=autostart?Integer.toString(Objects.requireNonNull(serverRequest.getLocalAddress()).getPort()):null;
        var result=createWorker.create(new CreateWorkerCommand(workspaceId,request.name(),request.description(),
                request.role(),launchIntent(request),autostart,port));
        TeamMemberResponse worker=TeamMemberResponse.from(result.worker());
        Map<String,Object> body=new LinkedHashMap<>();body.put("id",worker.id());body.put("name",worker.name());body.put("role",worker.role());body.put("status",worker.status());
        body.put("pending_task_count",worker.pendingTaskCount());body.put("last_pty_line",worker.lastPtyLine());body.put("command_preset_id",worker.commandPresetId());
        AgentStart start=new AgentStart(result.start().ok(),result.start().error(),result.start().runId());
        body.put("agent_start",start);return body;});}

    private static WorkerLaunchIntent launchIntent(TeamRequests.Worker request){if(request.launch()!=null){var launch=request.launch();launch.validate();if("inherit_orchestrator".equals(launch.type()))return new WorkerLaunchIntent.OrchestratorSnapshot(launch.expectedSourceRevision());if("preset".equals(launch.type()))return new WorkerLaunchIntent.Preset(launch.presetId(),launch.modelId(),launch.expectedPresetRevision());return new WorkerLaunchIntent.Startup(launch.startupCommand(),launch.recoveryPresetId());}if(request.startupCommand()!=null&&!request.startupCommand().isBlank())return new WorkerLaunchIntent.LegacyStartup(request.startupCommand(),request.commandPresetId());if(request.commandPresetId()!=null)return new WorkerLaunchIntent.Preset(request.commandPresetId(),null,null);return null;}
    @PatchMapping("/api/workspaces/{workspaceId}/workers/{workerId}")
    Mono<TeamMemberResponse> renameWorker(@PathVariable String workspaceId,@PathVariable String workerId,@RequestBody Map<String,Object> body){return blocking(()->TeamMemberResponse.from(admin.renameWorker(workspaceId,workerId,body.get("name") instanceof String name?name:null)));}
    @DeleteMapping("/api/workspaces/{workspaceId}/workers/{workerId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> deleteWorker(@PathVariable String workspaceId,@PathVariable String workerId){return blocking(()->{removeWorker.remove(workspaceId,workerId);return null;});}

    @GetMapping("/api/ui/workspaces/{workspaceId}/dispatches")
    Mono<List<DispatchSummaryResponse>> listDispatches(@PathVariable String workspaceId,
            @RequestParam(required=false) String state,@RequestParam(required=false) String status,
            @RequestParam(required=false) String limit,@RequestParam(required=false) String offset){
        return blocking(()->{if(status!=null)throw new TeamBadRequest("Use state instead of status for dispatch filtering");
            if(state!=null&&!STATES.contains(state))throw new TeamBadRequest("state must be queued, submitted, reported, or cancelled");
            int parsedLimit=bounded(limit,"limit",100,100);int parsedOffset=bounded(offset,"offset",0,100000);
            return dispatches.listDispatches(workspaceId,state,parsedLimit,parsedOffset).stream().map(DispatchSummaryResponse::from).toList();});}

    @GetMapping("/api/ui/workspaces/{workspaceId}/dispatches/{dispatchId}")
    Mono<ResponseEntity<DispatchResponse>> getDispatch(@PathVariable String workspaceId,@PathVariable String dispatchId){
        return blocking(()->dispatches.findDispatch(workspaceId,dispatchId).map(DispatchResponse::from)
                .map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build()));
    }

    @GetMapping("/api/ui/workspaces/{workspaceId}/dispatch-delivery-issues")
    Mono<List<DispatchSummaryResponse>> listDispatchDeliveryIssues(@PathVariable String workspaceId,
            @RequestParam(required=false) String limit) {
        return blocking(() -> dispatches.listDeliveryIssues(workspaceId, bounded(limit,"limit",100,100))
                .stream().map(DispatchSummaryResponse::from).toList());
    }

    private static int bounded(String value,String name,int fallback,int max){if(value==null)return fallback;if(!value.matches("^(0|[1-9][0-9]*)$"))throw new TeamBadRequest(name+" must be a non-negative integer");try{int parsed=Integer.parseInt(value);if(parsed>max)throw new TeamBadRequest(name+" must be between 0 and "+max);return parsed;}catch(NumberFormatException e){throw new TeamBadRequest(name+" must be between 0 and "+max);}}
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work){return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());}
    private record AgentStart(boolean ok,String error,@com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId) { }
}
