package dev.termestra.workspace.adapter.in.http;

import dev.termestra.workspace.application.port.in.registration.RegistrationOptionsView;
import dev.termestra.workspace.application.port.in.registration.RegistrationStatusView;
import dev.termestra.workspace.application.port.in.registration.WorkspaceRegistrationUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
    public Mono<Map<String, Object>> options(
            @RequestParam("inspection_token") String inspectionToken,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        return Mono.fromCallable(() -> options(
                        registrations.inspect(inspectionToken, query, limit, cursor)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{registrationId}")
    public Mono<Map<String, Object>> status(@PathVariable String registrationId) {
        return Mono.fromCallable(() -> status(registrations.status(registrationId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> options(RegistrationOptionsView value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canonical_path", value.canonicalPath());
        result.put("head", head(value.head()));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("count", value.changes().count());
        changes.put("count_accuracy", value.changes().countAccuracy());
        changes.put("state", value.changes().state());
        result.put("changes", changes);
        result.put("branches", value.branches().stream().map(branch -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("blocked_reason", branch.blockedReason());
            item.put("current", branch.current());
            item.put("name", branch.name());
            item.put("selectable", branch.selectable());
            item.put("selection_token", branch.selectionToken());
            return item;
        }).toList());
        result.put("next_cursor", value.nextCursor());
        return result;
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

    private static Map<String, Object> head(RegistrationOptionsView.HeadView head) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (head instanceof RegistrationOptionsView.BranchHead value) {
            result.put("kind", "branch");
            result.put("name", value.name());
            result.put("oid", value.oid());
        } else if (head instanceof RegistrationOptionsView.DetachedHead value) {
            result.put("kind", "detached");
            result.put("oid", value.oid());
        } else if (head instanceof RegistrationOptionsView.UnbornHead value) {
            result.put("kind", "unborn");
            result.put("name", value.name());
        }
        return result;
    }
}
