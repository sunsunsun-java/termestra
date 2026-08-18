package dev.termestra.shared.id;

import java.util.Objects;
import java.util.UUID;

public record WorkspaceId(UUID value) {
    public WorkspaceId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static WorkspaceId newId() {
        return new WorkspaceId(UUID.randomUUID());
    }

    public static WorkspaceId parse(String value) {
        return new WorkspaceId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

