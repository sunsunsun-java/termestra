package dev.termestra.auth.adapter.in.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalOnlyFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"localhost:3000", "LOCALHOST:3000", "127.0.0.1:3000", "[::1]:3000"})
    void acceptsLoopbackHostWithoutOrigin(String host) {
        verify("::1", host, null, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "http://127.0.0.1:3000", "http://[::1]:3000"})
    void acceptsLoopbackOrigin(String origin) {
        verify("127.0.0.1", "127.0.0.1:3000", origin, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example:3000", "localhost.evil.example:3000", "[::2]:3000",
            "[2001:db8::1]:3000", "[::1", ""})
    void rejectsUntrustedOrMalformedHost(String host) {
        verify("::1", host, "http://[::1]:3000", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://localhost.evil.example",
            "http://[::2]:3000", "http://[2001:db8::1]:3000", "http://[::1", "null"})
    void rejectsUntrustedOrMalformedOrigin(String origin) {
        verify("::1", "[::1]:3000", origin, false);
    }

    @Test
    void rejectsRemoteClientsEvenWithLoopbackHeaders() {
        verify("192.0.2.1", "[::1]:3000", "http://[::1]:3000", false);
    }

    private static void verify(String remote, String host, String origin, boolean allowed) {
        var request = MockServerHttpRequest.get("http://localhost/api/ui/session")
                .remoteAddress(new InetSocketAddress(remote, 41000)).header("Host", host);
        if (origin != null) request.header("Origin", origin);
        var exchange = MockServerWebExchange.from(request.build());
        var reachedHandler = new AtomicBoolean();
        new LocalOnlyFilter().filter(exchange, ignored -> {
            reachedHandler.set(true);
            return Mono.empty();
        }).block();
        assertEquals(allowed, reachedHandler.get());
        if (allowed) assertNull(exchange.getResponse().getStatusCode());
        else assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }
}
