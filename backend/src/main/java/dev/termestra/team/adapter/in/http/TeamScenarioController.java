package dev.termestra.team.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.team.application.port.in.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Objects;

@RestController
public final class TeamScenarioController {
    private final ApplyTeamScenarioUseCase scenarios;

    public TeamScenarioController(ApplyTeamScenarioUseCase scenarios) { this.scenarios = scenarios; }

    @PostMapping("/api/workspaces/{workspaceId}/scenarios/{scenarioId}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<ApplyScenarioResponse> apply(@PathVariable String workspaceId, @PathVariable String scenarioId,
                                      @RequestBody ApplyScenarioRequest body, ServerHttpRequest request) {
        return blocking(() -> ApplyScenarioResponse.from(scenarios.apply(new ApplyTeamScenarioCommand(
                workspaceId, scenarioId, body.goal(), body.locale(),
                Integer.toString(Objects.requireNonNull(request.getLocalAddress()).getPort())))));
    }

    public record ApplyScenarioRequest(String goal, String locale) { }

    public record ApplyScenarioResponse(
            @JsonProperty("created_workers") List<CreatedWorkerResponse> createdWorkers,
            boolean injected) {
        static ApplyScenarioResponse from(AppliedTeamScenario value) {
            return new ApplyScenarioResponse(value.createdWorkers().stream()
                    .map(CreatedWorkerResponse::from).toList(), value.injected());
        }
    }

    public record CreatedWorkerResponse(String id, String name, String role, StartResponse start) {
        static CreatedWorkerResponse from(AppliedTeamScenario.StartedMember member) {
            return new CreatedWorkerResponse(member.id(), member.name(), member.role(),
                    new StartResponse(true, member.runId()));
        }
    }

    public record StartResponse(boolean ok, @JsonProperty("run_id") String runId) { }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
