package dev.termestra.team.adapter.out.persistence;

import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.team.application.exception.InvalidTeamMemberRecord;
import dev.termestra.team.application.port.in.TeamInputLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTeamMemberRepositoryTest {
    @TempDir Path tempDirectory;

    @Test void preservesCreationOrderWhenMembersShareTheSameTimestamp() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("members.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        long createdAt = 1_700_000_000_000L;

        database.write("seed equally timed team members", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                workspace.setString(1, workspaceId);
                workspace.setString(2, "Alpha");
                workspace.setString(3, "/tmp/alpha");
                workspace.setLong(4, createdAt);
                workspace.executeUpdate();
            }
            try (var member = connection.prepareStatement(
                    "INSERT INTO workers(id,workspace_id,name,description,role,created_at) VALUES(?,?,?,?,?,?)")) {
                insert(member, "z-member", workspaceId, "Coder", "coder", createdAt);
                insert(member, "a-member", workspaceId, "Reviewer", "reviewer", createdAt);
                insert(member, "m-member", workspaceId, "Tester", "tester", createdAt);
            }
            return null;
        });

        List<String> roles = new JdbcTeamMemberRepository(database).list(workspaceId).stream()
                .map(member -> member.role()).toList();

        assertEquals(List.of("coder", "reviewer", "tester"), roles);
    }

    @Test void boundsLegacyTextInSqlAndRejectsOversizedIdentityFields() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("legacy-bounds.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        String workerId = UUID.randomUUID().toString();
        String oversized = "L".repeat(2 * 1_024 * 1_024);
        long now = System.currentTimeMillis();
        database.write("seed oversized legacy team rows", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                workspace.setString(1, workspaceId);
                workspace.setString(2, "Alpha");
                workspace.setString(3, "/tmp/alpha");
                workspace.setLong(4, now);
                workspace.executeUpdate();
            }
            try (var worker = connection.prepareStatement("""
                    INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                worker.setString(1, workerId);
                worker.setString(2, workspaceId);
                worker.setString(3, oversized);
                worker.setString(4, oversized);
                worker.setString(5, "coder");
                worker.setLong(6, now);
                worker.executeUpdate();

                worker.setString(1, UUID.randomUUID().toString());
                worker.setString(2, workspaceId);
                worker.setString(3, "Invalid role");
                worker.setString(4, "description");
                worker.setString(5, oversized);
                worker.setLong(6, now + 1);
                worker.executeUpdate();
            }
            try (var launch = connection.prepareStatement("""
                    INSERT INTO agent_launch_configs(
                      workspace_id,agent_id,command,args_json,command_preset_id,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                launch.setString(1, workspaceId);
                launch.setString(2, workerId);
                launch.setString(3, "tool");
                launch.setString(4, "[]");
                launch.setString(5, oversized);
                launch.setLong(6, now);
                launch.setLong(7, now);
                launch.executeUpdate();
            }
            return null;
        });

        JdbcTeamMemberRepository repository = new JdbcTeamMemberRepository(database);
        assertTrue(repository.list(workspaceId).isEmpty(),
                "an unaddressable legacy name must not be exposed as a truncated team address");
        assertThrows(InvalidTeamMemberRecord.class, () -> repository.findById(workspaceId, workerId));
        assertThrows(InvalidTeamMemberRecord.class,
                () -> repository.findByName(workspaceId, "Invalid role"));
        assertEquals(oversized.length(), database.<Integer>read("verify legacy value remains intact", connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT length(name) FROM workers WHERE id=?")) {
                statement.setString(1, workerId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }).intValue());
    }

    @Test void neverAliasesAnOversizedLegacyNameToARealWorkerAddress() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("legacy-name-alias.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        String addressableName = "A".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS);
        long now = System.currentTimeMillis();
        database.write("seed colliding legacy team names", connection -> {
            try (var workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                workspace.setString(1, workspaceId);
                workspace.setString(2, "Alpha");
                workspace.setString(3, "/tmp/alpha");
                workspace.setLong(4, now);
                workspace.executeUpdate();
            }
            try (var worker = connection.prepareStatement("""
                    INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                insert(worker, UUID.randomUUID().toString(), workspaceId,
                        addressableName, "coder", now);
                insert(worker, UUID.randomUUID().toString(), workspaceId,
                        addressableName + "-legacy", "coder", now + 1);
            }
            return null;
        });

        JdbcTeamMemberRepository repository = new JdbcTeamMemberRepository(database);

        assertEquals(List.of(addressableName), repository.list(workspaceId).stream()
                .map(member -> member.name()).toList());
        assertEquals(addressableName,
                repository.findByName(workspaceId, addressableName).orElseThrow().name());
    }

    @Test void deletingWithTheWrongWorkspaceDoesNotDeleteAnotherWorkspacesRun() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("delete-owner.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String ownerWorkspace = UUID.randomUUID().toString();
        String otherWorkspace = UUID.randomUUID().toString();
        String workerId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        database.write("seed cross-workspace worker", connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO workspaces(id,name,path,created_at) VALUES('" + ownerWorkspace + "','Owner','/tmp/owner'," + now + ")");
                statement.executeUpdate("INSERT INTO workspaces(id,name,path,created_at) VALUES('" + otherWorkspace + "','Other','/tmp/other'," + now + ")");
                statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,description,created_at) VALUES('" + workerId + "','" + ownerWorkspace + "','Alice','coder',''," + now + ")");
                statement.executeUpdate("INSERT INTO agent_runs(run_id,agent_id,status,started_at,created_at,updated_at) VALUES('run-1','" + workerId + "','running'," + now + "," + now + "," + now + ")");
            }
            return null;
        });

        assertFalse(new JdbcTeamMemberRepository(database).delete(otherWorkspace, workerId));

        database.read("verify cross-workspace graph", connection -> {
            assertEquals(1, count(connection, "workers"));
            assertEquals(1, count(connection, "agent_runs"));
            return null;
        });
    }

    @Test void schemaEnforcesOneActiveWorkerNamePerWorkspaceUnderConcurrentWrites() throws Exception {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("unique-worker.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        database.write("seed workspace", connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                statement.setString(1, workspaceId);
                statement.setString(2, "Alpha");
                statement.setString(3, "/tmp/alpha");
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            return null;
        });
        JdbcTeamMemberRepository repository = new JdbcTeamMemberRepository(database);
        var first = dev.termestra.team.domain.model.TeamMember.create(
                dev.termestra.shared.id.WorkspaceId.parse(workspaceId), "Same", "", dev.termestra.team.domain.model.AgentRole.CODER,
                java.time.Instant.ofEpochMilli(now));
        var second = dev.termestra.team.domain.model.TeamMember.create(
                dev.termestra.shared.id.WorkspaceId.parse(workspaceId), "Same", "", dev.termestra.team.domain.model.AgentRole.CODER,
                java.time.Instant.ofEpochMilli(now));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> left = executor.submit(() -> save(repository, first, ready, start));
            Future<Boolean> right = executor.submit(() -> save(repository, second, ready, start));
            ready.await();
            start.countDown();
            assertEquals(1, List.of(left.get(), right.get()).stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(1, repository.list(workspaceId).size());
    }

    @Test void rejectsTheNextWorkerInsteadOfSilentlyHidingItAtTheCollectionLimit() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("worker-limit.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        database.write("seed bounded worker collection", connection -> {
            try (var workspace = connection.prepareStatement("INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")) {
                workspace.setString(1, workspaceId);workspace.setString(2, "Alpha");workspace.setString(3, "/tmp/alpha");workspace.setLong(4, now);workspace.executeUpdate();
            }
            try (var worker = connection.prepareStatement("INSERT INTO workers(id,workspace_id,name,description,role,created_at) VALUES(?,?,?,?,?,?)")) {
                for (int index = 0; index < dev.termestra.team.application.port.out.TeamMemberRepository.MAX_MEMBERS_PER_WORKSPACE; index++) {
                    insert(worker, UUID.randomUUID().toString(), workspaceId, "Worker " + index, "coder", now + index);
                }
            }
            return null;
        });
        JdbcTeamMemberRepository repository = new JdbcTeamMemberRepository(database);
        var extra = dev.termestra.team.domain.model.TeamMember.create(
                dev.termestra.shared.id.WorkspaceId.parse(workspaceId), "One too many", "",
                dev.termestra.team.domain.model.AgentRole.CODER, java.time.Instant.ofEpochMilli(now + 999));

        var error = assertThrows(dev.termestra.team.application.exception.TeamConflict.class,
                () -> repository.save(extra));

        assertTrue(error.getMessage().contains("limit"));
        assertEquals(dev.termestra.team.application.port.out.TeamMemberRepository.MAX_MEMBERS_PER_WORKSPACE,
                repository.list(workspaceId).size());
    }

    @Test void migrationRenamesLegacyDuplicatesBeforeCreatingTheUniqueIndex() {
        SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("legacy-duplicates.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspaceId = UUID.randomUUID().toString();long now = System.currentTimeMillis();
        database.write("restore pre-v28 duplicate state", connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP INDEX idx_workers_active_workspace_name");
                statement.execute("DELETE FROM schema_version WHERE version>=28");
                statement.executeUpdate("INSERT INTO workspaces(id,name,path,created_at) VALUES('" + workspaceId + "','Alpha','/tmp/alpha'," + now + ")");
                statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,description,created_at) VALUES('a','" + workspaceId + "','Same','coder',''," + now + ")");
                statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,description,created_at) VALUES('b','" + workspaceId + "','Same','coder',''," + (now + 1) + ")");
            }
            return null;
        });

        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();

        assertEquals(List.of("Same", "Same (2)"), new JdbcTeamMemberRepository(database).list(workspaceId).stream()
                .map(member -> member.name()).toList());
    }

    private static boolean save(JdbcTeamMemberRepository repository,
                                dev.termestra.team.domain.model.TeamMember member,
                                CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            repository.save(member);
            return true;
        } catch (dev.termestra.team.application.exception.TeamConflict duplicate) {
            return false;
        }
    }

    private static int count(java.sql.Connection connection, String table) throws java.sql.SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void insert(java.sql.PreparedStatement statement, String id, String workspaceId,
                               String name, String role, long createdAt) throws java.sql.SQLException {
        statement.setString(1, id);
        statement.setString(2, workspaceId);
        statement.setString(3, name);
        statement.setString(4, "");
        statement.setString(5, role);
        statement.setLong(6, createdAt);
        statement.executeUpdate();
    }
}
