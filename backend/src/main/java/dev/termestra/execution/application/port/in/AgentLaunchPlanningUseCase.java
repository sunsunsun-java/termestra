package dev.termestra.execution.application.port.in;

/** Resolves a launch intent into the frozen configuration that persistence will store. */
@FunctionalInterface
public interface AgentLaunchPlanningUseCase {
    AgentLaunchConfigurationView plan(LaunchSource source);
}
