package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.domain.model.Workspace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkspaceRegistrationLedger {
    BeginResult begin(Intent intent);
    void markSourceReady(String registrationId, Instant now);
    Workspace activate(String registrationId, Instant now);
    void fail(String registrationId, Failure failure, Instant now);
    Optional<Attempt> find(String registrationId);
    List<Attempt> recoverable(int limit);

    record Intent(
            String registrationId,
            String requestHash,
            Workspace workspace,
            Instant now) { }

    sealed interface BeginResult permits Begun, Existing, Replay { }
    record Begun(Workspace workspace) implements BeginResult { }
    record Existing(Workspace workspace) implements BeginResult { }
    record Replay(Attempt attempt) implements BeginResult { }

    record Failure(
            String state,
            String checkoutOutcome,
            String errorCode,
            boolean releaseWorkspaceClaim) { }

    record Attempt(
            String registrationId,
            String workspaceId,
            String requestHash,
            String canonicalPath,
            String state,
            String checkoutOutcome,
            String observedHeadKind,
            String observedBranch,
            String observedHeadOid,
            String errorCode,
            Instant createdAt,
            Instant updatedAt) { }
}
