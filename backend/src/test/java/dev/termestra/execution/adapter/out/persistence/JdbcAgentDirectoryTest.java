package dev.termestra.execution.adapter.out.persistence;

import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcAgentDirectoryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void projectsLegacyDisplayTextWithExplicitBounds() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("directory.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("seed oversized directory fields", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace',?, '/tmp',1)");
                 var worker = connection.prepareStatement(
                         "INSERT INTO workers(id,workspace_id,name,description,role,created_at) VALUES('worker','workspace',?,?,?,1)")) {
                workspace.setString(1, "w".repeat(2_000_000));
                workspace.executeUpdate();
                worker.setString(1, "Worker");
                worker.setString(2, "d".repeat(2_000_000));
                worker.setString(3, "coder");
                worker.executeUpdate();
            }
            return null;
        });

        var descriptor = new JdbcAgentDirectory(database).find("workspace", "worker").orElseThrow();

        assertEquals(256, descriptor.workspaceName().length());
        assertEquals("Worker", descriptor.name());
        assertEquals(4_096, descriptor.description().length());
        assertEquals("coder", descriptor.role());
    }

    @Test
    void rejectsLegacyWorkerIdentityInsteadOfLaunchingATruncatedAlias() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("invalid-worker.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("seed invalid worker identity", connection -> {
            try (var statement = connection.createStatement();
                 var worker = connection.prepareStatement(
                         "INSERT INTO workers(id,workspace_id,name,description,role,created_at) VALUES('worker','workspace',?,'','coder',1)")) {
                statement.executeUpdate("INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace','/tmp',1)");
                worker.setString(1, "target" + "x".repeat(2_000_000));
                worker.executeUpdate();
            }
            return null;
        });

        assertThrows(dev.termestra.execution.application.exception.ExecutionConflict.class,
                () -> new JdbcAgentDirectory(database).find("workspace", "worker"));
    }

    @Test
    void rejectsAnOversizedLegacyWorkspacePathInsteadOfLaunchingFromATruncatedPath() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("oversized-path.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("seed oversized workspace path", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace',?,1)")) {
                workspace.setString(1, "/" + "p".repeat(2_000_000));
                workspace.executeUpdate();
            }
            return null;
        });

        assertThrows(dev.termestra.execution.application.exception.ExecutionConflict.class,
                () -> new JdbcAgentDirectory(database).find("workspace", "workspace:orchestrator"));
    }

    @Test
    void doesNotSplitUnicodeDisplayTextAtTheJavaCharacterBoundary() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("unicode-directory.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("seed unicode directory fields", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace',?,'/tmp',1)")) {
                workspace.setString(1, "w".repeat(255) + "😀tail");
                workspace.executeUpdate();
            }
            return null;
        });

        String name = new JdbcAgentDirectory(database)
                .find("workspace", "workspace:orchestrator").orElseThrow().workspaceName();

        assertEquals(255,name.length());
        assertEquals(false,Character.isHighSurrogate(name.charAt(name.length()-1)));
    }
}
