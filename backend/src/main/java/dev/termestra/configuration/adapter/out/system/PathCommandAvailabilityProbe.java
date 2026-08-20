package dev.termestra.configuration.adapter.out.system;

import dev.termestra.configuration.application.port.out.CommandAvailabilityProbe;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public final class PathCommandAvailabilityProbe implements CommandAvailabilityProbe {
    @Override public boolean available(String command, Map<String, String> presetEnvironment) {
        if (command == null || command.isBlank()) return false;
        try {
            Map<String, String> environment = new LinkedHashMap<>(System.getenv());
            for (Map.Entry<String, String> entry : Objects.requireNonNullElse(presetEnvironment, Map.<String,String>of()).entrySet()) {
                environment.put(entry.getKey(), entry.getValue());
            }
            if (command.contains("/")) {
                Path path = Path.of(command);
                if (!path.isAbsolute()) path = Path.of(System.getProperty("user.dir")).resolve(path);
                return Files.isExecutable(path);
            }
            String pathValue = environment.get("PATH");
            if (pathValue == null) return false;
            for (String directory : pathValue.split(Pattern.quote(File.pathSeparator))) {
                if (directory.isBlank()) continue;
                if (Files.isExecutable(Path.of(directory, command))) return true;
            }
            return false;
        } catch (InvalidPathException invalidPath) {
            return false;
        }
    }

}
