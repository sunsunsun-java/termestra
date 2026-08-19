package dev.termestra.execution.adapter.out.session;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.application.port.in.ExecutionInputLimits;
import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentSessionCapture;
import dev.termestra.execution.application.port.out.AgentSessionCapture.CaptureSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.time.Duration;
import java.util.stream.Stream;

public final class FilesystemAgentSessionCapture implements AgentSessionCapture {
    static final int MAX_CANDIDATE_FILES = 64;
    static final int MAX_CLAUDE_MATCH_BYTES = 256 * 1024;
    static final int MAX_CODEX_HEADER_BYTES = 64 * 1024;
    static final int MAX_GEMINI_SESSION_BYTES = 64 * 1024;
    static final int MAX_PROJECT_ROOT_BYTES = 4096;
    static final int MAX_SCANNED_PATHS = 4096;
    static final int MAX_OPENCODE_SESSION_INSPECTIONS = 256;
    private static final int MAX_CODEX_WALK_DEPTH = 4;
    private static final long CLAIM_TTL_NANOS = Duration.ofMinutes(2).toNanos();
    private static final Pattern UUID_FILE = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.jsonl");
    private static final Pattern CODEX_FILE = Pattern.compile("(?i)rollout-.*\\.jsonl");
    private static final Pattern GEMINI_FILE = Pattern.compile("(?i)session-.*\\.json");

    private final ObjectMapper json;
    private final Object claimLock = new Object();
    private final Map<SessionKey, Claim> sessionClaims = new HashMap<>();
    private final Map<String, Set<SessionKey>> claimantSessions = new HashMap<>();

