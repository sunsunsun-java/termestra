package dev.termestra.execution.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.domain.model.AgentLaunchConfiguration;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.team.adapter.out.persistence.JdbcTeamMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAgentExecutionRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void persistsLaunchEnvironmentAndDoesNotRecreateStateAfterWorkerDeletion() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("execution.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspace = UUID.randomUUID().toString();
        String worker = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        database.write("seed execution agent", connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO workspaces(id,name,path,created_at) VALUES('" + workspace + "','Alpha','/tmp/alpha'," + now + ")");
                statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,description,created_at) VALUES('" + worker + "','" + workspace + "','Alice','coder',''," + now + ")");
            }
            return null;
        });
        JdbcAgentExecutionRepository repository = new JdbcAgentExecutionRepository(database, new ObjectMapper());
        AgentLaunchConfiguration configuration = new AgentLaunchConfiguration(
                "/usr/bin/env", List.of(), "custom", null, false, null, null,
                Map.of("MODEL_ENDPOINT", "local", "MODE", "safe"));

        assertTrue(repository.saveConfiguration(workspace, worker, configuration, Instant.now()));
        assertEquals(configuration.environment(), repository.findConfiguration(workspace, worker).orElseThrow().environment());
        assertTrue(repository.insertRun("old-run",workspace,worker,1,dev.termestra.execution.domain.model.RunStatus.RUNNING,Instant.now()));
        assertTrue(repository.saveLastSession(workspace,worker,"old-run","new-session",Instant.now()));
        assertTrue(repository.finishRun("old-run",dev.termestra.execution.domain.model.RunStatus.ERROR,1,
                Instant.now(),workspace,worker,"old-session"));
        assertEquals("new-session",repository.findLastSession(workspace,worker).orElseThrow());
        assertTrue(repository.insertRun("new-run",workspace,worker,2,dev.termestra.execution.domain.model.RunStatus.RUNNING,Instant.now()));
        assertFalse(repository.saveLastSession(workspace,worker,"old-run","stale-old-session",Instant.now()),
                "a late capture from the completed run must not overwrite the new generation");
        assertEquals("new-session",repository.findLastSession(workspace,worker).orElseThrow());
        assertTrue(repository.finishRun("new-run",dev.termestra.execution.domain.model.RunStatus.ERROR,1,
                Instant.now(),workspace,worker,"new-session"));
        assertTrue(repository.findLastSession(workspace,worker).isEmpty());

        assertTrue(new JdbcTeamMemberRepository(database).delete(workspace, worker));
        assertFalse(repository.saveConfiguration(workspace, worker, configuration, Instant.now()));
        assertFalse(repository.saveLastSession(workspace, worker, "missing-run",
                "session-after-delete", Instant.now()));
        database.read("verify deleted worker state", connection -> {
            try (var statement = connection.createStatement()) {
                try (var configurations = statement.executeQuery("SELECT COUNT(*) FROM agent_launch_configs")) {
                    assertTrue(configurations.next());
                    assertEquals(0, configurations.getInt(1));
                }
                try (var sessions = statement.executeQuery("SELECT COUNT(*) FROM agent_sessions")) {
                    assertTrue(sessions.next());
                    assertEquals(0, sessions.getInt(1));
                }
            }
            return null;
        });
    }

    @Test void copiesTheModelAndFinalArgumentsAsARevisionCheckedSnapshot() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("snapshot.db"));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        String workspace=UUID.randomUUID().toString();String worker=UUID.randomUUID().toString();
        long now=System.currentTimeMillis();
        database.write("seed snapshot agents",connection->{try(var statement=connection.createStatement()){
            statement.executeUpdate("INSERT INTO workspaces(id,name,path,created_at) VALUES('"+workspace+"','Lab','/tmp/lab',"+now+")");
            statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,description,created_at) VALUES('"+worker+"','"+workspace+"','Worker','coder','',"+now+")");
        }return null;});
        JdbcAgentExecutionRepository repository=new JdbcAgentExecutionRepository(database,new ObjectMapper());
        String orchestrator=workspace+":orchestrator";
        AgentLaunchConfiguration source=new AgentLaunchConfiguration("codex",List.of("--model","gpt-test"),
                "codex",null,false,"resume {session_id}",null,Map.of("A","B"),"gpt-test",1);
        assertTrue(repository.saveConfiguration(workspace,orchestrator,source,Instant.now()));

        AgentLaunchConfiguration snapshot=repository.copyConfigurationSnapshot(
                workspace,orchestrator,worker,1L,Instant.now()).orElseThrow();
        assertEquals(List.of("--model","gpt-test"),snapshot.arguments());
        assertEquals("gpt-test",snapshot.modelId());
        assertEquals(Map.of("A","B"),snapshot.environment());
        assertEquals(1,snapshot.revision());

        assertTrue(repository.saveConfiguration(workspace,orchestrator,source,Instant.now()));
        assertTrue(repository.copyConfigurationSnapshot(
                workspace,orchestrator,worker,1L,Instant.now()).isEmpty());
        assertEquals("gpt-test",repository.findConfiguration(workspace,worker).orElseThrow().modelId());
    }
}
