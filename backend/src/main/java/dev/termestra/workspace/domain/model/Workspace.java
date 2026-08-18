package dev.termestra.workspace.domain.model;

import dev.termestra.shared.id.WorkspaceId;

import java.time.Instant;
import java.util.Objects;

public record Workspace(WorkspaceId id, WorkspaceName name, WorkspacePath path, Instant createdAt) {
    public Workspace {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(path); Objects.requireNonNull(createdAt);
    }

    public static Workspace create(WorkspaceName name, WorkspacePath path, Instant now) {
        return new Workspace(WorkspaceId.newId(), name, path, now);
    }
}
