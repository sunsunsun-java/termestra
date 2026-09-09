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

    @GetMapping("/api/ui/workspaces/{workspaceId}/report-delivery-issues")
    Mono<java.util.List<ReportIssueResponse>> reportIssues(@PathVariable String workspaceId,
                                                        @RequestParam(defaultValue="100") int limit) {
        return Mono.fromCallable(() -> deliveries.reportIssues(workspaceId, limit).stream()
                .map(r -> new ReportIssueResponse(r.dispatchId(),r.workerId(),r.state(),r.attemptCount(),r.error(),r.updatedAt())).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/api/ui/workspaces/{workspaceId}/dispatches/{dispatchId}/report-delivery/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Map<String,Object>> retryReport(@PathVariable String workspaceId, @PathVariable String dispatchId,
                                        @RequestBody(required=false) ReportRetryRequest request) {
        return Mono.fromCallable(() -> {
            if (!deliveries.retryReport(workspaceId, dispatchId, request != null && request.confirmUncertain())) {
                throw new TeamConflict("Report notification is not retryable, or uncertain delivery requires confirm_uncertain=true");
            }
            scheduler.wake();
            return Map.<String,Object>of("ok",true,"dispatch_id",dispatchId);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    record ReportRetryRequest(@com.fasterxml.jackson.annotation.JsonProperty("confirm_uncertain") boolean confirmUncertain) { }
    record ReportIssueResponse(@com.fasterxml.jackson.annotation.JsonProperty("dispatch_id") String dispatchId,
                               @com.fasterxml.jackson.annotation.JsonProperty("worker_id") String workerId,
                               String state, @com.fasterxml.jackson.annotation.JsonProperty("attempt_count") int attemptCount,
                               String error, @com.fasterxml.jackson.annotation.JsonProperty("updated_at") long updatedAt) { }

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
