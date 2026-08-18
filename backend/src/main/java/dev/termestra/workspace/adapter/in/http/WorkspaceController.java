package dev.termestra.workspace.adapter.in.http;

import dev.termestra.workspace.application.port.in.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public final class WorkspaceController {
    private final CreateWorkspaceUseCase createWorkspace;
    private final ListWorkspacesQuery listWorkspaces;
    private final DeleteWorkspaceUseCase deleteWorkspace;
    public WorkspaceController(CreateWorkspaceUseCase createWorkspace, ListWorkspacesQuery listWorkspaces,DeleteWorkspaceUseCase deleteWorkspace) {
        this.createWorkspace = createWorkspace; this.listWorkspaces = listWorkspaces;this.deleteWorkspace=deleteWorkspace;
    }

    @GetMapping public Mono<List<WorkspaceListResponse>> list() {
        return Mono.fromCallable(() -> listWorkspaces.list().stream().map(WorkspaceListResponse::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<ResponseEntity<WorkspaceResponse>> create(@RequestBody CreateWorkspaceRequest request) {
        return Mono.fromCallable(() -> {
                    CreateWorkspaceResult result = createWorkspace.create(new CreateWorkspaceCommand(
                            request.path(), request.name(), request.startupCommand(),
                            request.commandPresetId(), request.shouldAutostart()));
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
}
