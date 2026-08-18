package dev.termestra.team.application.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;

final class ScenarioMemberNameGenerator {
    private static final String NAME_BANK = "/dev/termestra/team/agent-names.txt";
    private static final int EXPECTED_NAME_COUNT = 1_111;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> NAMES = loadNames();

    private ScenarioMemberNameGenerator() { }

    static String next(String stem, Set<String> usedNames) {
        List<String> available = NAMES.stream().filter(name -> !usedNames.contains(name)).toList();
        List<String> draw = available.isEmpty() ? NAMES : available;
        String candidate = draw.get((int) (Integer.toUnsignedLong(RANDOM.nextInt()) % draw.size()));
        if (!usedNames.contains(candidate)) return candidate;

        String base = candidate.isEmpty() ? stem : candidate;
        for (int attempt = 0; attempt < 16; attempt++) {
            String raw = Integer.toString(RANDOM.nextInt(1 << 24), Character.MAX_RADIX);
            String padded = "0000" + raw;
            String name = base + "-" + padded.substring(padded.length() - 4);
            if (!usedNames.contains(name)) return name;
        }
        throw new IllegalStateException("Could not generate a unique member name for: " + stem);
    }

    static int poolSize() { return NAMES.size(); }

    private static List<String> loadNames() {
        try (InputStream stream = ScenarioMemberNameGenerator.class.getResourceAsStream(NAME_BANK)) {
            if (stream == null) throw new IllegalStateException("Scenario member name bank is missing");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                List<String> names = reader.lines().filter(name -> !name.isBlank()).toList();
                if (names.size() != EXPECTED_NAME_COUNT) {
                    throw new IllegalStateException("Scenario member name bank must contain "
                            + EXPECTED_NAME_COUNT + " names, found " + names.size());
                }
                return names;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not load scenario member name bank", failure);
        }
    }
}
