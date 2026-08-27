package dev.termestra.workspace.adapter.out.persistence;

import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.workspace.application.port.out.WorkspaceRepository;
import dev.termestra.workspace.application.port.in.WorkspaceInputLimits;
import dev.termestra.workspace.application.exception.InvalidWorkspaceRecord;
import dev.termestra.workspace.domain.model.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcWorkspaceRepository implements WorkspaceRepository {
    public static final int MAX_ACTIVE_WORKSPACES = 256;
    private static final int MAX_WORKSPACE_ID_CHARACTERS = 128;
    private final SqliteDatabase database;
    public JdbcWorkspaceRepository(SqliteDatabase database) { this.database = database; }

    @Override public List<Workspace> findAll() {
        return database.read("list workspaces", connection -> {
            List<Workspace> workspaces = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id,substr(name,1,?) AS name,path,created_at
                    FROM workspaces
                    WHERE deleted_at IS NULL AND lifecycle_state='active'
                      AND (canonical_path_owner=1 OR canonical_path IS NULL)
                      AND length(id) BETWEEN 1 AND ?
                      AND length(path) BETWEEN 1 AND ?
                    ORDER BY created_at,id LIMIT ?
                    """)) {
                ps.setInt(1, WorkspaceInputLimits.MAX_NAME_CHARACTERS);
                ps.setInt(2, MAX_WORKSPACE_ID_CHARACTERS);
                ps.setInt(3, WorkspaceInputLimits.MAX_PATH_CHARACTERS);
                ps.setInt(4, MAX_ACTIVE_WORKSPACES);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) workspaces.add(map(rs));
                }
            }
            return List.copyOf(workspaces);
        });
    }
    @Override public Optional<Workspace> find(String workspaceId) {
        if (!boundedId(workspaceId)) return Optional.empty();
        return database.read("find workspace", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id,substr(name,1,?) AS name,
                           CASE WHEN length(path) BETWEEN 1 AND ? THEN path ELSE NULL END AS path,
                           created_at
                    FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active'
                    """)) {
                ps.setInt(1, WorkspaceInputLimits.MAX_NAME_CHARACTERS);
                ps.setInt(2, WorkspaceInputLimits.MAX_PATH_CHARACTERS);
                ps.setString(3, workspaceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(map(rs));
                }
            }
        });
    }

    @Override public Optional<Workspace> findByCanonicalPath(String canonicalPath) {
        if (canonicalPath == null || canonicalPath.isBlank()
                || canonicalPath.length() > WorkspaceInputLimits.MAX_PATH_CHARACTERS) {
            return Optional.empty();
        }
        return database.write("find workspace by canonical path",
                connection -> findByCanonicalPath(connection, canonicalPath));
    }

    private static Optional<Workspace> findByCanonicalPath(java.sql.Connection connection, String canonicalPath)
            throws java.sql.SQLException {
        Workspace workspace;
        boolean owner;
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id,substr(name,1,?) AS name,
                       CASE WHEN length(path) BETWEEN 1 AND ? THEN path ELSE NULL END AS path,
                       created_at,canonical_path_owner
                FROM workspaces
                WHERE canonical_path=? AND deleted_at IS NULL AND lifecycle_state='active'
                  AND length(id) BETWEEN 1 AND ?
                ORDER BY canonical_path_owner DESC,created_at,id
                LIMIT 1
                """)) {
            ps.setInt(1, WorkspaceInputLimits.MAX_NAME_CHARACTERS);
            ps.setInt(2, WorkspaceInputLimits.MAX_PATH_CHARACTERS);
            ps.setString(3, canonicalPath);
            ps.setInt(4, MAX_WORKSPACE_ID_CHARACTERS);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                workspace = map(rs);
                owner = rs.getInt("canonical_path_owner") == 1;
            }
        }
        if (!owner) {
            try (PreparedStatement promote = connection.prepareStatement(
                    "UPDATE workspaces SET canonical_path_owner=1 WHERE id=?")) {
                promote.setString(1, workspace.id().toString());
                promote.executeUpdate();
            }
        }
        return Optional.of(workspace);
    }

    private static Workspace map(ResultSet result) throws java.sql.SQLException {
        String path = result.getString("path");
        if (path == null) throw new InvalidWorkspaceRecord();
        try {
            return new Workspace(WorkspaceId.parse(result.getString("id")),
                    new WorkspaceName(WorkspaceInputLimits.boundedName(result.getString("name"))),
                    new WorkspacePath(path),
                    Instant.ofEpochMilli(result.getLong("created_at")));
        } catch (IllegalArgumentException invalidRecord) {
            throw new InvalidWorkspaceRecord();
        }
    }
    @Override public boolean delete(String workspaceId) {
        if (!boundedId(workspaceId)) return false;
        return database.write("delete workspace", connection -> {
            Optional<CanonicalPathClaim> claim = findCanonicalPathClaim(connection, workspaceId);
            if (claim.isEmpty()) return false;
            execute(connection, "DELETE FROM dispatch_deliveries WHERE workspace_id=?", workspaceId);
            execute(connection, "DELETE FROM dispatches WHERE workspace_id=?", workspaceId);
            execute(connection, "DELETE FROM messages WHERE workspace_id=?", workspaceId);
            execute(connection, "DELETE FROM agent_runs WHERE workspace_id=? OR agent_id IN (SELECT id FROM workers WHERE workspace_id=?) OR agent_id IN (SELECT agent_id FROM agent_launch_configs WHERE workspace_id=?) OR agent_id IN (?,?)", workspaceId, workspaceId, workspaceId, workspaceId + ":orchestrator", workspaceId + ":shell");
            execute(connection, "DELETE FROM agent_launch_configs WHERE workspace_id=?", workspaceId);
            execute(connection, "DELETE FROM agent_sessions WHERE workspace_id=?", workspaceId);
            execute(connection, "DELETE FROM workers WHERE workspace_id=?", workspaceId);
            execute(connection, "UPDATE app_state SET value=NULL,updated_at=? WHERE key='active_workspace_id' AND value=?", System.currentTimeMillis(), workspaceId);
            execute(connection, "DELETE FROM app_state WHERE key=?",
                    "workspace." + workspaceId + ".ui_language");
            boolean deleted = execute(connection, "DELETE FROM workspaces WHERE id=?", workspaceId) == 1;
            CanonicalPathClaim deletedClaim = claim.orElseThrow();
            if (deleted && deletedClaim.owner() && deletedClaim.path() != null) {
                promoteOldestDuplicate(connection, deletedClaim.path());
            }
            return deleted;
        });
    }

    private static Optional<CanonicalPathClaim> findCanonicalPathClaim(
            java.sql.Connection connection, String workspaceId) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT CASE WHEN length(canonical_path) BETWEEN 1 AND ?
                            THEN canonical_path ELSE NULL END AS canonical_path,
                       canonical_path_owner
                FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active'
                """)) {
            statement.setInt(1, WorkspaceInputLimits.MAX_PATH_CHARACTERS);
            statement.setString(2, workspaceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new CanonicalPathClaim(
                        result.getString("canonical_path"), result.getInt("canonical_path_owner") == 1));
            }
        }
    }

    private static void promoteOldestDuplicate(java.sql.Connection connection, String canonicalPath)
            throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE workspaces SET canonical_path_owner=1
                WHERE id=(
                    SELECT id FROM workspaces
                    WHERE canonical_path=? AND deleted_at IS NULL AND lifecycle_state='active'
                    ORDER BY created_at,id
                    LIMIT 1
                )
                """)) {
            statement.setString(1, canonicalPath);
            statement.executeUpdate();
        }
    }

    private record CanonicalPathClaim(String path, boolean owner) { }

    private static boolean boundedId(String workspaceId) {
        return workspaceId != null && !workspaceId.isBlank()
                && workspaceId.length() <= MAX_WORKSPACE_ID_CHARACTERS;
    }

    private static int execute(java.sql.Connection connection, String sql, Object... values) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement.executeUpdate();
        }
    }
}
