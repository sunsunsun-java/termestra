package dev.termestra.execution.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.execution.application.port.in.ExecutionInputLimits;
import dev.termestra.execution.application.port.in.StartAgentCommand;
import dev.termestra.execution.application.port.out.AgentCredentialIssuer;
import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider;
import dev.termestra.execution.application.port.out.AgentSessionCapture;
import dev.termestra.execution.application.service.AgentExecutionService;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcAgentExecutionLegacyBoundaryTest {
    private static final String WORKSPACE_ID = "workspace";
    private static final String AGENT_ID = "worker";

    @TempDir Path temporaryDirectory;

    @Test
    void refusesLegacyLaunchConfigurationsBeforeTheyReachThePtyLauncher() throws Exception {
        SqliteDatabase database = database("legacy-config.db");
        JdbcAgentExecutionRepository repository = new JdbcAgentExecutionRepository(database, new ObjectMapper());
        AtomicInteger launches = new AtomicInteger();
        AgentExecutionService service = service(repository, launches);
        ObjectMapper json = new ObjectMapper();

        List<String> tooManyArguments = new ArrayList<>();
        for (int index = 0; index <= ExecutionInputLimits.MAX_ARGUMENTS; index++) {
            tooManyArguments.add("argument-" + index);
        }
        Map<String, String> tooManyEnvironmentEntries = new LinkedHashMap<>();
        for (int index = 0; index <= ExecutionInputLimits.MAX_ENVIRONMENT_ENTRIES; index++) {
            tooManyEnvironmentEntries.put("KEY_" + index, "value");
        }
        List<LegacyConfiguration> invalidConfigurations = List.of(
                new LegacyConfiguration("codex", "[", "{}", null),
                new LegacyConfiguration("codex", json.writeValueAsString(tooManyArguments), "{}", null),
                new LegacyConfiguration("codex", "[]", json.writeValueAsString(tooManyEnvironmentEntries), null),
                new LegacyConfiguration("codex", "[\"" + "x".repeat(3_000_000) + "\"]", "{}", null),
                new LegacyConfiguration("codex", "[]", "{}", "{"),
                new LegacyConfiguration("codex", "[]", "{}", "{} {}"),
                new LegacyConfiguration("c".repeat(3_000_000), "[]", "{}", null));

        try {
            for (LegacyConfiguration invalid : invalidConfigurations) {
                replaceLegacyConfiguration(database, invalid);

                assertThrows(ExecutionConflict.class,
                        () -> service.start(new StartAgentCommand(WORKSPACE_ID, AGENT_ID, "4010")));
                assertEquals(0, launches.get(), "invalid persisted data must fail before PTY launch");
            }
        } finally {
            service.close();
        }
    }

    @Test
    void refusesAnOversizedLegacySessionIdAfterASqlBoundedRead() {
        SqliteDatabase database = database("legacy-session.db");
        database.write("seed oversized session", connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO agent_sessions(workspace_id,agent_id,last_session_id,updated_at) VALUES(?,?,?,1)")) {
                statement.setString(1, WORKSPACE_ID);
                statement.setString(2, AGENT_ID);
                statement.setString(3, "s".repeat(3_000_000));
                statement.executeUpdate();
            }
            return null;
        });

        JdbcAgentExecutionRepository repository = new JdbcAgentExecutionRepository(database, new ObjectMapper());
        assertThrows(ExecutionConflict.class,
                () -> repository.findLastSession(WORKSPACE_ID, AGENT_ID));
    }

    private SqliteDatabase database(String name) {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve(name));
        new SqliteSchemaMigrator(database, Clock.systemUTC()).migrate();
        database.write("seed execution boundary", connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace','/tmp',1)");
                statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,description,created_at) VALUES('worker','workspace','Worker','coder','Implement tasks',1)");
            }
            return null;
        });
        return database;
    }

    private static void replaceLegacyConfiguration(SqliteDatabase database, LegacyConfiguration configuration) {
        database.write("replace legacy launch configuration", connection -> {
            try (var delete = connection.prepareStatement("DELETE FROM agent_launch_configs");
                 var insert = connection.prepareStatement("""
                         INSERT INTO agent_launch_configs(
                           workspace_id,agent_id,command,args_json,command_preset_id,interactive_command,
                           preset_augmentation_disabled,resume_args_template,session_id_capture_json,env_json,
                           created_at,updated_at)
                         VALUES(?,?,?,?,NULL,NULL,0,NULL,?,?,1,1)
                         """)) {
                delete.executeUpdate();
                insert.setString(1, WORKSPACE_ID);
                insert.setString(2, AGENT_ID);
                insert.setString(3, configuration.command());
                insert.setString(4, configuration.argumentsJson());
                insert.setString(5, configuration.captureJson());
                insert.setString(6, configuration.environmentJson());
                insert.executeUpdate();
            }
            return null;
        });
    }

    private static AgentExecutionService service(JdbcAgentExecutionRepository repository,
                                                 AtomicInteger launches) {
        AgentDescriptor descriptor = new AgentDescriptor(
                WORKSPACE_ID, "Workspace", "/tmp", AGENT_ID, "Worker", "Implement tasks", "coder");
        AgentCredentialIssuer credentials = new AgentCredentialIssuer() {
            @Override public String issue(String agentId) { return "token"; }
            @Override public void revoke(String agentId, String token) { }
        };
        AgentSessionCapture sessionCapture = new AgentSessionCapture() {
            @Override public Optional<CaptureSnapshot> snapshot(AgentDescriptor agent, String captureJson) {
                return Optional.empty();
            }
            @Override public Optional<String> findNew(CaptureSnapshot snapshot) { return Optional.empty(); }
            @Override public boolean exists(AgentDescriptor agent, String captureJson, String sessionId) { return false; }
        };
        AgentRecoveryContextProvider recovery = new AgentRecoveryContextProvider() {
            @Override public boolean hasPreviousRun(String agentId, String currentRunId) { return false; }
            @Override public RecoveryContext load(String workspaceId, Instant recentSince) {
                return new RecoveryContext("", List.of(), List.of(), List.of());
            }
            @Override public long appendSystemRecoveryMessage(String workspaceId, String agentId, String text, Instant at) { return 1; }
            @Override public long appendUserInput(String workspaceId, String agentId, String text, Instant at) { return 1; }
            @Override public void deleteMessage(long sequence) { }
        };
        return new AgentExecutionService(repository,
                (workspaceId, agentId) -> workspaceId.equals(WORKSPACE_ID) && agentId.equals(AGENT_ID)
                        ? Optional.of(descriptor) : Optional.empty(),
                credentials,
                request -> {
                    launches.incrementAndGet();
                    throw new AssertionError("PTY launcher must not receive an invalid persisted configuration");
                },
                sessionCapture,
                (presetId, command) -> List.of(),
                recovery,
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
                new RuntimeOperationCoordinator());
    }

    private record LegacyConfiguration(String command, String argumentsJson,
                                       String environmentJson, String captureJson) { }
}
