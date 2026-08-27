package dev.termestra.team.adapter.out.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.configuration.application.port.in.*;
import dev.termestra.configuration.domain.model.CommandPreset;
import dev.termestra.execution.application.port.in.*;
import dev.termestra.team.application.port.out.TeamScenarioRuntime;
import dev.termestra.team.application.port.out.WorkerLaunchPlan;

import java.util.*;

/** Bridges the team formation use case to execution and configuration contexts. */
public final class ExecutionTeamScenarioRuntime implements TeamScenarioRuntime {
    private static final List<String> BUILTIN_ORDER = List.of(
            "claude", "codex", "opencode", "gemini", "hermes", "qwen", "pi", "agy", "cursor", "grok");

    private final AgentLaunchConfigurationQuery configurations;
    private final AgentExecutionUseCase execution;
    private final AgentMessagingUseCase messaging;
    private final ConfigurationUseCase settings;
    private final CommandAvailabilityUseCase availability;
    private final ObjectMapper json;

    public ExecutionTeamScenarioRuntime(AgentLaunchConfigurationQuery configurations,
                                        AgentExecutionUseCase execution,
                                        AgentMessagingUseCase messaging,
                                        ConfigurationUseCase settings,
                                        CommandAvailabilityUseCase availability,
                                        ObjectMapper json) {
        this.configurations = configurations;
        this.execution = execution;
        this.messaging = messaging;
        this.settings = settings;
        this.availability = availability;
        this.json = json;
    }

    @Override public boolean hasActiveOrchestrator(String workspaceId) {
        String orchestratorId = workspaceId + ":orchestrator";
        return execution.listActiveSummaries(workspaceId).stream().anyMatch(run -> run.agentId().equals(orchestratorId));
    }

    @Override public String resolveAndStoreLocale(String workspaceId, String requestedLocale) {
        String key = "workspace." + workspaceId + ".ui_language";
        String locale = supportedLocale(requestedLocale) ? requestedLocale
                : settings.appState(key).filter(ExecutionTeamScenarioRuntime::supportedLocale).orElse("en");
        settings.setAppState(key, locale);
        return locale;
    }

    @Override public WorkerLaunchPlan resolveDefaultWorkerLaunch(String workspaceId) {
        List<CommandPreset> presets = settings.commandPresets();
        Map<String, CommandPreset> byId = new LinkedHashMap<>();
        presets.forEach(preset -> byId.put(preset.id(), preset));
        AgentLaunchConfigurationView orchestrator = configurations
                .find(workspaceId, workspaceId + ":orchestrator").orElse(null);
        String inheritedId = inheritedPresetId(orchestrator, presets);
        CommandPreset inherited = available(byId.get(inheritedId));
        if (orchestrator != null && orchestrator.commandPresetId()!=null
                && !orchestrator.commandPresetId().isBlank()) {
            return new WorkerLaunchPlan(orchestrator.command(),orchestrator.arguments(),
                    orchestrator.commandPresetId(),orchestrator.resumeArgsTemplate(),
                    orchestrator.sessionIdCaptureJson(),orchestrator.environment(),orchestrator.modelId(),
                    orchestrator.presetAugmentationDisabled());
        }
        if (inherited != null) return plan(inherited);
        for (String id : BUILTIN_ORDER) {
            CommandPreset candidate = available(byId.get(id));
            if (candidate != null) return plan(candidate);
        }
        CommandPreset claude = byId.get("claude");
        return claude == null
                ? new WorkerLaunchPlan("claude", List.of(), null, null, null,Map.of(),null,true)
                : plan(claude);
    }

    @Override public StartedRun startWorker(String workspaceId, String workerId, String runtimePort) {
        AgentRunView run = execution.start(new StartAgentCommand(workspaceId, workerId, runtimePort));
        return new StartedRun(run.runId(), run.status());
    }

    @Override public DeliveryResult deliverUserInput(String workspaceId, String text) {
        MessageDeliveryResult result = messaging.userInput(workspaceId, text);
        return new DeliveryResult(result.delivered(), result.error());
    }

    private CommandPreset available(CommandPreset preset) {
        return preset != null && availability.available(preset) ? preset : null;
    }

    private WorkerLaunchPlan plan(CommandPreset preset) {
        return new WorkerLaunchPlan(preset.command(), prependUnique(preset.yoloArgsTemplate(),preset.arguments()),
                preset.id(),preset.resumeArgsTemplate(),capture(preset),preset.environment(),null,true);
    }

    private static List<String> prependUnique(List<String> prefix,List<String> arguments){
        if(prefix==null||prefix.isEmpty())return List.copyOf(arguments);
        List<String> result=new ArrayList<>(prefix);Set<String> prefixValues=new HashSet<>(prefix);
        for(String argument:arguments)if(!prefixValues.contains(argument))result.add(argument);
        return List.copyOf(result);
    }

    private String capture(CommandPreset preset) {
        if (preset.sessionIdCapture() == null) return null;
        try { return json.writeValueAsString(preset.sessionIdCapture()); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid session capture configuration", failure);
        }
    }

    private static String inheritedPresetId(AgentLaunchConfigurationView configuration,
                                             List<CommandPreset> presets) {
        if (configuration == null) return null;
        if (configuration.commandPresetId() != null && !configuration.commandPresetId().isBlank()) {
            return configuration.commandPresetId();
        }
        String brand = normalizeExecutable(Objects.requireNonNullElse(
                configuration.interactiveCommand(), configuration.command()));
        if (brand == null) return null;
        return presets.stream().filter(CommandPreset::builtin)
                .filter(preset -> normalizeExecutable(preset.command()).equals(brand))
                .map(CommandPreset::id).findFirst().orElse(brand);
    }

    private static String normalizeExecutable(String command) {
        if (command == null || command.isBlank()) return null;
        String trimmed = command.trim();
        String token;
        if (trimmed.startsWith("\"")) {
            int closing = trimmed.indexOf('"', 1);
            token = closing > 1 ? trimmed.substring(1, closing) : trimmed.substring(1);
        } else if (trimmed.startsWith("'")) {
            int closing = trimmed.indexOf('\'', 1);
            token = closing > 1 ? trimmed.substring(1, closing) : trimmed.substring(1);
        } else {
            int whitespace = -1;
            for (int index = 0; index < trimmed.length(); index++) {
                if (Character.isWhitespace(trimmed.charAt(index))) { whitespace = index; break; }
            }
            token = whitespace < 0 ? trimmed : trimmed.substring(0, whitespace);
        }
        String fileName = token.replace('\\', '/');
        int separator = fileName.lastIndexOf('/');
        if (separator >= 0) fileName = fileName.substring(separator + 1);
        return fileName.toLowerCase(Locale.ROOT).replaceFirst("\\.(?:cmd|bat|exe|ps1)$", "");
    }

    private static boolean supportedLocale(String locale) {
        return "en".equals(locale) || "zh".equals(locale);
    }
}
