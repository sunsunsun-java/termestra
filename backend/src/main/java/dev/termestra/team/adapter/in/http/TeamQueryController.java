package dev.termestra.team.adapter.in.http;

import dev.termestra.team.application.exception.TeamBadRequest;
import dev.termestra.team.application.port.in.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

@RestController
public final class TeamQueryController {
    private static final Set<String> STATES=Set.of("queued","submitted","reported","cancelled");
    private final TeamUseCase team; private final TeamAdminUseCase admin; private final RemoveWorkerUseCase removeWorker; private final DispatchQuery dispatches;private final TeamMemberOutputEnricher outputEnricher;
    private final dev.termestra.execution.application.port.in.AgentExecutionUseCase execution;private final dev.termestra.configuration.application.port.in.ConfigurationUseCase settings;private final com.fasterxml.jackson.databind.ObjectMapper json;
    public TeamQueryController(TeamUseCase team,TeamAdminUseCase admin,RemoveWorkerUseCase removeWorker,DispatchQuery dispatches,TeamMemberOutputEnricher outputEnricher,dev.termestra.execution.application.port.in.AgentExecutionUseCase execution,dev.termestra.configuration.application.port.in.ConfigurationUseCase settings,com.fasterxml.jackson.databind.ObjectMapper json){this.team=team;this.admin=admin;this.removeWorker=removeWorker;this.dispatches=dispatches;this.outputEnricher=outputEnricher;this.execution=execution;this.settings=settings;this.json=json;}

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
        boolean startup=request.startupCommand()!=null&&!request.startupCommand().isBlank();
        var selected=request.commandPresetId()==null?Optional.<dev.termestra.configuration.domain.model.CommandPreset>empty():settings.commandPresets().stream().filter(item->item.id().equals(request.commandPresetId())).findFirst();
        if(!startup&&request.commandPresetId()!=null&&selected.isEmpty())throw new TeamBadRequest("Command preset not found: "+request.commandPresetId());
        TeamMemberView created=admin.addWorker(new AddWorkerCommand(workspaceId,request.name(),request.description(),request.role()));
        try {
            if(startup){boolean windows=System.getProperty("os.name","").toLowerCase().contains("win");String shell=windows?System.getenv().getOrDefault("ComSpec","cmd.exe"):Objects.requireNonNullElse(System.getenv("SHELL"),"/bin/sh");String shellName=java.nio.file.Path.of(shell).getFileName().toString().toLowerCase();List<String> args=windows?List.of("/d","/s","/c",request.startupCommand().trim()):List.of(shellName.contains("bash")||shellName.contains("zsh")||shellName.contains("ksh")?"-lic":"-ic",request.startupCommand().trim());String interactive=selected.map(dev.termestra.configuration.domain.model.CommandPreset::command).orElse(request.startupCommand().trim());execution.configure(new dev.termestra.execution.application.port.in.ConfigureAgentCommand(workspaceId,created.id(),shell,args,null,interactive,true,selected.map(dev.termestra.configuration.domain.model.CommandPreset::resumeArgsTemplate).orElse(null),capture(selected.orElse(null)),selected.map(dev.termestra.configuration.domain.model.CommandPreset::environment).orElse(Map.of())));}
            else if(selected.isPresent()){var preset=selected.orElseThrow();execution.configure(new dev.termestra.execution.application.port.in.ConfigureAgentCommand(workspaceId,created.id(),preset.command(),preset.arguments(),preset.id(),null,false,preset.resumeArgsTemplate(),capture(preset),preset.environment()));}
        } catch (RuntimeException configurationFailure) {
            try { admin.deleteWorker(workspaceId,created.id()); }
            catch (RuntimeException rollbackFailure) { configurationFailure.addSuppressed(rollbackFailure); }
            throw configurationFailure;
        }
        TeamMemberResponse worker=TeamMemberResponse.from(admin.listForUi(workspaceId).stream().filter(value->value.id().equals(created.id())).findFirst().orElseThrow());
        Map<String,Object> body=new LinkedHashMap<>();body.put("id",worker.id());body.put("name",worker.name());body.put("role",worker.role());body.put("status",worker.status());
        body.put("pending_task_count",worker.pendingTaskCount());body.put("last_pty_line",worker.lastPtyLine());body.put("command_preset_id",worker.commandPresetId());
        AgentStart start=new AgentStart(false,null,null);if(Boolean.TRUE.equals(request.autostart()))try{String port=Integer.toString(Objects.requireNonNull(serverRequest.getLocalAddress()).getPort());var run=execution.start(new dev.termestra.execution.application.port.in.StartAgentCommand(workspaceId,worker.id(),port));start=new AgentStart(true,null,run.runId());}catch(RuntimeException error){start=new AgentStart(false,error.getMessage(),null);}
        body.put("agent_start",start);return body;});}

    private String capture(dev.termestra.configuration.domain.model.CommandPreset preset){if(preset==null||preset.sessionIdCapture()==null)return null;try{return json.writeValueAsString(preset.sessionIdCapture());}catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Invalid session capture configuration",error);}}
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
