package dev.termestra.shared.id;

import java.util.Objects;
import java.util.UUID;

public record RunId(UUID value) {
    public RunId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static RunId newId() {
        return new RunId(UUID.randomUUID());
    }

    public static RunId parse(String value) {
        return new RunId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

