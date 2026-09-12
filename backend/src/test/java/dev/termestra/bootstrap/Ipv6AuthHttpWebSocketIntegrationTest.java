package dev.termestra.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf(value = "ipv6LoopbackAvailable", disabledReason = "Host cannot bind IPv6 loopback ::1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=::1")
class Ipv6AuthHttpWebSocketIntegrationTest {
    private static final Path DATA = temporaryDirectory("termestra-ipv6-auth-");
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA::toString);
    }

    @Test
    void acceptsAuthenticatedIpv6HttpAndWebSocketButPreservesRejectionBoundaries() throws Exception {
        String origin = "http://[::1]:" + port;
        WebTestClient http = WebTestClient.bindToServer().baseUrl(origin)
                .defaultHeader(HttpHeaders.ORIGIN, origin).build();
        String cookieHeader = http.get().uri("/api/ui/session").exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie = Objects.requireNonNull(cookieHeader).split(";", 2)[0];
        http.get().uri("/api/workspaces").exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error_code").isEqualTo("UI_SESSION_INVALID");
        http.get().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .exchange().expectStatus().isOk();
        http.get().uri("/api/ui/session").header(HttpHeaders.ORIGIN, "https://evil.example")
                .exchange().expectStatus().isForbidden();

        Map<?, ?> workspace = http.post().uri("/api/workspaces").header(HttpHeaders.COOKIE, cookie)
                .bodyValue(Map.of("path", temporaryDirectory("termestra-ipv6-workspace-").toString(),
                        "autostart_orchestrator", false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId = Objects.requireNonNull(workspace).get("id").toString();
        URI endpoint = URI.create("ws://[::1]:" + port + "/ws/tasks/" + workspaceId);
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertHandshakeStatus(client, endpoint, origin, null, 401);
            assertHandshakeStatus(client, endpoint, "https://evil.example", cookie, 403);
            var listener = new SnapshotListener();
            WebSocket socket = client.newWebSocketBuilder().header(HttpHeaders.ORIGIN, origin)
                    .header(HttpHeaders.COOKIE, cookie).connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(endpoint, listener).get(5, TimeUnit.SECONDS);
            try {
                assertTrue(listener.snapshot.get(5, TimeUnit.SECONDS).contains("tasks-snapshot"));
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            } finally {
                socket.abort();
            }
        }
    }

    private static void assertHandshakeStatus(HttpClient client, URI endpoint, String origin,
                                              String cookie, int expectedStatus) {
        var builder = client.newWebSocketBuilder().header(HttpHeaders.ORIGIN, origin)
                .connectTimeout(Duration.ofSeconds(5));
        if (cookie != null) builder.header(HttpHeaders.COOKIE, cookie);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> builder.buildAsync(endpoint, new SnapshotListener()).get(5, TimeUnit.SECONDS));
        var handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
        assertEquals(expectedStatus, handshake.getResponse().statusCode());
    }

    private static final class SnapshotListener implements WebSocket.Listener {
        private final CompletableFuture<String> snapshot = new CompletableFuture<>();
        private final StringBuilder message = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            message.append(text);
            if (last) snapshot.complete(message.toString());
            socket.request(1);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) {
            snapshot.completeExceptionally(error);
        }
    }

    static boolean ipv6LoopbackAvailable() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static Path temporaryDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix).toRealPath(); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
}
