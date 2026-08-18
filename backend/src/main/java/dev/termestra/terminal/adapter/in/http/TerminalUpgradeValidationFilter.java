package dev.termestra.terminal.adapter.in.http;

import dev.termestra.execution.application.exception.RunNotFound;
import dev.termestra.terminal.application.port.in.TerminalChannelUseCase;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class TerminalUpgradeValidationFilter implements WebFilter {
    private final TerminalChannelUseCase terminal;
    public TerminalUpgradeValidationFilter(TerminalChannelUseCase terminal) { this.terminal = terminal; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String[] parts = path.split("/");
        if (parts.length != 5 || !"ws".equals(parts[1]) || !"terminal".equals(parts[2])
                || !("io".equals(parts[4]) || "control".equals(parts[4]))) return chain.filter(exchange);
        try { terminal.status(parts[3]); return chain.filter(exchange); }
        catch (RunNotFound error) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
    }
}
