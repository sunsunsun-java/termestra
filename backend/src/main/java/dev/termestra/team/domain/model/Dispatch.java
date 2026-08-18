package dev.termestra.team.domain.model;

import dev.termestra.shared.id.AgentId;
import dev.termestra.shared.id.DispatchId;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.team.domain.exception.InvalidDispatchTransition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Dispatch {
    private final DispatchId id;
    private final WorkspaceId workspaceId;
    private final String fromAgentId;
    private final AgentId toAgentId;
    private final TaskText task;
    private final Instant createdAt;

    private DispatchStatus status;
    private Instant submittedAt;
    private Instant deliveredAt;
    private Instant reportedAt;
    private String reportText;
    private String cancellationReason;
    private List<String> artifacts;

    private Dispatch(
            DispatchId id,
            WorkspaceId workspaceId,
            String fromAgentId,
            AgentId toAgentId,
            TaskText task,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.fromAgentId = fromAgentId;
        this.toAgentId = Objects.requireNonNull(toAgentId);
        this.task = Objects.requireNonNull(task);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = DispatchStatus.QUEUED;
        this.artifacts = List.of();
    }

    public static Dispatch create(
            WorkspaceId workspaceId,
            AgentId fromAgentId,
            AgentId toAgentId,
            TaskText task,
            Instant createdAt) {
        return new Dispatch(
                DispatchId.newId(), workspaceId, fromAgentId == null ? null : fromAgentId.toString(), toAgentId, task, createdAt);
    }

    public static Dispatch create(WorkspaceId workspaceId, String fromAgentId, AgentId toAgentId,
                                  TaskText task, Instant createdAt) {
        return new Dispatch(DispatchId.newId(), workspaceId, fromAgentId, toAgentId, task, createdAt);
    }

    public static Dispatch restore(DispatchId id, WorkspaceId workspaceId, String fromAgentId,
                                   AgentId toAgentId, TaskText task, Instant createdAt,
                                   DispatchStatus status, Instant submittedAt, Instant deliveredAt,
                                   Instant reportedAt, String reportText, List<String> artifacts) {
        Dispatch dispatch = new Dispatch(id, workspaceId, fromAgentId, toAgentId, task, createdAt);
        dispatch.status = Objects.requireNonNull(status);
        dispatch.submittedAt = submittedAt;
        dispatch.deliveredAt = deliveredAt;
        dispatch.reportedAt = reportedAt;
        dispatch.reportText = reportText;
        if (status == DispatchStatus.CANCELLED) dispatch.cancellationReason = reportText;
        dispatch.artifacts = List.copyOf(Objects.requireNonNullElse(artifacts, List.of()));
        return dispatch;
    }

    public void markSubmitted(Instant at) {
        requireStatus(DispatchStatus.QUEUED, DispatchStatus.SUBMITTED);
        status = DispatchStatus.SUBMITTED;
        submittedAt = Objects.requireNonNull(at);
    }

    public void markDelivered(Instant at) {
        if (status != DispatchStatus.SUBMITTED) {
            throw new InvalidDispatchTransition(status, DispatchStatus.SUBMITTED);
        }
        deliveredAt = Objects.requireNonNull(at);
    }

    public void report(String text, List<String> reportedArtifacts, Instant at) {
        if (status != DispatchStatus.QUEUED && status != DispatchStatus.SUBMITTED) {
            throw new InvalidDispatchTransition(status, DispatchStatus.REPORTED);
        }
        status = DispatchStatus.REPORTED;
        reportText = Objects.requireNonNullElse(text, "");
        artifacts = List.copyOf(Objects.requireNonNullElse(reportedArtifacts, List.of()));
        reportedAt = Objects.requireNonNull(at);
    }

    public void cancel(String reason, Instant at) {
        if (status != DispatchStatus.QUEUED && status != DispatchStatus.SUBMITTED) {
            throw new InvalidDispatchTransition(status, DispatchStatus.CANCELLED);
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("cancellation reason must not be blank");
        }
        status = DispatchStatus.CANCELLED;
        cancellationReason = reason;
        reportText = reason;
        reportedAt = Objects.requireNonNull(at);
    }

    private void requireStatus(DispatchStatus expected, DispatchStatus target) {
        if (status != expected) {
            throw new InvalidDispatchTransition(status, target);
        }
    }

    public DispatchId id() { return id; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public Optional<String> fromAgentId() { return Optional.ofNullable(fromAgentId); }
    public AgentId toAgentId() { return toAgentId; }
    public TaskText task() { return task; }
    public DispatchStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Optional<Instant> submittedAt() { return Optional.ofNullable(submittedAt); }
    public Optional<Instant> deliveredAt() { return Optional.ofNullable(deliveredAt); }
    public Optional<Instant> reportedAt() { return Optional.ofNullable(reportedAt); }
    public Optional<String> reportText() { return Optional.ofNullable(reportText); }
    public Optional<String> cancellationReason() { return Optional.ofNullable(cancellationReason); }
    public List<String> artifacts() { return artifacts; }
}
