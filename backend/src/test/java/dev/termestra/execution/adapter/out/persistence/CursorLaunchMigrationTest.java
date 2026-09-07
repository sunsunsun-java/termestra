package dev.termestra.execution.adapter.out.persistence;

import dev.termestra.execution.adapter.out.terminal.VtPromptTerminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.application.port.in.*;
import dev.termestra.execution.application.port.out.*;
import dev.termestra.execution.application.service.AgentExecutionService;
import dev.termestra.execution.application.service.AgentLaunchConfigurator;
import dev.termestra.execution.domain.model.AgentLaunchConfiguration;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CursorLaunchMigrationTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest @ValueSource(ints = {32, 33})
    void upgradesOldPresetSnapshotsAndRestartsThemWithoutChangingCustomLaunches(int version) {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("v" + version + ".db"));
        Clock clock = Clock.systemUTC();
        new SqliteSchemaMigrator(database, clock).migrate();
        database.write("seed pre-upgrade Cursor workers", connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("DELETE FROM schema_version WHERE version>" + version);
                statement.execute("UPDATE command_presets SET yolo_args_json='"
                        + (version == 32 ? "[\"--force\"]" : "[\"--force\",\"--trust\"]") + "' WHERE id='cursor'");
                statement.execute("INSERT INTO workspaces(id,name,path,created_at) VALUES('workspace','Workspace','/tmp',1)");
                for (String worker : List.of("default", "model", "custom", "startup", "trusted", "custom-command")) {
                    statement.execute("INSERT INTO workers(id,workspace_id,name,role,description,created_at) VALUES('"
                            + worker + "','workspace','" + worker + "','coder','Test',1)");
                }
            }
            return null;
        });
        var repository = new JdbcAgentExecutionRepository(database, new ObjectMapper());
        LaunchPresetCatalog presets = mock(LaunchPresetCatalog.class);
        when(presets.require("cursor")).thenReturn(new LaunchPresetDescriptor("cursor", "Cursor CLI", "cursor-agent",
                List.of(), Map.of(), null, null, List.of("--force"), List.of("--model", "{model_id}"),
                List.of(), true, true, 1));
        var configurator = new AgentLaunchConfigurator(repository, presets,
                text -> new ShellCommandResolver.ShellCommand("/bin/sh", List.of("-c", text)), clock,
                new RuntimeOperationCoordinator());
        configurator.configure(new ConfigureAgentLaunchCommand("workspace", "default", new LaunchSource.Preset("cursor", null, null)));
        configurator.configure(new ConfigureAgentLaunchCommand("workspace", "model", new LaunchSource.Preset("cursor", "model-\"quoted\"", null)));
        configurator.configure(new ConfigureAgentLaunchCommand("workspace", "startup",
                new LaunchSource.Startup("cursor-agent --force", "cursor", true)));
        repository.saveConfiguration("workspace", "custom", new AgentLaunchConfiguration("cursor-agent",
                List.of("--force", "--plan"), "cursor", null, true, null, null), Instant.now(clock));
        repository.saveConfiguration("workspace", "trusted", new AgentLaunchConfiguration("cursor-agent",
                List.of("--force", "--trust"), "cursor", null, true, null, null), Instant.now(clock));
        repository.saveConfiguration("workspace", "custom-command", new AgentLaunchConfiguration("/opt/custom/cursor-agent",
                List.of("--force"), "cursor", null, true, null, null), Instant.now(clock));
        var customCommand = repository.findConfiguration("workspace", "custom-command").orElseThrow();
        var custom = repository.findConfiguration("workspace", "custom").orElseThrow();
        var startup = repository.findConfiguration("workspace", "startup").orElseThrow();
        var trusted = repository.findConfiguration("workspace", "trusted").orElseThrow();

        new SqliteSchemaMigrator(database, clock).migrate();
        new SqliteSchemaMigrator(database, clock).migrate();

        var defaultLaunch = repository.findConfiguration("workspace", "default").orElseThrow();
        var modelLaunch = repository.findConfiguration("workspace", "model").orElseThrow();
        assertEquals(List.of("--force", "--trust"), defaultLaunch.arguments());
        assertEquals(List.of("--force", "--model", "model-\"quoted\"", "--trust"), modelLaunch.arguments());
        assertEquals(2, defaultLaunch.revision());
        assertEquals(2, modelLaunch.revision());
        assertEquals("model-\"quoted\"", modelLaunch.modelId());
        assertTrue(defaultLaunch.presetAugmentationDisabled());
        assertEquals(customCommand, repository.findConfiguration("workspace", "custom-command").orElseThrow());
        assertEquals(custom, repository.findConfiguration("workspace", "custom").orElseThrow());
        assertEquals(startup, repository.findConfiguration("workspace", "startup").orElseThrow());
        assertEquals(trusted, repository.findConfiguration("workspace", "trusted").orElseThrow());
        assertEquals(List.of("cursor-agent", "--force", "--trust"), restartCommand(repository));
    }

    @Test void preservesOldSnapshotsWhenTheBuiltinCursorPolicyWasCustomized() {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("custom-policy.db"));
        Clock clock = Clock.systemUTC();
        new SqliteSchemaMigrator(database, clock).migrate();
        database.write("seed customized Cursor policy", connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("DELETE FROM schema_version WHERE version>33");
                statement.execute("UPDATE command_presets SET yolo_args_json='[]' WHERE id='cursor'");
                statement.execute("INSERT INTO agent_launch_configs(workspace_id,agent_id,command,args_json,command_preset_id,preset_augmentation_disabled,created_at,updated_at) VALUES('workspace','worker','cursor-agent','[\"--force\"]','cursor',1,1,1)");
            }
            return null;
        });
        var repository = new JdbcAgentExecutionRepository(database, new ObjectMapper());
        var before = repository.findConfiguration("workspace", "worker").orElseThrow();
        new SqliteSchemaMigrator(database, clock).migrate();
        assertEquals(before, repository.findConfiguration("workspace", "worker").orElseThrow());
    }

    private static List<String> restartCommand(JdbcAgentExecutionRepository repository) {
        AgentDescriptor agent = new AgentDescriptor("workspace", "Workspace", "/tmp", "default", "Worker", "Test", "coder");
        AgentCredentialIssuer credentials = mock(AgentCredentialIssuer.class);
        when(credentials.issue("default")).thenReturn("test-token");
        AgentSessionCapture sessions = mock(AgentSessionCapture.class);
        when(sessions.snapshot(any(), any())).thenReturn(Optional.empty());
        PseudoTerminalHandle pty = mock(PseudoTerminalHandle.class);
        AtomicBoolean alive = new AtomicBoolean(true);
        when(pty.alive()).thenAnswer(ignored -> alive.get());
        doAnswer(call -> {
            call.<Consumer<byte[]>>getArgument(0).accept("  → Plan, search, build anything".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(pty).activate(any(), any(), any());
        when(pty.stopAndConfirm()).thenAnswer(ignored -> { alive.set(false); return true; });
        AtomicReference<ProcessLaunchRequest> launched = new AtomicReference<>();
        try (var execution = new AgentExecutionService(repository, (workspace, worker) -> Optional.of(agent), credentials,
                request -> { launched.set(request); return pty; }, sessions,
                (preset, command) -> List.of("--force", "--trust"), mock(AgentRecoveryContextProvider.class),
                VtPromptTerminal::new, Clock.systemUTC(), new RuntimeOperationCoordinator())) {
            var run = execution.start(new StartAgentCommand("workspace", "default", "4010"));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!"running".equals(execution.get(run.runId()).status()) && System.nanoTime() < deadline) {
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }
            assertEquals("running", execution.get(run.runId()).status());
            return launched.get().command();
        }
    }
}
