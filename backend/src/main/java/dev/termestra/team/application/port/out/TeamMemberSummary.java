package dev.termestra.team.application.port.out;

/** Durable, bounded member data used to build the team status projection. */
public record TeamMemberSummary(
        String id,
        String name,
        String role,
        String commandPresetId) { }