    public FilesystemAgentSessionCapture(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Optional<CaptureSnapshot> snapshot(AgentDescriptor agent, String captureJson) {
        Config config = parse(captureJson);
        if (config == null) return Optional.empty();
        String root = root(config);
        Map<String, String> env = environment(config.source(), root);
        return Optional.of(new CaptureSnapshot(agent, config.source(), config.pattern(), root,
                list(agent, config.source(), root), env));
    }

    @Override
    public Optional<String> findNew(CaptureSnapshot snapshot) {
        return newSessions(snapshot).stream().findFirst();
    }

    @Override
    public Optional<String> claimNew(CaptureSnapshot snapshot, String claimantId) {
        if (claimantId == null || claimantId.isBlank()) throw new IllegalArgumentException("claimantId is required");
        List<String> candidates = newSessions(snapshot);
        synchronized (claimLock) {
            purgeExpiredClaims();
            for (String id : candidates) {
                SessionKey key = new SessionKey(snapshot.source(), snapshot.root(),
                        snapshot.agent().workspacePath(), id);
                Claim claim = sessionClaims.get(key);
                if (claim != null && !claim.owner().equals(claimantId)) continue;
                sessionClaims.put(key, new Claim(claimantId, System.nanoTime() + CLAIM_TTL_NANOS));
                claimantSessions.computeIfAbsent(claimantId, ignored -> new HashSet<>()).add(key);
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    @Override
    public void releaseClaims(String claimantId) {
        synchronized (claimLock) {
            Set<SessionKey> keys = claimantSessions.remove(claimantId);
            if (keys == null) return;
            for (SessionKey key : keys) {
                Claim claim=sessionClaims.get(key);
                if(claim!=null&&claim.owner().equals(claimantId))sessionClaims.remove(key);
            }
        }
    }

    private void purgeExpiredClaims(){
        long now=System.nanoTime();
        var expired=sessionClaims.entrySet().stream().filter(entry->entry.getValue().expiresAtNanos()<=now).toList();
        for(var entry:expired){
            sessionClaims.remove(entry.getKey());
            Set<SessionKey> keys=claimantSessions.get(entry.getValue().owner());
            if(keys!=null){keys.remove(entry.getKey());if(keys.isEmpty())claimantSessions.remove(entry.getValue().owner());}
        }
    }

    private List<String> newSessions(CaptureSnapshot snapshot) {
        Set<String> current = list(snapshot.agent(), snapshot.source(), snapshot.root());
        return current.stream()
                .filter(id -> !snapshot.knownSessionIds().contains(id))
                .filter(id -> matchesAgent(snapshot, id))
                .toList();
    }

    @Override
    public boolean exists(AgentDescriptor agent, String captureJson, String sessionId) {
        Config config = parse(captureJson);
        if (config == null || sessionId == null || sessionId.isBlank()) return false;
        String root = root(config);
        return switch (config.source()) {
            case "claude_project_jsonl_dir" -> claudeSessionExists(agent, Path.of(root), sessionId);
            case "codex_session_jsonl_dir" -> codex(agent, Path.of(root), false).contains(sessionId);
            case "gemini_session_json_dir" -> gemini(agent, Path.of(root), false).contains(sessionId);
            case "opencode_session_db" -> openCodeSessionExists(agent, Path.of(root), sessionId);
            default -> false;
        };
    }

    private Set<String> list(AgentDescriptor agent, String source, String root) {
        return switch (source) {
            case "claude_project_jsonl_dir" -> claude(agent, Path.of(root));
            case "codex_session_jsonl_dir" -> codex(agent, Path.of(root), true);
            case "gemini_session_json_dir" -> gemini(agent, Path.of(root), true);
            case "opencode_session_db" -> opencode(agent, Path.of(root));
            default -> Set.of();
        };
    }

    private Set<String> claude(AgentDescriptor agent, Path root) {
        Path directory = root.resolve(encode(agent.workspacePath()));
        try (Stream<Path> files = Files.list(directory)) {
            Set<String> result = new LinkedHashSet<>();
            for (Path path : recentPaths(files,
                    candidate -> Files.isRegularFile(candidate)
                            && UUID_FILE.matcher(candidate.getFileName().toString()).matches(),
                    Function.identity())) {
                String name = path.getFileName().toString();
                result.add(name.substring(0, name.length() - ".jsonl".length()));
            }
            return result;
        } catch (IOException missing) {
            return Set.of();
        }
    }

    private Set<String> codex(AgentDescriptor agent, Path root, boolean requireBinding) {
        Path sessions = root.resolve("sessions");
        if (!Files.isDirectory(sessions)) return Set.of();
        try (Stream<Path> files = Files.walk(sessions, MAX_CODEX_WALK_DEPTH)) {
            Set<String> result = new LinkedHashSet<>();
            for (Path path : recentPaths(files,
                    candidate -> Files.isRegularFile(candidate)
                            && CODEX_FILE.matcher(candidate.getFileName().toString()).matches(),
                    Function.identity())) {
                SessionMetadata metadata = readCodexMetadata(path);
                if (metadata != null && validSessionId(metadata.id())
                        && agent.workspacePath().equals(metadata.cwd())
                        && (!requireBinding || boundedEdgesContain(path, bindingMarkers(agent)))) {
                    result.add(metadata.id());
                }
            }
            return result;
        } catch (IOException missing) {
            return Set.of();
        }
    }

    private Set<String> gemini(AgentDescriptor agent, Path root, boolean requireBinding) {
        Path tmp = root.resolve("tmp");
        if (!Files.isDirectory(tmp)) return Set.of();
        try (Stream<Path> projects = Files.list(tmp)) {
            Set<String> result = new LinkedHashSet<>();
            List<Path> recentProjects = recentPaths(projects,
                    Files::isDirectory,
                    project -> project.resolve("chats"));
            PriorityQueue<FileCandidate> recentFiles = recentQueue();
            ScanBudget chatFileBudget = new ScanBudget();
            for (Path project : recentProjects) {
                if (chatFileBudget.exhausted()) break;
                if (!agent.workspacePath().equals(readBoundedText(project.resolve(".project_root"),
                        MAX_PROJECT_ROOT_BYTES))) continue;
                Path chats = project.resolve("chats");
                if (!Files.isDirectory(chats)) continue;
                try (Stream<Path> files = Files.list(chats)) {
                    collectRecent(files,
                            candidate -> Files.isRegularFile(candidate)
                                    && GEMINI_FILE.matcher(candidate.getFileName().toString()).matches(),
                            Function.identity(), recentFiles, chatFileBudget);
                } catch (IOException ignored) {
                    // A CLI may rotate its chats directory while capture is polling.
                }
            }
            for (Path file : orderedRecent(recentFiles)) {
                String id = readGeminiSessionId(file);
                if (validSessionId(id)
                        && (!requireBinding || boundedEdgesContain(file, bindingMarkers(agent)))) result.add(id);
            }
            return result;
        } catch (IOException missing) {
            return Set.of();
        }
    }

    private Set<String> opencode(AgentDescriptor agent, Path database) {
        if (!Files.isRegularFile(database)) return Set.of();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            List<OpenCodeBindingSource> bindingSources = openCodeBindingSources(connection);
            if (bindingSources.isEmpty()) return Set.of();
            String bindings = bindingSources.stream()
                    .map(source -> "EXISTS (SELECT 1 FROM " + source.table() + " b WHERE b."
                            + source.sessionColumn() + "=s.id AND instr(CAST(b."
                            + source.dataColumn() + " AS TEXT), ?) > 0)")
                    .reduce((left, right) -> left + " OR " + right)
                    .orElseThrow();
            String sql = "SELECT substr(s.id,1,?) FROM (SELECT rowid AS sort_order,id FROM session "
                    + "WHERE directory=? AND time_archived IS NULL ORDER BY rowid DESC LIMIT ?) s WHERE ("
                    + bindings + ") ORDER BY s.sort_order DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, ExecutionInputLimits.MAX_SESSION_ID_CHARACTERS + 1);
                statement.setString(2, agent.workspacePath());
                statement.setInt(3, MAX_OPENCODE_SESSION_INSPECTIONS);
                int parameter = 4;
                for (int ignored = 0; ignored < bindingSources.size(); ignored++) {
                    statement.setString(parameter++, bindingMarker(agent));
                }
                statement.setInt(parameter, MAX_CANDIDATE_FILES);
                try (ResultSet rows = statement.executeQuery()) {
                    Set<String> result = new LinkedHashSet<>();
                    while (rows.next()) {
                        String id=rows.getString(1);
                        if(validSessionId(id))result.add(id);
                    }
                    return result;
                }
            }
        } catch (SQLException unavailable) {
            return Set.of();
        }
    }

    private List<OpenCodeBindingSource> openCodeBindingSources(Connection connection) throws SQLException {
        List<OpenCodeBindingSource> sources = new ArrayList<>(3);
        if (hasColumns(connection, "part", Set.of("session_id", "data"))) {
            sources.add(new OpenCodeBindingSource("part", "session_id", "data"));
        }
        if (hasColumns(connection, "message", Set.of("session_id", "data"))) {
            sources.add(new OpenCodeBindingSource("message", "session_id", "data"));
        }
        if (hasColumns(connection, "session_message", Set.of("session_id", "data"))) {
            sources.add(new OpenCodeBindingSource("session_message", "session_id", "data"));
        }
        return List.copyOf(sources);
    }

    private boolean hasColumns(Connection connection, String table, Set<String> required) throws SQLException {
        Set<String> present = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(null, null, table, null)) {
            while (columns.next()) present.add(columns.getString("COLUMN_NAME").toLowerCase());
        }
        return present.containsAll(required);
    }

    private boolean claudeSessionExists(AgentDescriptor agent, Path root, String sessionId) {
        String fileName = sessionId + ".jsonl";
        if (!UUID_FILE.matcher(fileName).matches()) return false;
        return Files.isRegularFile(root.resolve(encode(agent.workspacePath())).resolve(fileName));
    }

    private boolean openCodeSessionExists(AgentDescriptor agent, Path database, String sessionId) {
        if (!Files.isRegularFile(database)||!validSessionId(sessionId)) return false;
        String sql = "SELECT 1 FROM session WHERE id=? AND directory=? AND time_archived IS NULL LIMIT 1";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, agent.workspacePath());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException unavailable) {
            return false;
        }
    }

    private static boolean validSessionId(String value){
        try{
            return ExecutionInputLimits.sessionId(value).equals(value);
        }catch(IllegalArgumentException invalid){
            return false;
        }
    }

    private boolean matchesAgent(CaptureSnapshot snapshot, String id) {
        if (!"claude_project_jsonl_dir".equals(snapshot.source())) return true;
        Path file = Path.of(snapshot.root())
                .resolve(encode(snapshot.agent().workspacePath()))
                .resolve(id + ".jsonl");
        return boundedEdgesContain(file, bindingMarkers(snapshot.agent()));
    }

    private List<String> bindingMarkers(AgentDescriptor agent) {
        return List.of(bindingMarker(agent));
    }

    private String bindingMarker(AgentDescriptor agent) {
        return "Termestra session binding: workspace_id=" + agent.workspaceId()
                + "; agent_id=" + agent.agentId();
    }

    private SessionMetadata readCodexMetadata(Path path) {
        String line = readFirstLine(path, MAX_CODEX_HEADER_BYTES);
        if (line == null || line.isBlank()) return null;
        try {
            JsonNode payload = json.readTree(line).path("payload");
            String cwd = payload.path("cwd").asText();
            String id = payload.path("id").asText();
            return cwd.isBlank() || id.isBlank() ? null : new SessionMetadata(cwd, id);
        } catch (IOException invalid) {
            return null;
        }
    }

    private String readGeminiSessionId(Path path) {
        byte[] prefix = readPrefix(path, MAX_GEMINI_SESSION_BYTES);
        if (prefix.length == 0) return null;
        try (JsonParser parser = json.getFactory().createParser(prefix)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME
                        || !"sessionId".equals(parser.currentName())) continue;
                JsonToken value = parser.nextToken();
                return value == JsonToken.VALUE_STRING ? parser.getValueAsString() : null;
            }
        } catch (IOException invalid) {
            return null;
        }
        return null;
    }

    List<Path> recentPaths(Stream<Path> paths, Predicate<Path> include,
                           Function<Path, Path> timestampSource) {
        PriorityQueue<FileCandidate> recent = recentQueue();
        collectRecent(paths, include, timestampSource, recent);
        return orderedRecent(recent);
    }

    private PriorityQueue<FileCandidate> recentQueue() {
        return new PriorityQueue<>(oldestFirst());
    }

    private void collectRecent(Stream<Path> paths, Predicate<Path> include,
                               Function<Path, Path> timestampSource,
                               PriorityQueue<FileCandidate> recent) {
        collectRecent(paths, include, timestampSource, recent, new ScanBudget());
    }

    private void collectRecent(Stream<Path> paths, Predicate<Path> include,
                               Function<Path, Path> timestampSource,
                               PriorityQueue<FileCandidate> recent, ScanBudget scanBudget) {
        Comparator<FileCandidate> oldestFirst = oldestFirst();
        var iterator = paths.iterator();
        // Claim before probing the iterator: some filesystem-backed stream iterators advance
        // during hasNext(), so probing first would traverse one entry beyond the hard budget.
        while (scanBudget.claim() && iterator.hasNext()) {
            Path path = iterator.next();
            if (!include.test(path)) continue;
            try {
                FileCandidate candidate = new FileCandidate(path,
                        Files.getLastModifiedTime(timestampSource.apply(path)).toMillis());
                if (recent.size() < MAX_CANDIDATE_FILES) {
                    recent.add(candidate);
                } else if (oldestFirst.compare(candidate, recent.element()) > 0) {
                    recent.remove();
                    recent.add(candidate);
                }
            } catch (IOException ignored) {
                // Files can disappear while a CLI rotates session data.
            }
        }
    }

    private List<Path> orderedRecent(PriorityQueue<FileCandidate> recent) {
        Comparator<FileCandidate> oldestFirst = oldestFirst();
        List<FileCandidate> ordered = new ArrayList<>(recent);
        ordered.sort(oldestFirst.reversed());
        return ordered.stream().map(FileCandidate::path).toList();
    }

    private Comparator<FileCandidate> oldestFirst() {
        return Comparator.comparingLong(FileCandidate::modifiedAt)
                .thenComparing(candidate -> candidate.path().toString());
    }

    private static final class ScanBudget {
        private int remaining = MAX_SCANNED_PATHS;

        boolean claim() {
            if (remaining == 0) return false;
            remaining--;
            return true;
        }

        boolean exhausted() { return remaining == 0; }
    }

    private String readFirstLine(Path path, int maximumBytes) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            int newline = -1;
            for (int index = 0; index < bytes.length; index++) {
                if (bytes[index] == '\n') {
                    newline = index;
                    break;
                }
            }
            if (newline < 0 && bytes.length > maximumBytes) return null;
            int length = newline >= 0 ? newline : bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') length--;
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        } catch (IOException missing) {
            return null;
        }
    }

    private String readBoundedText(Path path, int maximumBytes) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) return "";
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IOException missing) {
            return "";
        }
    }

    private boolean boundedEdgesContain(Path path, List<String> markers) {
        int edgeBytes = MAX_CLAUDE_MATCH_BYTES / 2;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            String prefix = readChunk(channel, 0, edgeBytes);
            if (markers.stream().anyMatch(prefix::contains)) return true;
            if (size <= edgeBytes) return false;
            String suffix = readChunk(channel, Math.max(0, size - edgeBytes), edgeBytes);
            return markers.stream().anyMatch(suffix::contains);
        } catch (IOException missing) {
            return false;
        }
    }

    private String readChunk(FileChannel channel, long position, int maximumBytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(maximumBytes);
        channel.position(position);
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // Continue until the bounded buffer is full or EOF is reached.
        }
        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
    }

    private byte[] readPrefix(Path path, int maximumBytes) {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(maximumBytes);
        } catch (IOException missing) {
            return new byte[0];
        }
    }

    private Config parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            JsonNode node = json.readTree(value);
            String source = node.path("source").asText();
            String pattern = node.path("pattern").asText();
            if (!Set.of("claude_project_jsonl_dir", "codex_session_jsonl_dir",
                    "gemini_session_json_dir", "opencode_session_db").contains(source)) return null;
            return new Config(source, pattern);
        } catch (IOException invalid) {
            return null;
        }
    }

    private String root(Config config) {
        String home = System.getProperty("user.home");
        String pattern = config.pattern();
        return switch (config.source()) {
            case "claude_project_jsonl_dir" -> firstEnvironment("TERMESTRA_CLAUDE_PROJECTS_DIR",
                    expand(before(pattern, "{encoded_cwd}"), Path.of(home, ".claude", "projects").toString()));
            case "codex_session_jsonl_dir" -> firstEnvironment("TERMESTRA_CODEX_HOME",
                    expand(before(pattern, "/sessions/"), Path.of(home, ".codex").toString()));
            case "gemini_session_json_dir" -> firstEnvironment("TERMESTRA_GEMINI_HOME",
                    expand(beforeLast(pattern, "/tmp/"), Path.of(home, ".gemini").toString()));
            case "opencode_session_db" -> firstEnvironment("TERMESTRA_OPENCODE_DB_PATH",
                    expand(pattern, Path.of(home, ".local", "share", "opencode", "opencode.db").toString()));
            default -> home;
        };
    }

    private Map<String, String> environment(String source, String root) {
        return switch (source) {
            case "claude_project_jsonl_dir" -> Map.of("TERMESTRA_CLAUDE_PROJECTS_DIR", root);
            case "codex_session_jsonl_dir" -> Map.of("TERMESTRA_CODEX_HOME", root, "CODEX_HOME", root);
            case "gemini_session_json_dir" -> Map.of("TERMESTRA_GEMINI_HOME", root);
            case "opencode_session_db" -> Map.of("TERMESTRA_OPENCODE_DB_PATH", root);
            default -> Map.of();
        };
    }

    private String firstEnvironment(String preferred, String fallback) {
        String value = System.getenv(preferred);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String before(String value, String marker) {
        int index = value.indexOf(marker);
        return index < 0 ? "" : value.substring(0, index);
    }

    private String beforeLast(String value, String marker) {
        int index = value.lastIndexOf(marker);
        return index < 0 ? "" : value.substring(0, index);
    }

    private String expand(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        if (value.equals("~")) return System.getProperty("user.home");
        if (value.startsWith("~/")) return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        return value;
    }

    private String encode(String path) {
        return path.replaceAll("[\\\\/:\\s]", "-");
    }

    private record Config(String source, String pattern) {}

    private record FileCandidate(Path path, long modifiedAt) {}

    private record SessionMetadata(String cwd, String id) {}

    private record OpenCodeBindingSource(String table, String sessionColumn, String dataColumn) {}

    private record SessionKey(String source, String root, String workspacePath, String sessionId) {}

    private record Claim(String owner,long expiresAtNanos) {}
}
