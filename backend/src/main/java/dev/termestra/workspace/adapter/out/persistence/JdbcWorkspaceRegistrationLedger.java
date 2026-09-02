package dev.termestra.workspace.adapter.out.persistence;

import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.workspace.application.exception.WorkspaceLimitReached;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationConflict;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationNotFound;
import dev.termestra.workspace.application.port.in.WorkspaceInputLimits;
import dev.termestra.workspace.application.port.out.WorkspaceRegistrationLedger;
import dev.termestra.workspace.domain.model.Workspace;
import dev.termestra.workspace.domain.model.WorkspaceName;
import dev.termestra.workspace.domain.model.WorkspacePath;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcWorkspaceRegistrationLedger implements WorkspaceRegistrationLedger {
    private static final int MAX_RECOVERY_BATCH = 256;
    static final int MAX_RETAINED_ATTEMPTS = 4_096;
    private final SqliteDatabase database;

    public JdbcWorkspaceRegistrationLedger(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public BeginResult begin(Intent intent) {
        return database.write("begin workspace registration", connection -> {
            Optional<Attempt> replay = findAttempt(connection, intent.registrationId());
            if (replay.isPresent()) {
                Attempt existing = replay.orElseThrow();
                if (!existing.requestHash().equals(intent.requestHash())) {
                    throw new WorkspaceRegistrationConflict(
                            "WORKSPACE_REGISTRATION_ID_REUSED",
                            "registration_id was already used for a different request", null);
                }
                return new Replay(existing);
            }

            ExistingWorkspace existing = findPathOwner(connection, intent.workspace().path().value());
            if (existing != null) {
                if (!"active".equals(existing.lifecycleState())) {
                    throw new WorkspaceRegistrationConflict(
                            "WORKSPACE_REGISTRATION_IN_PROGRESS",
                            "Another registration is still preparing this Workspace path",
                            existing.workspace().id().toString());
                }
                return new Existing(existing.workspace());
            }

            pruneTerminalAttempts(connection);
            try (PreparedStatement capacity = connection.prepareStatement(
                    "SELECT COUNT(*) FROM workspace_registration_attempts")) {
                try (ResultSet result = capacity.executeQuery()) {
                    if (result.next() && result.getInt(1) >= MAX_RETAINED_ATTEMPTS) {
                        throw new WorkspaceRegistrationConflict(
                                "WORKSPACE_REGISTRATION_CAPACITY_REACHED",
                                "Too many Workspace registrations are still retained", null);
                    }
                }
            }

            try (PreparedStatement count = connection.prepareStatement("""
                    SELECT COUNT(*) FROM workspaces
                    WHERE deleted_at IS NULL
                      AND lifecycle_state IN ('preparing','active')
                      AND (canonical_path_owner=1 OR canonical_path IS NULL)
                    """)) {
                try (ResultSet result = count.executeQuery()) {
                    if (result.next() && result.getInt(1) >= JdbcWorkspaceRepository.MAX_ACTIVE_WORKSPACES) {
                        throw new WorkspaceLimitReached(JdbcWorkspaceRepository.MAX_ACTIVE_WORKSPACES);
                    }
                }
            }

            Workspace workspace = intent.workspace();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO workspaces(
                        id,name,path,created_at,canonical_path,canonical_path_owner,lifecycle_state)
                    VALUES(?,?,?,?,?,1,'preparing')
                    """)) {
                insert.setString(1, workspace.id().toString());
                insert.setString(2, workspace.name().value());
                insert.setString(3, workspace.path().value());
                insert.setLong(4, workspace.createdAt().toEpochMilli());
                insert.setString(5, workspace.path().value());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO workspace_registration_attempts(
                        registration_id,workspace_id,request_hash,canonical_path,
                        selection_kind,selected_branch,selected_ref_oid,
                        state,checkout_outcome,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,'reserved','not_attempted',?,?)
                    """)) {
                insert.setString(1, intent.registrationId());
                insert.setString(2, workspace.id().toString());
                insert.setString(3, intent.requestHash());
                insert.setString(4, workspace.path().value());
                insert.setString(5, "current");
                insert.setNull(6, Types.VARCHAR);
                insert.setNull(7, Types.VARCHAR);
                insert.setLong(8, intent.now().toEpochMilli());
                insert.setLong(9, intent.now().toEpochMilli());
                insert.executeUpdate();
            }
            return new Begun(workspace);
        });
    }

    @Override
    public void markSourceReady(String registrationId, Instant now) {
        transition(registrationId, "reserved", "checkout_applied", "not_attempted", now);
    }

    @Override
    public Workspace activate(String registrationId, Instant now) {
        return database.write("activate workspace registration", connection -> {
            Attempt attempt = findAttempt(connection, registrationId)
                    .orElseThrow(() -> new WorkspaceRegistrationNotFound(registrationId));
            if ("completed".equals(attempt.state())) {
                return findWorkspace(connection, attempt.workspaceId())
                        .orElseThrow(() -> new IllegalStateException("Completed registration lost its Workspace"));
            }
            if (!"checkout_applied".equals(attempt.state()) || attempt.workspaceId() == null) {
                throw invalidTransition(registrationId, attempt.state(), "completed");
            }
            int workspaceUpdated;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE workspaces SET lifecycle_state='active'
                    WHERE id=? AND lifecycle_state='preparing' AND deleted_at IS NULL
                    """)) {
                update.setString(1, attempt.workspaceId());
                workspaceUpdated = update.executeUpdate();
            }
            int attemptUpdated;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE workspace_registration_attempts
                    SET state='completed',updated_at=?
                    WHERE registration_id=? AND state='checkout_applied'
                    """)) {
                update.setLong(1, now.toEpochMilli());
                update.setString(2, registrationId);
                attemptUpdated = update.executeUpdate();
            }
            if (workspaceUpdated != 1 || attemptUpdated != 1) {
                throw new IllegalStateException("Workspace registration activation lost its expected state");
            }
            return findWorkspace(connection, attempt.workspaceId()).orElseThrow();
        });
    }

    @Override
    public void fail(String registrationId, Failure failure, Instant now) {
        database.write("fail workspace registration", connection -> {
            Attempt current = findAttempt(connection, registrationId)
                    .orElseThrow(() -> new WorkspaceRegistrationNotFound(registrationId));
            if ("completed".equals(current.state())) {
                throw invalidTransition(registrationId, current.state(), failure.state());
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE workspace_registration_attempts
                    SET state=?,checkout_outcome=?,error_code=?,updated_at=?
                    WHERE registration_id=?
                    """)) {
                update.setString(1, failure.state());
                update.setString(2, failure.checkoutOutcome());
                nullable(update, 3, failure.errorCode());
                update.setLong(4, now.toEpochMilli());
                update.setString(5, registrationId);
                update.executeUpdate();
            }
            if (failure.releaseWorkspaceClaim() && current.workspaceId() != null) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM workspaces WHERE id=? AND lifecycle_state='preparing'")) {
                    delete.setString(1, current.workspaceId());
                    delete.executeUpdate();
                }
            }
            return null;
        });
    }

    @Override
    public Optional<Attempt> find(String registrationId) {
        return database.read("find workspace registration", connection -> findAttempt(connection, registrationId));
    }

    @Override
    public List<Attempt> recoverable(int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_RECOVERY_BATCH));
        return database.read("list recoverable workspace registrations", connection -> {
            List<Attempt> attempts = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM workspace_registration_attempts
                    WHERE state IN ('reserved','switching','checkout_applied','uncertain')
                    ORDER BY updated_at,registration_id LIMIT ?
                    """)) {
                query.setInt(1, bounded);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) attempts.add(mapAttempt(result));
                }
            }
            return List.copyOf(attempts);
        });
    }

    private void transition(String registrationId, String expectedState, String nextState,
                            String outcome, Instant now) {
        database.write("transition workspace registration", connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE workspace_registration_attempts
                    SET state=?,checkout_outcome=?,error_code=NULL,updated_at=?
                    WHERE registration_id=? AND state=?
                    """)) {
                update.setString(1, nextState);
                update.setString(2, outcome);
                update.setLong(3, now.toEpochMilli());
                update.setString(4, registrationId);
                update.setString(5, expectedState);
                if (update.executeUpdate() != 1) {
                    Attempt current = findAttempt(connection, registrationId)
                            .orElseThrow(() -> new WorkspaceRegistrationNotFound(registrationId));
                    throw invalidTransition(registrationId, current.state(), nextState);
                }
            }
            return null;
        });
    }

    private static Optional<Attempt> findAttempt(Connection connection, String registrationId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM workspace_registration_attempts WHERE registration_id=?")) {
            query.setString(1, registrationId);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(mapAttempt(result)) : Optional.empty();
            }
        }
    }

    private static void pruneTerminalAttempts(Connection connection) throws SQLException {
        try (PreparedStatement prune = connection.prepareStatement("""
                DELETE FROM workspace_registration_attempts
                WHERE registration_id IN (
                    SELECT registration_id FROM workspace_registration_attempts
                    WHERE state IN ('completed','failed')
                    ORDER BY updated_at,registration_id
                    LIMIT (
                        SELECT CASE WHEN COUNT(*) > ? THEN COUNT(*) - ? ELSE 0 END
                        FROM workspace_registration_attempts
                    )
                )
                """)) {
            prune.setInt(1, MAX_RETAINED_ATTEMPTS - 1);
            prune.setInt(2, MAX_RETAINED_ATTEMPTS - 1);
            prune.executeUpdate();
        }
    }

    private static ExistingWorkspace findPathOwner(Connection connection, String path) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id,name,path,created_at,lifecycle_state FROM workspaces
                WHERE canonical_path=? AND deleted_at IS NULL
                  AND canonical_path_owner=1
                ORDER BY CASE lifecycle_state WHEN 'active' THEN 0 ELSE 1 END,created_at,id
                LIMIT 1
                """)) {
            query.setString(1, path);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new ExistingWorkspace(mapWorkspace(result), result.getString("lifecycle_state"));
            }
        }
    }

    private static Optional<Workspace> findWorkspace(Connection connection, String workspaceId)
            throws SQLException {
        if (workspaceId == null) return Optional.empty();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,name,path,created_at FROM workspaces WHERE id=? AND deleted_at IS NULL")) {
            query.setString(1, workspaceId);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(mapWorkspace(result)) : Optional.empty();
            }
        }
    }

    private static Workspace mapWorkspace(ResultSet result) throws SQLException {
        return new Workspace(WorkspaceId.parse(result.getString("id")),
                new WorkspaceName(WorkspaceInputLimits.boundedName(result.getString("name"))),
                new WorkspacePath(result.getString("path")),
                Instant.ofEpochMilli(result.getLong("created_at")));
    }

    private static Attempt mapAttempt(ResultSet result) throws SQLException {
        return new Attempt(
                result.getString("registration_id"), result.getString("workspace_id"),
                result.getString("request_hash"), result.getString("canonical_path"),
                result.getString("state"), result.getString("checkout_outcome"),
                result.getString("observed_head_kind"), result.getString("observed_branch"),
                result.getString("observed_head_oid"),
                result.getString("error_code"),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")));
    }

    private static void nullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static WorkspaceRegistrationConflict invalidTransition(
            String registrationId, String current, String target) {
        return new WorkspaceRegistrationConflict(
                "WORKSPACE_REGISTRATION_STATE_CHANGED",
                "Workspace registration " + registrationId + " is " + current
                        + " and cannot transition to " + target,
                null);
    }

    private record ExistingWorkspace(Workspace workspace, String lifecycleState) { }
}
