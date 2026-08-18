package dev.termestra.tasks.adapter.in.http;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.tasks.application.port.in.TasksUseCase;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import java.util.Map;
@Configuration public class TasksWebSocketConfiguration {
    @Bean WebSocketHandler tasksWebSocketHandler(TasksUseCase tasks,ObjectMapper json){return new TasksWebSocketHandler(tasks,json);}
    @Bean HandlerMapping tasksWebSocketMapping(WebSocketHandler tasksWebSocketHandler){SimpleUrlHandlerMapping mapping=new SimpleUrlHandlerMapping();mapping.setOrder(Ordered.HIGHEST_PRECEDENCE+21);mapping.setUrlMap(Map.of("/ws/tasks/{workspaceId}",tasksWebSocketHandler));return mapping;}
}
