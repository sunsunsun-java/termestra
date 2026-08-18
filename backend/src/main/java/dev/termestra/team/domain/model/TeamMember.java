package dev.termestra.team.domain.model;

import dev.termestra.shared.id.AgentId;
import dev.termestra.shared.id.WorkspaceId;
import java.time.Instant;
import java.util.Objects;

public record TeamMember(AgentId id, WorkspaceId workspaceId, String name, String description,
                         AgentRole role, Instant createdAt) {
    public TeamMember {
        Objects.requireNonNull(id); Objects.requireNonNull(workspaceId); Objects.requireNonNull(role); Objects.requireNonNull(createdAt);
        if (!role.isWorker()) throw new IllegalArgumentException("orchestrator is not a persisted worker");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("worker name must not be blank");
        name = name.trim(); description = description == null ? DefaultRoleDescription.forRole(role) : description;
    }
    public static TeamMember create(WorkspaceId workspaceId, String name, String description, AgentRole role, Instant at) {
        return new TeamMember(AgentId.newId(), workspaceId, name, description, role, at);
    }
}
