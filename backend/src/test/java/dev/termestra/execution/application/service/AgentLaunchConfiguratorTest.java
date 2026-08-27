package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.execution.application.port.in.ConfigureAgentLaunchCommand;
import dev.termestra.execution.application.port.in.LaunchSource;
import dev.termestra.execution.application.port.out.AgentExecutionRepository;
import dev.termestra.execution.application.port.out.LaunchPresetCatalog;
import dev.termestra.execution.application.port.out.LaunchPresetDescriptor;
import dev.termestra.execution.application.port.out.ShellCommandResolver;
import dev.termestra.execution.domain.model.AgentLaunchConfiguration;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLaunchConfiguratorTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"),ZoneOffset.UTC);

    @Test void expandsAndPersistsAnExplicitModelFromTheSelectedPreset(){
        AgentExecutionRepository repository=mock(AgentExecutionRepository.class);
        LaunchPresetCatalog presets=mock(LaunchPresetCatalog.class);
        LaunchPresetDescriptor codex=new LaunchPresetDescriptor("codex","Codex","codex",List.of("--quiet"),
                Map.of("MODE","safe"),null,null,List.of("--full-auto"),List.of("--model","{model_id}"),
                List.of("gpt-test"),false,true,7);
        when(presets.require("codex")).thenReturn(codex);
        ArgumentCaptor<AgentLaunchConfiguration> saved=ArgumentCaptor.forClass(AgentLaunchConfiguration.class);
        when(repository.saveConfiguration(eq("workspace"),eq("worker"),saved.capture(),any())).thenReturn(true);
        AgentLaunchConfigurator configurator=new AgentLaunchConfigurator(repository,presets,shells(),CLOCK,
                new RuntimeOperationCoordinator());

        configurator.configure(new ConfigureAgentLaunchCommand("workspace","worker",
                new LaunchSource.Preset("codex","gpt-test",7L)));

        assertEquals(List.of("--full-auto","--quiet","--model","gpt-test"),saved.getValue().arguments());
        assertTrue(saved.getValue().presetAugmentationDisabled());
        assertEquals("gpt-test",saved.getValue().modelId());
    }

    @Test void explicitModelReplacesAConflictingPresetModelArgument(){
        AgentExecutionRepository repository=mock(AgentExecutionRepository.class);
        LaunchPresetCatalog presets=mock(LaunchPresetCatalog.class);
        when(presets.require("codex")).thenReturn(new LaunchPresetDescriptor("codex","Codex","codex",
                List.of("--model","old","--quiet"),Map.of(),null,null,
                List.of("--model","yolo-old","--full-auto"),
                List.of("--model","{model_id}"),List.of(),true,true,1));
        ArgumentCaptor<AgentLaunchConfiguration> saved=ArgumentCaptor.forClass(AgentLaunchConfiguration.class);
        when(repository.saveConfiguration(any(),any(),saved.capture(),any())).thenReturn(true);
        AgentLaunchConfigurator configurator=new AgentLaunchConfigurator(repository,presets,shells(),CLOCK,
                new RuntimeOperationCoordinator());

        configurator.configure(new ConfigureAgentLaunchCommand("workspace","worker",
                new LaunchSource.Preset("codex","new",1L)));

        assertEquals(List.of("--full-auto","--quiet","--model","new"),saved.getValue().arguments());
    }

    @Test void rejectsAStaleOrUnavailableOrchestratorSnapshot(){
        AgentExecutionRepository repository=mock(AgentExecutionRepository.class);
        when(repository.copyConfigurationSnapshot(eq("workspace"),eq("workspace:orchestrator"),
                eq("worker"),eq(3L),any())).thenReturn(Optional.empty());
        AgentLaunchConfigurator configurator=new AgentLaunchConfigurator(repository,mock(LaunchPresetCatalog.class),
                shells(),CLOCK,new RuntimeOperationCoordinator());

        ExecutionConflict failure=assertThrows(ExecutionConflict.class,()->configurator.configure(
                new ConfigureAgentLaunchCommand("workspace","worker",
                        new LaunchSource.Snapshot("workspace:orchestrator",3L))));
        assertTrue(failure.getMessage().startsWith("ORCHESTRATOR_LAUNCH_CHANGED:"));
    }

    private static ShellCommandResolver shells(){
        return startup->new ShellCommandResolver.ShellCommand("/bin/sh",List.of("-ic",startup));
    }
}
