package dev.termestra.configuration.application.service;

import dev.termestra.configuration.application.port.in.CommandAvailabilityUseCase;
import dev.termestra.configuration.application.port.out.CommandAvailabilityProbe;
import dev.termestra.configuration.domain.model.CommandPreset;

public final class CommandAvailabilityService implements CommandAvailabilityUseCase {
    private final CommandAvailabilityProbe probe;

    public CommandAvailabilityService(CommandAvailabilityProbe probe) { this.probe = probe; }

    @Override public boolean available(CommandPreset preset) {
        return preset != null && probe.available(preset.command(), preset.environment());
    }
}
