package dev.termestra.shared.id;

import java.util.Objects;
import java.util.UUID;

public record AgentId(UUID value) {
    public AgentId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static AgentId newId() {
        return new AgentId(UUID.randomUUID());
    }

    public static AgentId parse(String value) {
        return new AgentId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

