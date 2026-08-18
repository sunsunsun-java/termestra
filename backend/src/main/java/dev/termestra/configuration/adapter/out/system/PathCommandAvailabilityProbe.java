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
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Map<String, String> environment = new LinkedHashMap<>(System.getenv());
            for (Map.Entry<String, String> entry : Objects.requireNonNullElse(presetEnvironment, Map.<String,String>of()).entrySet()) {
                if (windows) environment.keySet().removeIf(key -> key.equalsIgnoreCase(entry.getKey()));
                environment.put(entry.getKey(), entry.getValue());
            }
            if (command.contains("/") || command.contains("\\")) {
                Path path = Path.of(command);
                if (!path.isAbsolute()) path = Path.of(System.getProperty("user.dir")).resolve(path);
                return candidates(path, environment, windows).stream().anyMatch(candidate -> executable(candidate, windows));
            }
            String pathValue = value(environment, "PATH", windows);
            if (pathValue == null) return false;
            for (String directory : pathValue.split(Pattern.quote(File.pathSeparator))) {
                if (directory.isBlank()) continue;
                for (Path candidate : candidates(Path.of(directory, command), environment, windows)) {
                    if (executable(candidate, windows)) return true;
                }
            }
            return false;
        } catch (InvalidPathException invalidPath) {
            return false;
        }
    }

    private static List<Path> candidates(Path path, Map<String, String> environment, boolean windows) {
        Path fileName = path.getFileName();
        if (!windows || fileName == null || fileName.toString().contains(".")) return List.of(path);
        String extensions = value(environment, "PATHEXT", true);
        if (extensions == null || extensions.isBlank()) extensions = ".COM;.EXE;.BAT;.CMD";
        List<Path> candidates = new ArrayList<>();
        for (String extension : extensions.split(";")) {
            if (!extension.isBlank()) candidates.add(Path.of(path + extension));
        }
        candidates.add(path);
        return candidates;
    }

    private static boolean executable(Path path, boolean windows) {
        return windows ? Files.isRegularFile(path) : Files.isExecutable(path);
    }

    private static String value(Map<String, String> environment, String key, boolean caseInsensitive) {
        if (!caseInsensitive) return environment.get(key);
        return environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
