package dev.termestra.auth.adapter.in.http;

import dev.termestra.auth.application.UiSessionService;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

@RestController
public final class UiSessionController {
    public static final String COOKIE_NAME = "termestra_ui_token";
    private final UiSessionService sessions;
    public UiSessionController(UiSessionService sessions) { this.sessions = sessions; }

    @GetMapping("/api/ui/session")
    public Map<String, Boolean> create(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, sessions.issue())
                .httpOnly(true).sameSite("Strict").path("/").build();
        exchange.getResponse().addCookie(cookie);
        return Map.of("ok", true);
    }
}
