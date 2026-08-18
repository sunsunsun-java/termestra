package dev.termestra.tasks.adapter.in.http;
import dev.termestra.tasks.application.port.in.TasksDocument;
import dev.termestra.tasks.application.port.in.TasksUseCase;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;
@RestController public final class TasksController {
    private final TasksUseCase tasks; public TasksController(TasksUseCase tasks){this.tasks=tasks;}
    @GetMapping("/api/workspaces/{workspaceId}/tasks") Mono<TasksResponse> read(@PathVariable String workspaceId){return Mono.fromCallable(()->TasksResponse.from(tasks.readDocument(workspaceId))).subscribeOn(Schedulers.boundedElastic());}
    @PutMapping("/api/workspaces/{workspaceId}/tasks") Mono<TasksResponse> write(@PathVariable String workspaceId,@RequestBody Map<String,Object> body){return Mono.fromCallable(()->{
        Object content=body.get("content");
        if(!(content instanceof String text))throw new IllegalArgumentException("content must be a string");
        Object revision=body.get("revision");
        if(revision!=null&&!(revision instanceof String))throw new IllegalArgumentException("revision must be a string");
        return TasksResponse.from(tasks.writeDocument(workspaceId,text,(String)revision));
    }).subscribeOn(Schedulers.boundedElastic());}
    record TasksResponse(String content,String revision){static TasksResponse from(TasksDocument value){return new TasksResponse(value.content(),value.revision());}}
}
