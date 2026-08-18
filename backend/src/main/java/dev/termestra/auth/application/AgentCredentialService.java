package dev.termestra.auth.application;

import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentCredentialService {
    private final ConcurrentHashMap<String, Set<String>> tokens = new ConcurrentHashMap<>();

    public String issue(String agentId) {
        requireAgentId(agentId);
        String token = UUID.randomUUID().toString();
        Set<String> replacement = ConcurrentHashMap.newKeySet(1);
        replacement.add(token);
        tokens.put(agentId, replacement);
        return token;
    }

    public String issueConcurrent(String agentId) {
        requireAgentId(agentId);
        String token = UUID.randomUUID().toString();
        tokens.compute(agentId, (ignored, current) -> {
            Set<String> values = current == null ? ConcurrentHashMap.newKeySet() : current;
            values.add(token);
            return values;
        });
        return token;
    }

    public boolean validate(String agentId, String token) {
        Set<String> values = agentId == null ? null : tokens.get(agentId);
        return token != null && values != null && values.contains(token);
    }

    public Optional<String> currentToken(String agentId) {
        Set<String> values = tokens.get(agentId);
        return values == null ? Optional.empty() : values.stream().findFirst();
    }

    public void revoke(String agentId, String token) {
        if (agentId == null || token == null) return;
        tokens.computeIfPresent(agentId, (ignored, values) -> {
            values.remove(token);
            return values.isEmpty() ? null : values;
        });
    }

    private static void requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agent id must not be blank");
        }
    }
}
