package dev.termestra.marketplace.adapter.out.classpath;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.marketplace.application.MarketplaceCatalog;
import dev.termestra.marketplace.application.MarketplaceNotFound;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClasspathMarketplaceCatalog implements MarketplaceCatalog {
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_AGENT_BYTES = 256 * 1024;
    private static final int MAX_RESOURCE_PATH_CHARACTERS = 512;

    private final ObjectMapper json;

    public ClasspathMarketplaceCatalog(ObjectMapper json) { this.json = json; }

    @Override public Map<String, Object> manifest(String language) {
        validateLanguage(language);
        try {
            return json.readValue(readBounded(language + "/manifest.json", MAX_MANIFEST_BYTES),
                    new TypeReference<>() { });
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read marketplace manifest", error);
        }
    }

    @Override public AgentDetail agent(String language, String path) {
        validateLanguage(language);
        validateAgentPath(path);
        try {
            String raw = new String(readBounded(language + "/" + path, MAX_AGENT_BYTES),
                    StandardCharsets.UTF_8);
            return parse(path, raw);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read marketplace agent", error);
        }
    }

    private byte[] readBounded(String relative, int maximumBytes) throws IOException {
        try (InputStream input = resource(relative)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new IOException("Marketplace resource exceeds " + maximumBytes + " bytes");
            }
            return bytes;
        }
    }

    private InputStream resource(String relative) {
        InputStream input = ClasspathMarketplaceCatalog.class.getClassLoader()
                .getResourceAsStream("vendor/marketplace/" + relative);
        if (input == null) throw new MarketplaceNotFound("Marketplace resource not found: " + relative);
        return input;
    }

    private static void validateAgentPath(String path) {
        boolean unsafe = path == null || path.length() > MAX_RESOURCE_PATH_CHARACTERS
                || !path.endsWith(".md") || path.startsWith("/") || path.contains("\\")
                || Arrays.stream(path.split("/", -1))
                        .anyMatch(segment -> segment.isBlank() || ".".equals(segment) || "..".equals(segment));
        if (unsafe) throw new MarketplaceNotFound(
                "Marketplace agent path must be a safe .md path: " + path);
    }

    private static void validateLanguage(String language) {
        if (!"en".equals(language) && !"zh".equals(language)) {
            throw new IllegalArgumentException("Invalid or missing lang parameter (expected en|zh)");
        }
    }

    static AgentDetail parse(String path, String raw) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String body = normalized;
        if (normalized.startsWith("---\n")) {
            int end = normalized.indexOf("\n---\n", 4);
            if (end >= 0) {
                for (String line : normalized.substring(4, end).split("\n")) {
                    int separator = line.indexOf(':');
                    if (separator <= 0) continue;
                    String key = line.substring(0, separator).trim();
                    String value = line.substring(separator + 1).trim();
                    frontmatter.put(key, "null".equals(value) ? null : value);
                }
                body = normalized.substring(end + 5);
            }
        }
        return new AgentDetail(path, frontmatter, body);
    }
}
