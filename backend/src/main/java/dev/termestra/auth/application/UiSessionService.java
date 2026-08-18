package dev.termestra.auth.application;

import java.util.UUID;

public final class UiSessionService {
    // The cookie is origin-wide rather than tab-scoped, so retaining every
    // token ever issued adds no isolation and grows without a bound. Keep one
    // process-scoped CSRF token; a runtime restart intentionally rotates it.
    private final String token = UUID.randomUUID().toString();

    public String issue() {
        return token;
    }

    public boolean isValid(String token) {
        return this.token.equals(token);
    }
}
