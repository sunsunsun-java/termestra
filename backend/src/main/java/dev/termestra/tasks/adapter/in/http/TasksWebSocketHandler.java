package dev.termestra.tasks.adapter.in.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.tasks.application.port.in.TasksDocumentEvent;
import dev.termestra.tasks.application.port.in.TasksSubscription;
import dev.termestra.tasks.application.port.in.TasksUseCase;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

public final class TasksWebSocketHandler implements WebSocketHandler {
    private final TasksUseCase tasks;
    private final ObjectMapper json;

    public TasksWebSocketHandler(TasksUseCase tasks, ObjectMapper json) {
        this.tasks = tasks;
        this.json = json;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String workspace = session.getHandshakeInfo().getUri().getPath().split("/")[3];
        Flux<WebSocketMessage> updates = Flux.defer(() -> Flux.<TasksDocumentEvent>create(sink -> {
                    TasksSubscription subscription = tasks.observe(workspace, sink::next, sink::complete);
                    sink.onDispose(subscription::close);
                }, FluxSink.OverflowStrategy.LATEST))
                .subscribeOn(Schedulers.boundedElastic())
                .map(event -> message(session,
                        event.snapshot() ? "tasks-snapshot" : "tasks-updated",
                        event.content(), event.revision()));
        return session.send(updates).and(session.receive().then());
    }

    private WebSocketMessage message(WebSocketSession session, String type, String content, String revision) {
        try {
            return session.textMessage(json.writeValueAsString(Map.of("type", type, "content", content, "revision", revision)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize tasks message", error);
        }
    }
}
