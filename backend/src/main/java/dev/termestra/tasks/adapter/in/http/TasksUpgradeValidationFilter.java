package dev.termestra.tasks.adapter.in.http;

import dev.termestra.tasks.application.port.in.TasksUseCase;
import dev.termestra.tasks.application.port.in.TasksWorkspaceNotFound;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class TasksUpgradeValidationFilter implements WebFilter {
    private final TasksUseCase tasks;

    public TasksUpgradeValidationFilter(TasksUseCase tasks) {
        this.tasks = tasks;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String[] parts = exchange.getRequest().getPath().value().split("/");
        if (parts.length != 4 || !"ws".equals(parts[1]) || !"tasks".equals(parts[2])) {
            return chain.filter(exchange);
        }
        return Mono.fromRunnable(() -> tasks.validateWorkspace(parts[3]))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> chain.filter(exchange)))
                .onErrorResume(TasksWorkspaceNotFound.class, error -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                });
    }
}
