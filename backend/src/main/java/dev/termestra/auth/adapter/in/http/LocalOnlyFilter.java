package dev.termestra.auth.adapter.in.http;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class LocalOnlyFilter implements WebFilter {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        String host = exchange.getRequest().getHeaders().getFirst("Host");
        String origin = exchange.getRequest().getHeaders().getFirst("Origin");
        if (address != null && address.isLoopbackAddress() && localAuthority(host) && localOrigin(origin)) return chain.filter(exchange);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
    private static boolean localAuthority(String authority) {
        if (authority == null) return true;
        try { return isLocalHost(new URI("http://" + authority).getHost()); }
        catch (URISyntaxException invalidAuthority) { return false; }
    }
    private static boolean localOrigin(String origin) {
        if (origin == null) return true;
        try { return isLocalHost(new URI(origin).getHost()); }
        catch (URISyntaxException invalidOrigin) { return false; }
    }
    private static boolean isLocalHost(String host) {
        return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }
}
