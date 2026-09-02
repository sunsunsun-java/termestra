package dev.termestra.workspace.adapter.in.http;

import dev.termestra.workspace.application.port.in.registration.RegistrationStatusView;
import dev.termestra.workspace.application.port.in.registration.WorkspaceRegistrationUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace-registrations")
public final class WorkspaceRegistrationController {
    private final WorkspaceRegistrationUseCase registrations;

    public WorkspaceRegistrationController(WorkspaceRegistrationUseCase registrations) {
        this.registrations = registrations;
    }

    @GetMapping("/options")
    public ResponseEntity<Map<String, Object>> removedOptions() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Workspace branch selection has been removed");
        body.put("error_code", "WORKSPACE_REVISION_OPTIONS_REMOVED");
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    @GetMapping("/{registrationId}")
    public Mono<Map<String, Object>> status(@PathVariable String registrationId) {
        return Mono.fromCallable(() -> status(registrations.status(registrationId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> status(RegistrationStatusView value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error_code", value.errorCode());
        result.put("observed_head", value.observedHead() == null ? null : head(value.observedHead()));
        result.put("registration_id", value.registrationId());
        result.put("source_revision_changed", value.sourceRevisionChanged());
        result.put("status", value.status());
        result.put("workspace_id", value.workspaceId());
        return result;
    }

    private static Map<String, Object> head(RegistrationStatusView.ObservedHead head) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (head instanceof RegistrationStatusView.BranchHead value) {
            result.put("kind", "branch");
            result.put("name", value.name());
            result.put("oid", value.oid());
        } else if (head instanceof RegistrationStatusView.DetachedHead value) {
            result.put("kind", "detached");
            result.put("oid", value.oid());
        } else if (head instanceof RegistrationStatusView.UnbornHead value) {
            result.put("kind", "unborn");
            result.put("name", value.name());
        }
        return result;
    }
}
