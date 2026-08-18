package dev.termestra.configuration.application.port.in;

import dev.termestra.configuration.domain.model.CommandPreset;

@FunctionalInterface
public interface CommandAvailabilityUseCase {
    boolean available(CommandPreset preset);
}
