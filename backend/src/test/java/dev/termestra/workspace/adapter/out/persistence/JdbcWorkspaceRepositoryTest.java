package dev.termestra.workspace.adapter.out.persistence;

import dev.termestra.platform.persistence.sqlite.*;
import dev.termestra.workspace.domain.model.*;
import dev.termestra.workspace.application.exception.InvalidWorkspaceRecord;
import dev.termestra.workspace.application.port.in.WorkspaceInputLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class JdbcWorkspaceRepositoryTest {
    @TempDir Path tempDirectory;

    @Test void persistsAndReloadsWorkspacesInStableCreationOrder() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("workspaces.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        JdbcWorkspaceRepository repository = new JdbcWorkspaceRepository(database);
        Instant now = Instant.parse("2026-08-06T00:00:00Z");

        Workspace alpha = Workspace.create(new WorkspaceName("Alpha"), new WorkspacePath("/tmp/alpha"), now);
        Workspace beta = Workspace.create(new WorkspaceName("Beta"), new WorkspacePath("/tmp/beta"), now.plusMillis(1));
        insertWorkspace(database, alpha);
        insertWorkspace(database, beta);

        assertEquals(java.util.List.of(alpha, beta), new JdbcWorkspaceRepository(database).findAll());
    }

    @Test void boundsLegacyNamesInSqlAndIsolatesOversizedPaths() {
        SqliteDatabase database = database("legacy-workspace-bounds.db");
        String validId = java.util.UUID.randomUUID().toString();
        String invalidId = java.util.UUID.randomUUID().toString();
        String oversizedName = "N".repeat(2 * 1_024 * 1_024);
        String oversizedPath = "/" + "p".repeat(2 * 1_024 * 1_024);
        database.write("seed oversized legacy workspace rows", connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO workspaces(id,name,path,created_at,canonical_path,canonical_path_owner)
                    VALUES(?,?,?,?,?,1)
                    """)) {
                statement.setString(1, validId);
                statement.setString(2, oversizedName);
                statement.setString(3, "/tmp/legacy-valid");
                statement.setLong(4, 1);
                statement.setString(5, "/tmp/legacy-valid");
                statement.executeUpdate();
                statement.setString(1, invalidId);
                statement.setString(2, "Invalid path");
                statement.setString(3, oversizedPath);
                statement.setLong(4, 2);
                statement.setString(5, oversizedPath);
                statement.executeUpdate();
            }
            return null;
        });

        JdbcWorkspaceRepository repository = new JdbcWorkspaceRepository(database);
        assertEquals(java.util.List.of(validId), repository.findAll().stream()
                .map(workspace -> workspace.id().toString()).toList());
        assertEquals(WorkspaceInputLimits.MAX_NAME_CHARACTERS,
                repository.find(validId).orElseThrow().name().value().length());
        assertThrows(InvalidWorkspaceRecord.class, () -> repository.find(invalidId));
        assertEquals(oversizedPath.length(), database.<Integer>read("verify legacy path remains intact", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT length(path) FROM workspaces WHERE id=?")) {
                statement.setString(1, invalidId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }).intValue());
    }

    @Test void hidesLegacyPathDuplicatesAndPromotesTheNextOneWhenTheOwnerIsDeleted() {
        SqliteDatabase database = database("legacy-path-owner.db");
        JdbcWorkspaceRepository repository = new JdbcWorkspaceRepository(database);
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        Workspace owner = Workspace.create(new WorkspaceName("Original"), new WorkspacePath("/tmp/alpha"), now);
        Workspace duplicate = Workspace.create(new WorkspaceName("Accidental retry"), new WorkspacePath("/tmp/alpha"), now.plusMillis(1));
        insertWorkspace(database, owner);
        insertLegacyDuplicate(database, duplicate);

        assertEquals(java.util.List.of(owner), repository.findAll());
        assertTrue(repository.delete(owner.id().toString()));
        assertEquals(java.util.List.of(duplicate), repository.findAll());
        assertEquals(1, canonicalOwner(database, duplicate.id().toString()));
    }

    @Test void deletingAHiddenLegacyDuplicateDoesNotChangeTheCanonicalOwner() {
        SqliteDatabase database = database("delete-hidden-duplicate.db");
        JdbcWorkspaceRepository repository = new JdbcWorkspaceRepository(database);
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        Workspace owner = Workspace.create(new WorkspaceName("Original"), new WorkspacePath("/tmp/alpha"), now);
        Workspace duplicate = Workspace.create(new WorkspaceName("Accidental retry"), new WorkspacePath("/tmp/alpha"), now.plusMillis(1));
        insertWorkspace(database, owner);
        insertLegacyDuplicate(database, duplicate);

        assertTrue(repository.delete(duplicate.id().toString()));
        assertEquals(java.util.List.of(owner), repository.findAll());
        assertEquals(1, canonicalOwner(database, owner.id().toString()));
    }

    @Test void hardDeletesTheWorkspaceGraphInOneTransaction() {
        SqliteDatabase database = database("delete.db");
        JdbcWorkspaceRepository repository = new JdbcWorkspaceRepository(database);
        Workspace workspace = Workspace.create(new WorkspaceName("Alpha"), new WorkspacePath("/tmp/alpha"), Instant.now());
        insertWorkspace(database, workspace);
        String id = workspace.id().toString();
        seedWorkspaceGraph(database, id, "worker-1");

        assertTrue(repository.delete(id));
        database.read("verify workspace hard delete", connection -> {
            for (String table : java.util.List.of("workspaces", "workers", "messages", "dispatches", "agent_launch_configs", "agent_sessions", "agent_runs"))
                assertEquals(0, count(connection, table));
            try (var statement = connection.prepareStatement("SELECT value FROM app_state WHERE key='active_workspace_id'"); var result = statement.executeQuery()) {
                assertTrue(result.next()); assertNull(result.getString(1));
            }
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM app_state WHERE key=?")) {
                statement.setString(1, "workspace." + id + ".ui_language");
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertEquals(0, result.getInt(1));
                }
            }
            return null;
        });
    }

    @Test void rollsBackTheWholeGraphWhenWorkspaceDeletionFails() {
        SqliteDatabase database = database("rollback.db");
        JdbcWorkspaceRepository repository = new JdbcWorkspaceRepository(database);
        Workspace workspace = Workspace.create(new WorkspaceName("Alpha"), new WorkspacePath("/tmp/alpha"), Instant.now());
        insertWorkspace(database, workspace);
        String id = workspace.id().toString();
        seedWorkspaceGraph(database, id, "worker-1");
        database.write("install delete blocker", connection -> { try (var statement=connection.createStatement()) {
            statement.execute("CREATE TRIGGER block_workspace_delete BEFORE DELETE ON workspaces BEGIN SELECT RAISE(ABORT, 'blocked workspace delete'); END");
        } return null; });

        assertThrows(SqlitePersistenceException.class, () -> repository.delete(id));
        database.read("verify delete rollback", connection -> {
            assertEquals(1, count(connection,"workspaces")); assertEquals(1,count(connection,"workers"));
            assertEquals(1,count(connection,"messages")); assertEquals(1,count(connection,"dispatches"));
            return null;
        });
    }

    private SqliteDatabase database(String name) {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(name));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        return database;
    }

    private static void insertWorkspace(SqliteDatabase database, Workspace workspace) {
        database.write("seed workspace", connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO workspaces(id,name,path,created_at,canonical_path,canonical_path_owner)
                    VALUES(?,?,?,?,?,1)
                    """)) {
                statement.setString(1, workspace.id().toString());
                statement.setString(2, workspace.name().value());
                statement.setString(3, workspace.path().value());
                statement.setLong(4, workspace.createdAt().toEpochMilli());
                statement.setString(5, workspace.path().value());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static void seedWorkspaceGraph(SqliteDatabase database,String workspace,String worker) {
        database.write("seed workspace graph", connection -> { try (var statement=connection.createStatement()) {
            long now=System.currentTimeMillis();
            statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,created_at,description) VALUES('"+worker+"','"+workspace+"','Alice','coder',"+now+",'')");
            statement.executeUpdate("INSERT INTO messages(workspace_id,worker_id,type,text,artifacts,created_at) VALUES('"+workspace+"','"+worker+"','send','task','[]',"+now+")");
            statement.executeUpdate("INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at,artifacts) VALUES('dispatch-1','"+workspace+"','"+worker+"','task','queued',"+now+",'[]')");
            statement.executeUpdate("INSERT INTO agent_launch_configs(workspace_id,agent_id,command,args_json,created_at,updated_at) VALUES('"+workspace+"','"+worker+"','cat','[]',"+now+","+now+")");
            statement.executeUpdate("INSERT INTO agent_sessions(agent_id,workspace_id,last_session_id,updated_at) VALUES('"+worker+"','"+workspace+"','session-1',"+now+")");
            statement.executeUpdate("INSERT INTO agent_runs(run_id,workspace_id,agent_id,status,started_at,created_at,updated_at) VALUES('run-1','"+workspace+"','"+worker+"','running',"+now+","+now+","+now+")");
            statement.executeUpdate("UPDATE app_state SET value='"+workspace+"' WHERE key='active_workspace_id'");
            statement.executeUpdate("INSERT INTO app_state(key,value,updated_at) VALUES('workspace."+workspace+".ui_language','zh',"+now+")");
        } return null; });
    }

    private static void insertLegacyDuplicate(SqliteDatabase database, Workspace workspace) {
        database.write("insert legacy duplicate", connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO workspaces(id,name,path,created_at,canonical_path,canonical_path_owner)
                    VALUES(?,?,?,?,?,0)
                    """)) {
                statement.setString(1, workspace.id().toString());
                statement.setString(2, workspace.name().value());
                statement.setString(3, workspace.path().value());
                statement.setLong(4, workspace.createdAt().toEpochMilli());
                statement.setString(5, workspace.path().value());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static int canonicalOwner(SqliteDatabase database, String workspaceId) {
        return database.read("read canonical owner", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT canonical_path_owner FROM workspaces WHERE id=?")) {
                statement.setString(1, workspaceId);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getInt(1);
                }
            }
        });
    }

    private static int count(java.sql.Connection connection,String table)throws java.sql.SQLException{try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM "+table)){result.next();return result.getInt(1);}}
}
