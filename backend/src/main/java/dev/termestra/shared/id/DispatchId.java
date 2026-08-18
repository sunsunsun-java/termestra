package dev.termestra.shared.id;

import java.util.Objects;
import java.util.UUID;

public record DispatchId(UUID value) {
    public DispatchId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static DispatchId newId() {
        return new DispatchId(UUID.randomUUID());
    }

    public static DispatchId parse(String value) {
        return new DispatchId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

