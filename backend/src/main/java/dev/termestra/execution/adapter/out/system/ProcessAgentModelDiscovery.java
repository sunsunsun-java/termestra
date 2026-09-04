package dev.termestra.execution.adapter.out.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.application.port.out.AgentModelDiscovery;
import dev.termestra.platform.process.BoundedProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/** Runs only the fixed, non-interactive model-list contracts of known built-in CLIs. */
public final class ProcessAgentModelDiscovery implements AgentModelDiscovery {
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_OUTPUT_BYTES = 256 * 1_024;
    private static final int MAX_CACHE_ENTRIES = 64;
    static final int MAX_CONCURRENT_DISCOVERIES = 4;
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private static final Pattern MODEL_COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern PI_CONTEXT = Pattern.compile("[0-9][A-Za-z0-9._-]*");
    private static final String CODEX_REQUESTS = """
            {"id":1,"method":"initialize","params":{"clientInfo":{"name":"termestra","version":"1"}}}
            {"id":2,"method":"model/list","params":{"includeHidden":false,"limit":256}}
            """;
    private static final Map<String, ProviderSpec> PROVIDERS = Map.of(
            "codex", new ProviderSpec(List.of("app-server", "--listen", "stdio://"), CODEX_REQUESTS,
                    ProcessAgentModelDiscovery::parseCodex),
            "cursor", new ProviderSpec(List.of("models"), null,
                    (discovery, output) -> parseCursor(output)),
            "opencode", new ProviderSpec(List.of("models"), null,
                    (discovery, output) -> parseOpenCode(output)),
            "pi", new ProviderSpec(List.of("--list-models"), null,
                    (discovery, output) -> parsePi(output)));

    private final ObjectMapper json;
    private final ProcessRunner processes;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<CacheKey, CompletableFuture<List<String>>> inFlight = new ConcurrentHashMap<>();
    private final Semaphore concurrency;

    public ProcessAgentModelDiscovery(ObjectMapper json) {
        this(json, new BoundedProcessRunner()::run);
    }

    ProcessAgentModelDiscovery(ObjectMapper json, ProcessRunner processes) {
        this(json, processes, MAX_CONCURRENT_DISCOVERIES);
    }

    ProcessAgentModelDiscovery(ObjectMapper json, ProcessRunner processes, int maximumConcurrency) {
        this.json = json;
        this.processes = processes;
        if (maximumConcurrency < 1) throw new IllegalArgumentException("maximumConcurrency must be positive");
        this.concurrency = new Semaphore(maximumConcurrency);
    }

    @Override public List<String> discover(String presetId, String command, String workspacePath) {
        ProviderSpec provider = PROVIDERS.get(presetId);
        if (provider == null) return List.of();
        CacheKey key = new CacheKey(presetId, command, workspacePath);
        long now = System.nanoTime();
        synchronized (cache) {
            CacheEntry found = cache.get(key);
            if (found != null && now - found.createdAtNanos() < CACHE_TTL.toNanos()) {
                return found.models();
            }
        }
        CompletableFuture<List<String>> owned = new CompletableFuture<>();
        CompletableFuture<List<String>> existing = inFlight.putIfAbsent(key, owned);
        if (existing != null) return existing.join();
        boolean acquired = concurrency.tryAcquire();
        if (!acquired) {
            owned.complete(List.of());
            inFlight.remove(key, owned);
            return List.of();
        }
        try {
            List<String> models = discoverOnce(provider, command, workspacePath);
            if (!models.isEmpty()) {
                synchronized (cache) {
                    cache.put(key, new CacheEntry(models, System.nanoTime()));
                    while (cache.size() > MAX_CACHE_ENTRIES) {
                        cache.remove(cache.keySet().iterator().next());
                    }
                }
            }
            owned.complete(models);
            return models;
        } catch (RuntimeException | Error failure) {
            owned.completeExceptionally(failure);
            throw failure;
        } finally {
            concurrency.release();
            inFlight.remove(key, owned);
        }
    }

    private List<String> discoverOnce(ProviderSpec provider, String command, String workspacePath) {
        try {
            List<String> invocation = new ArrayList<>(provider.arguments().size() + 1);
            invocation.add(command);
            invocation.addAll(provider.arguments());
            BoundedProcessRunner.Result result = processes.run(invocation, Path.of(workspacePath), provider.input(),
                    TIMEOUT, MAX_OUTPUT_BYTES);
            if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) return List.of();
            return List.copyOf(provider.parser().parse(this, result.output()));
        } catch (IOException | RuntimeException failure) {
            return List.of();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private List<String> parseCodex(String output) {
        List<String> models = new ArrayList<>();
        for (String line : output.lines().toList()) {
            if (!line.startsWith("{")) continue;
            try {
                JsonNode response = json.readTree(line);
                if (response.path("id").asInt(-1) != 2) continue;
                for (JsonNode model : response.path("result").path("data")) {
                    if (!model.path("hidden").asBoolean(false)) add(models, model.path("model").asText());
                }
            } catch (IOException ignored) {
                // Ignore diagnostic lines that happen to begin with a brace.
            }
        }
        return models;
    }

    private static List<String> parseCursor(String output) {
        List<String> models = new ArrayList<>();
        boolean tableStarted = false;
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if ("Available models".equalsIgnoreCase(trimmed)) {
                tableStarted = true;
                continue;
            }
            if (!tableStarted) continue;
            int separator = trimmed.indexOf(" - ");
            if (separator > 0) {
                String candidate = trimmed.substring(0, separator);
                if (MODEL_ID.matcher(candidate).matches()) add(models, candidate);
            }
        }
        return models;
    }

    private static List<String> parseOpenCode(String output) {
        List<String> models = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String model = line.trim();
            if (model.contains("/") && MODEL_ID.matcher(model).matches()) add(models, model);
        }
        return models;
    }

    private static List<String> parsePi(String output) {
        List<String> models = new ArrayList<>();
        boolean tableStarted = false;
        for (String line : output.lines().toList()) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length >= 2 && "provider".equalsIgnoreCase(columns[0])
                    && "model".equalsIgnoreCase(columns[1])) {
                tableStarted = true;
                continue;
            }
            if (tableStarted && columns.length >= 3
                    && MODEL_COMPONENT.matcher(columns[0]).matches()
                    && MODEL_COMPONENT.matcher(columns[1]).matches()
                    && PI_CONTEXT.matcher(columns[2]).matches()) {
                add(models, columns[0] + "/" + columns[1]);
            }
        }
        return models;
    }

    private static void add(List<String> models, String model) {
        String candidate = model == null ? "" : model.trim();
        if (!candidate.isEmpty() && candidate.length() <= 128 && candidate.chars().noneMatch(Character::isWhitespace)
                && !models.contains(candidate) && models.size() < 256) models.add(candidate);
    }

    @FunctionalInterface
    interface ProcessRunner {
        BoundedProcessRunner.Result run(List<String> command, Path workingDirectory, String input,
                                        Duration timeout, int maxOutputBytes)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    private interface OutputParser {
        List<String> parse(ProcessAgentModelDiscovery discovery, String output);
    }

    private record ProviderSpec(List<String> arguments, String input, OutputParser parser) {
        private ProviderSpec { arguments = List.copyOf(arguments); }
    }
    private record CacheKey(String presetId, String command, String workspacePath) { }
    private record CacheEntry(List<String> models, long createdAtNanos) {
        private CacheEntry { models = List.copyOf(models); }
    }
}
