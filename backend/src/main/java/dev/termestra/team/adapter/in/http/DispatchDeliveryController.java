package dev.termestra.team.adapter.in.http;

import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.port.in.DispatchDeliveryUseCase;
import dev.termestra.team.application.port.out.DispatchDeliveryScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
public final class DispatchDeliveryController {
    private final DispatchDeliveryUseCase deliveries;
    private final DispatchDeliveryScheduler scheduler;

    public DispatchDeliveryController(DispatchDeliveryUseCase deliveries,
                                      DispatchDeliveryScheduler scheduler) {
        this.deliveries = deliveries;
        this.scheduler = scheduler;
    }

    @PostMapping("/api/ui/workspaces/{workspaceId}/dispatches/{dispatchId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Map<String, Object>> retry(@PathVariable String workspaceId,
                                    @PathVariable String dispatchId) {
        return Mono.fromCallable(() -> {
            if (!deliveries.retry(workspaceId, dispatchId)) {
                throw new TeamConflict("Only uncertain or failed queued deliveries can be retried");
            }
            scheduler.wake();
            return Map.<String, Object>of("ok", true, "dispatch_id", dispatchId);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
