package dev.termestra.auth.adapter.in.http;

import dev.termestra.auth.application.UiSessionService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class UiSessionFilter implements WebFilter {
    private static final byte[] FORBIDDEN = "{\"error_code\":\"UI_SESSION_INVALID\",\"error\":\"UI endpoint requires valid UI token\"}"
            .getBytes(StandardCharsets.UTF_8);
    private final UiSessionService sessions;
    public UiSessionFilter(UiSessionService sessions) { this.sessions = sessions; }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean webSocket = path.startsWith("/ws/");
        if ((!path.startsWith("/api/") && !webSocket) || path.equals("/api/ui/session") || path.equals("/api/version") || path.startsWith("/api/team/")
                || path.matches("/api/workspaces/[^/]+/team")) return chain.filter(exchange);
        var cookie = exchange.getRequest().getCookies().getFirst(UiSessionController.COOKIE_NAME);
        if (cookie != null && sessions.isValid(cookie.getValue())) return chain.filter(exchange);
        exchange.getResponse().setStatusCode(webSocket ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN);
        if (webSocket) return exchange.getResponse().setComplete();
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(FORBIDDEN)));
    }
}
