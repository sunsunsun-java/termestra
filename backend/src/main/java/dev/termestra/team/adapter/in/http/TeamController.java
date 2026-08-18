package dev.termestra.team.adapter.in.http;

import dev.termestra.team.application.port.in.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Objects;

@RestController
@RequestMapping("/api/team")
public final class TeamController {
    private final TeamUseCase team;
    public TeamController(TeamUseCase team) { this.team=team; }

    @PostMapping("/send") @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<SendTaskResponse> send(@RequestBody TeamRequests.Send r,org.springframework.http.server.reactive.ServerHttpRequest request){return blocking(()->{String port=r.runtimePort()!=null?r.runtimePort():Integer.toString(Objects.requireNonNull(request.getLocalAddress()).getPort());TeamOperationResult result=team.send(new SendTaskCommand(r.projectId(),r.fromAgentId(),r.token(),r.to(),r.text(),port,r.idempotencyKey()));return new SendTaskResponse(true,result.dispatchId());});}
    @PostMapping("/cancel") @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<TeamOperationResponse> cancel(@RequestBody TeamRequests.Cancel r){return blocking(()->TeamOperationResponse.from(team.cancel(new CancelTaskCommand(r.projectId(),r.fromAgentId(),r.token(),r.dispatchId(),r.reason()))));}
    @PostMapping("/report") @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<TeamOperationResponse> report(@RequestBody TeamRequests.Report r){return blocking(()->TeamOperationResponse.from(team.report(new ReportTaskCommand(r.projectId(),r.fromAgentId(),r.token(),r.dispatchId(),r.result(),r.status(),r.artifacts()))));}
    @PostMapping("/status") @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<TeamOperationResponse> status(@RequestBody TeamRequests.Report r){return blocking(()->TeamOperationResponse.from(team.status(new StatusTaskCommand(r.projectId(),r.fromAgentId(),r.token(),r.result(),r.artifacts()))));}
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work){return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());}
}
