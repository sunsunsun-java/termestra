package dev.termestra.platform.web;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public final class PwaCacheControlFilter implements WebFilter {
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if ("/sw.js".equals(path)) {
            exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        } else if ("/manifest.webmanifest".equals(path)) {
            exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "max-age=0, must-revalidate");
        }
        return chain.filter(exchange);
    }
}
