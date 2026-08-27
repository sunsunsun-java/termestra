package dev.termestra.workspace.adapter.in.http;

import dev.termestra.workspace.application.port.in.*;
import dev.termestra.workspace.application.port.in.registration.*;
import dev.termestra.execution.application.exception.InvalidLaunchRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public final class WorkspaceController {
    private final WorkspaceRegistrationUseCase createWorkspace;
    private final ListWorkspacesQuery listWorkspaces;
    private final DeleteWorkspaceUseCase deleteWorkspace;
    public WorkspaceController(WorkspaceRegistrationUseCase createWorkspace, ListWorkspacesQuery listWorkspaces,DeleteWorkspaceUseCase deleteWorkspace) {
        this.createWorkspace = createWorkspace; this.listWorkspaces = listWorkspaces;this.deleteWorkspace=deleteWorkspace;
    }

    @GetMapping public Mono<List<WorkspaceListResponse>> list() {
        return Mono.fromCallable(() -> listWorkspaces.list().stream().map(WorkspaceListResponse::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<ResponseEntity<WorkspaceResponse>> create(@RequestBody CreateWorkspaceRequest request) {
        return Mono.fromCallable(() -> {
                    CreateWorkspaceRequest.RevisionSelectionRequest requested = request.revisionSelection();
                    RevisionSelection selection = requested == null || requested.kind() == null
                            || "current".equals(requested.kind())
                            ? new RevisionSelection.Current(requested == null ? null : requested.selectionToken())
                            : "local_branch".equals(requested.kind())
                                ? new RevisionSelection.LocalBranch(requested.name(), requested.selectionToken())
                                : throwInvalidSelection(requested.kind());
                    if(request.launch()!=null&&(request.startupCommand()!=null||request.commandPresetId()!=null)){
                        throw new InvalidLaunchRequest("LAUNCH_CONTRACT_CONFLICT",
                                "launch cannot be combined with legacy fields");
                    }
                    String startup=request.startupCommand();String preset=request.commandPresetId();String model=null;Long revision=null;
                    if(request.launch()!=null){var launch=request.launch();launch.validate();if("inherit_orchestrator".equals(launch.type()))throw new InvalidLaunchRequest("LAUNCH_CONTRACT_CONFLICT","Workspace cannot inherit an Orchestrator launch");if("startup".equals(launch.type())){startup=launch.startupCommand();preset=launch.recoveryPresetId();}else {preset=launch.presetId();model=launch.modelId();revision=launch.expectedPresetRevision();}}
                    CreateWorkspaceResult result = createWorkspace.register(new RegisterWorkspaceCommand(
                            request.registrationId(),request.path(),request.name(),startup,preset,model,revision,
                            request.shouldAutostart(),selection));
                    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
                    return ResponseEntity.status(status).body(WorkspaceResponse.from(result));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
    @DeleteMapping("/{workspaceId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String workspaceId){return Mono.fromCallable(()->{deleteWorkspace.delete(workspaceId);return (Void)null;}).subscribeOn(Schedulers.boundedElastic());}

    private record WorkspaceListResponse(String id, String name, String path) {
        private static WorkspaceListResponse from(WorkspaceView workspace) {
            return new WorkspaceListResponse(workspace.id(),
                    WorkspaceInputLimits.boundedName(workspace.name()), workspace.path());
        }
    }

    private static RevisionSelection throwInvalidSelection(String kind) {
        throw new IllegalArgumentException("Unknown revision_selection kind: " + kind);
    }
}
