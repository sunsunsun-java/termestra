package dev.termestra.workspace.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.workspace.application.port.in.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public final class OpenWorkspaceController {
    private final OpenWorkspaceUseCase openWorkspace;
    public OpenWorkspaceController(OpenWorkspaceUseCase openWorkspace) { this.openWorkspace = openWorkspace; }

    @PostMapping("/api/workspaces/{workspaceId}/open")
    Mono<ResponseEntity<Map<String, Object>>> open(@PathVariable String workspaceId,
                                                    @RequestBody OpenRequest request) {
        if (!openWorkspace.supports(request.targetId())) {
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("error", "Unknown open target");
            body.put("target_id", request.targetId());
            return Mono.just(ResponseEntity.badRequest().body(body));
        }
        return Mono.fromCallable(() -> response(openWorkspace.open(workspaceId, request.targetId())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Map<String, Object>> response(OpenWorkspaceView value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", value.ok());
        body.put("effective_target_id", value.effectiveTargetId());
        if (!value.ok()) body.put("error_code", value.errorCode());
        return ResponseEntity.status(value.ok() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY).body(body);
    }
    record OpenRequest(@JsonProperty("target_id") String targetId) { }
}
