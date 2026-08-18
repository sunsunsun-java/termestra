package dev.termestra.configuration.application.port.out;

import java.util.Map;

@FunctionalInterface
public interface CommandAvailabilityProbe {
    boolean available(String command, Map<String, String> presetEnvironment);
}
