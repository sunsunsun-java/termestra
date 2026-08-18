package dev.termestra.execution.adapter.out.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.application.port.in.ExecutionInputLimits;
import dev.termestra.execution.application.port.out.AgentDescriptor;
import dev.termestra.execution.application.port.out.AgentSessionCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemAgentSessionCaptureTest {
    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper json = new ObjectMapper();
    private final FilesystemAgentSessionCapture capture = new FilesystemAgentSessionCapture(json);
    private Path workspace;
    private AgentDescriptor agent;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("workspace")).toRealPath();
        agent = new AgentDescriptor("workspace-1", "Learning Lab", workspace.toString(),
                "agent-1", "Alice", "Implement tasks", "coder");
    }

    @Test
    void capturesOnlyANewClaudeSessionBoundToTheAgent() throws Exception {
        Path projects = Files.createDirectory(temporaryDirectory.resolve("claude-projects")).toRealPath();
        Path project = claudeProject(projects);
        String oldId = "11111111-1111-4111-8111-111111111111";
        Files.writeString(project.resolve(oldId + ".jsonl"), "old session");

        String config = config("claude_project_jsonl_dir", projects + "/{encoded_cwd}/*.jsonl");
        AgentSessionCapture.CaptureSnapshot snapshot = capture.snapshot(agent, config).orElseThrow();

        String foreignId = "22222222-2222-4222-8222-222222222222";
        Files.writeString(project.resolve(foreignId + ".jsonl"), "foreign session");
        assertTrue(capture.findNew(snapshot).isEmpty());

        String currentId = "33333333-3333-4333-8333-333333333333";
        Files.writeString(project.resolve(currentId + ".jsonl"), bindingMarker());
        assertEquals(currentId, capture.findNew(snapshot).orElseThrow());
        assertTrue(capture.exists(agent, config, currentId));
    }

    @Test
    void exposesOnlyTermestraOwnedSessionCaptureVariablesToNewAgents() throws Exception {
        Path projects=Files.createDirectory(temporaryDirectory.resolve("capture-environment")).toRealPath();
        String config=config("claude_project_jsonl_dir",projects+"/{encoded_cwd}/*.jsonl");

        Map<String,String> environment=capture.snapshot(agent,config).orElseThrow().environment();

        assertEquals(Set.of("TERMESTRA_CLAUDE_PROJECTS_DIR"),environment.keySet());
    }

    @Test
    void limitsClaudeCandidatesAndSearchesOnlyBoundedFileEdges() throws Exception {
        Path projects = Files.createDirectory(temporaryDirectory.resolve("claude-projects")).toRealPath();
        Path project = claudeProject(projects);
        int historySize = FilesystemAgentSessionCapture.MAX_CANDIDATE_FILES + 10;
        for (int index = 0; index < historySize; index++) {
            Path history = Files.writeString(project.resolve(uuid(index) + ".jsonl"), "old session " + index);
            Files.setLastModifiedTime(history, FileTime.fromMillis(1_000L + index));
        }

        String config = config("claude_project_jsonl_dir", projects + "/{encoded_cwd}/*.jsonl");
        AgentSessionCapture.CaptureSnapshot snapshot = capture.snapshot(agent, config).orElseThrow();

        assertEquals(FilesystemAgentSessionCapture.MAX_CANDIDATE_FILES,
                snapshot.knownSessionIds().size());
        assertTrue(capture.exists(agent, config, uuid(0)),
                "direct resume checks must still find sessions outside the capture window");

        int edgeSize = FilesystemAgentSessionCapture.MAX_CLAUDE_MATCH_BYTES / 2;
        String middleOnlyId = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa";
        Path middleOnly = Files.writeString(project.resolve(middleOnlyId + ".jsonl"),
                "x".repeat(edgeSize + 100) + bindingMarker() + "y".repeat(edgeSize + 100));
        Files.setLastModifiedTime(middleOnly, FileTime.fromMillis(10_000L));

        String tailId = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb";
        Path tail = Files.writeString(project.resolve(tailId + ".jsonl"),
                "x".repeat(FilesystemAgentSessionCapture.MAX_CLAUDE_MATCH_BYTES + 100)
                        + bindingMarker());
        Files.setLastModifiedTime(tail, FileTime.fromMillis(10_001L));

        assertEquals(tailId, capture.findNew(snapshot).orElseThrow(),
                "the bounded tail still contains the startup binding marker");
    }

    @Test
    void capturesCodexMetadataFromABoundedHeaderWithoutReadingLargeHistory() throws Exception {
        Path codexHome = Files.createDirectory(temporaryDirectory.resolve("codex-home")).toRealPath();
        Path day = Files.createDirectories(codexHome.resolve("sessions/2026/08/07"));
        for (int index = 0; index < FilesystemAgentSessionCapture.MAX_CANDIDATE_FILES + 6; index++) {
            Path history = Files.writeString(day.resolve("rollout-old-" + index + ".jsonl"),
                    codexHeader("foreign-" + index, temporaryDirectory.resolve("foreign").toString()) + "\n");
            Files.setLastModifiedTime(history, FileTime.fromMillis(1_000L + index));
        }

        String config = config("codex_session_jsonl_dir", codexHome + "/sessions/**/*.jsonl");
        AgentSessionCapture.CaptureSnapshot snapshot = capture.snapshot(agent, config).orElseThrow();

        Path oversizedHeader = Files.writeString(day.resolve("rollout-oversized.jsonl"),
                "x".repeat(FilesystemAgentSessionCapture.MAX_CODEX_HEADER_BYTES + 1)
                        + codexHeader("oversized", workspace.toString()) + "\n");
        Files.setLastModifiedTime(oversizedHeader, FileTime.fromMillis(10_000L));

        String currentId = "codex-current";
        Path current = Files.writeString(day.resolve("rollout-current.jsonl"),
                codexHeader(currentId, workspace.toString()) + "\n"
                        + bindingMarker(agent) + "\n"
                        + "history".repeat(150_000));
        Files.setLastModifiedTime(current, FileTime.fromMillis(10_001L));

        assertEquals(currentId, capture.findNew(snapshot).orElseThrow());
    }

    @Test
    void ignoresOversizedCodexSessionIdsBeforeRetainingThem() throws Exception {
        Path codexHome = Files.createDirectory(temporaryDirectory.resolve("oversized-codex-home")).toRealPath();
        Path day = Files.createDirectories(codexHome.resolve("sessions/2026/08/11"));
        String config = config("codex_session_jsonl_dir", codexHome + "/sessions/**/*.jsonl");
        AgentSessionCapture.CaptureSnapshot snapshot = capture.snapshot(agent, config).orElseThrow();

        Files.writeString(day.resolve("rollout-oversized-id.jsonl"),
                codexHeader("x".repeat(ExecutionInputLimits.MAX_SESSION_ID_CHARACTERS + 1),
                        workspace.toString()) + "\n" + bindingMarker());

        assertTrue(capture.findNew(snapshot).isEmpty());
    }

    @Test
    void boundsTheActualFilesystemTraversalRatherThanOnlyTheRetainedCandidates() {
        AtomicInteger traversed = new AtomicInteger();
        Stream<Path> paths = Stream.generate(
                        () -> Path.of("candidate-" + traversed.incrementAndGet()))
                .limit(FilesystemAgentSessionCapture.MAX_SCANNED_PATHS + 100L);

        List<Path> recent = capture.recentPaths(paths, ignored -> false, Function.identity());

        assertTrue(recent.isEmpty());
        assertEquals(FilesystemAgentSessionCapture.MAX_SCANNED_PATHS, traversed.get(),
                "the traversal itself must stop at the configured budget");
    }

    @Test
    void assignsConcurrentCodexSessionsByExactAgentBinding() throws Exception {
        Path codexHome = Files.createDirectory(temporaryDirectory.resolve("shared-codex-home")).toRealPath();
        Path day = Files.createDirectories(codexHome.resolve("sessions/2026/08/11"));
        String config = config("codex_session_jsonl_dir", codexHome + "/sessions/**/*.jsonl");
        AgentDescriptor secondAgent = new AgentDescriptor("workspace-1", "Learning Lab", workspace.toString(),
                "agent-2", "Bob", "Review tasks", "reviewer");
        AgentSessionCapture.CaptureSnapshot firstSnapshot = capture.snapshot(agent, config).orElseThrow();
        AgentSessionCapture.CaptureSnapshot secondSnapshot = capture.snapshot(secondAgent, config).orElseThrow();

        Files.writeString(day.resolve("rollout-first.jsonl"), codexHeader("session-a", workspace.toString())
                + "\n" + bindingMarker(agent));
        Files.writeString(day.resolve("rollout-second.jsonl"), codexHeader("session-b", workspace.toString())
                + "\n" + bindingMarker(secondAgent));

        List<String> claimed = claimConcurrently(firstSnapshot, secondSnapshot);

        assertEquals(List.of("session-a", "session-b"), claimed,
                "cwd alone must never swap two concurrent Codex members' sessions");
    }

    @Test
    void capturesGeminiSessionIdFromABoundedPrefix() throws Exception {
        Path geminiHome = Files.createDirectory(temporaryDirectory.resolve("gemini-home")).toRealPath();
        Path project = Files.createDirectories(geminiHome.resolve("tmp/project"));
        Path chats = Files.createDirectory(project.resolve("chats"));
        Files.writeString(project.resolve(".project_root"), workspace.toString());

        String config = config("gemini_session_json_dir", geminiHome + "/tmp/*/chats/session-*.json");
        AgentSessionCapture.CaptureSnapshot snapshot = capture.snapshot(agent, config).orElseThrow();

        Path lateId = Files.writeString(chats.resolve("session-late.json"),
                "{\"transcript\":\""
                        + "x".repeat(FilesystemAgentSessionCapture.MAX_GEMINI_SESSION_BYTES + 1)
                        + "\",\"sessionId\":\"gemini-late\"}");
        Files.setLastModifiedTime(lateId, FileTime.fromMillis(10_000L));

        String currentId = "gemini-current";
        Path current = Files.writeString(chats.resolve("session-current.json"),
                "{\"sessionId\":\"" + currentId + "\",\"transcript\":\""
                        + bindingMarker(agent)
                        + "x".repeat(FilesystemAgentSessionCapture.MAX_GEMINI_SESSION_BYTES * 4)
                        + "\"}");
        Files.setLastModifiedTime(current, FileTime.fromMillis(10_001L));

        assertEquals(currentId, capture.findNew(snapshot).orElseThrow());
    }

    @Test
    void ignoresOversizedGeminiSessionIdsBeforeRetainingThem() throws Exception {
        Path geminiHome = Files.createDirectory(temporaryDirectory.resolve("oversized-gemini-home")).toRealPath();
        Path project = Files.createDirectories(geminiHome.resolve("tmp/project"));
        Path chats = Files.createDirectory(project.resolve("chats"));
        Files.writeString(project.resolve(".project_root"), workspace.toString());
        String config = config("gemini_session_json_dir", geminiHome + "/tmp/*/chats/session-*.json");
        AgentSessionCapture.CaptureSnapshot snapshot = capture.snapshot(agent, config).orElseThrow();

        Files.writeString(chats.resolve("session-oversized.json"),
                "{\"sessionId\":\""
                        + "x".repeat(ExecutionInputLimits.MAX_SESSION_ID_CHARACTERS + 1)
                        + "\",\"binding\":\"" + bindingMarker(agent) + "\"}");

        assertTrue(capture.findNew(snapshot).isEmpty());
    }

    @Test
    void assignsConcurrentGeminiSessionsByExactAgentBinding() throws Exception {
        Path geminiHome = Files.createDirectory(temporaryDirectory.resolve("shared-gemini-home")).toRealPath();
        Path project = Files.createDirectories(geminiHome.resolve("tmp/project"));
        Path chats = Files.createDirectory(project.resolve("chats"));
        Files.writeString(project.resolve(".project_root"), workspace.toString());
        String config = config("gemini_session_json_dir", geminiHome + "/tmp/*/chats/session-*.json");
        AgentDescriptor secondAgent = secondAgent();
        AgentSessionCapture.CaptureSnapshot firstSnapshot = capture.snapshot(agent, config).orElseThrow();
        AgentSessionCapture.CaptureSnapshot secondSnapshot = capture.snapshot(secondAgent, config).orElseThrow();

        Files.writeString(chats.resolve("session-first.json"), geminiSession("gemini-a", agent));
        Files.writeString(chats.resolve("session-second.json"), geminiSession("gemini-b", secondAgent));

        assertEquals(List.of("gemini-a", "gemini-b"), claimConcurrently(firstSnapshot, secondSnapshot),
                "project root alone must never swap two concurrent Gemini members' sessions");
    }

    @Test
    void appliesOneGlobalGeminiCandidateBudgetAcrossMatchingProjects() throws Exception {
        Path geminiHome = Files.createDirectory(temporaryDirectory.resolve("bounded-gemini-home")).toRealPath();
        int perProject = FilesystemAgentSessionCapture.MAX_CANDIDATE_FILES / 2 + 8;
        for (int projectIndex = 0; projectIndex < 2; projectIndex++) {
            Path project = Files.createDirectories(geminiHome.resolve("tmp/project-" + projectIndex));
            Path chats = Files.createDirectory(project.resolve("chats"));
            Files.writeString(project.resolve(".project_root"), workspace.toString());
            for (int session = 0; session < perProject; session++) {
                Files.writeString(chats.resolve("session-" + projectIndex + "-" + session + ".json"),
                        geminiSession("gemini-" + projectIndex + "-" + session, agent));
            }
        }

        String config = config("gemini_session_json_dir", geminiHome + "/tmp/*/chats/session-*.json");
        AgentSessionCapture.CaptureSnapshot snapshot = capture.snapshot(agent, config).orElseThrow();

        assertEquals(FilesystemAgentSessionCapture.MAX_CANDIDATE_FILES,
                snapshot.knownSessionIds().size(),
                "the content-read budget must be global, not multiplied by matching project directories");
    }

    @Test
    void limitsOpenCodeSnapshotButChecksOldSessionsDirectly() throws Exception {
        Path database = temporaryDirectory.resolve("opencode.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE session (id TEXT PRIMARY KEY, directory TEXT NOT NULL, "
                    + "time_archived INTEGER)");
            statement.execute("CREATE TABLE part (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, data TEXT NOT NULL)");
            for (int index = 0; index < FilesystemAgentSessionCapture.MAX_CANDIDATE_FILES + 6; index++)
                insertOpenCodeSession(connection, "old-" + index, agent);
        }

        String config = config("opencode_session_db", database.toString());
        AgentSessionCapture.CaptureSnapshot snapshot = capture.snapshot(agent, config).orElseThrow();
        assertEquals(FilesystemAgentSessionCapture.MAX_CANDIDATE_FILES,
                snapshot.knownSessionIds().size());
        assertTrue(capture.exists(agent, config, "old-0"),
                "direct resume checks must not inherit the discovery window");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            insertOpenCodeSession(connection, "opencode-current", agent);
        }

        assertEquals("opencode-current", capture.findNew(snapshot).orElseThrow());
    }

    @Test
    void assignsConcurrentOpenCodeSessionsByExactAgentBinding() throws Exception {
        Path database = temporaryDirectory.resolve("shared-opencode.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE session (id TEXT PRIMARY KEY, directory TEXT NOT NULL, "
                    + "time_archived INTEGER)");
            statement.execute("CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, data TEXT NOT NULL)");
        }
        String config = config("opencode_session_db", database.toString());
        AgentDescriptor secondAgent = secondAgent();
        AgentSessionCapture.CaptureSnapshot firstSnapshot = capture.snapshot(agent, config).orElseThrow();
        AgentSessionCapture.CaptureSnapshot secondSnapshot = capture.snapshot(secondAgent, config).orElseThrow();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            insertOpenCodeMessageSession(connection, "opencode-a", agent);
            insertOpenCodeMessageSession(connection, "opencode-b", secondAgent);
        }

        assertEquals(List.of("opencode-a", "opencode-b"), claimConcurrently(firstSnapshot, secondSnapshot),
                "directory alone must never swap two concurrent OpenCode members' sessions");
    }

    @Test
    void doesNotMaterializeOrCaptureOversizedOpenCodeSessionIds() throws Exception {
        Path database = temporaryDirectory.resolve("oversized-opencode.db");
        String oversized="s".repeat(2_000_000);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE session (id TEXT PRIMARY KEY, directory TEXT NOT NULL, time_archived INTEGER)");
            statement.execute("CREATE TABLE part (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, data TEXT NOT NULL)");
            try(var session=connection.prepareStatement(
                    "INSERT INTO session(id,directory,time_archived) VALUES(?,?,NULL)");
                var part=connection.prepareStatement(
                    "INSERT INTO part(id,session_id,data) VALUES('oversized-part',?,?)")){
                session.setString(1,oversized);session.setString(2,workspace.toString());session.executeUpdate();
                part.setString(1,oversized);part.setString(2,json.writeValueAsString(
                        Map.of("text",bindingMarker(agent))));part.executeUpdate();
            }
        }

        String config=config("opencode_session_db",database.toString());
        AgentSessionCapture.CaptureSnapshot snapshot=capture.snapshot(agent,config).orElseThrow();

        assertTrue(snapshot.knownSessionIds().isEmpty());
        assertFalse(capture.exists(agent,config,oversized));
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+database)){
            insertOpenCodeSession(connection,"valid-session",agent);
        }
        assertEquals("valid-session",capture.findNew(snapshot).orElseThrow());
    }

    private List<String> claimConcurrently(AgentSessionCapture.CaptureSnapshot first,
                                           AgentSessionCapture.CaptureSnapshot second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstClaim = executor.submit(() -> claimAfterBarrier(first, "run-a", ready, start));
            Future<String> secondClaim = executor.submit(() -> claimAfterBarrier(second, "run-b", ready, start));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of(firstClaim.get(5, TimeUnit.SECONDS), secondClaim.get(5, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private String claimAfterBarrier(AgentSessionCapture.CaptureSnapshot snapshot, String claimant,
                                     CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(2, TimeUnit.SECONDS));
        return capture.claimNew(snapshot, claimant).orElseThrow();
    }

    private void insertOpenCodeSession(Connection connection, String sessionId,
                                       AgentDescriptor owner) throws Exception {
        try (PreparedStatement session = connection.prepareStatement(
                "INSERT INTO session (id, directory, time_archived) VALUES (?, ?, NULL)");
             PreparedStatement part = connection.prepareStatement(
                     "INSERT INTO part (id, session_id, data) VALUES (?, ?, ?)")) {
            session.setString(1, sessionId);
            session.setString(2, workspace.toString());
            session.executeUpdate();
            part.setString(1, "part-" + sessionId);
            part.setString(2, sessionId);
            part.setString(3, json.writeValueAsString(Map.of("text", bindingMarker(owner))));
            part.executeUpdate();
        }
    }

    private void insertOpenCodeMessageSession(Connection connection, String sessionId,
                                              AgentDescriptor owner) throws Exception {
        try (PreparedStatement session = connection.prepareStatement(
                "INSERT INTO session (id, directory, time_archived) VALUES (?, ?, NULL)");
             PreparedStatement message = connection.prepareStatement(
                     "INSERT INTO message (id, session_id, data) VALUES (?, ?, ?)")) {
            session.setString(1, sessionId);
            session.setString(2, workspace.toString());
            session.executeUpdate();
            message.setString(1, "message-" + sessionId);
            message.setString(2, sessionId);
            message.setString(3, json.writeValueAsString(Map.of("content", bindingMarker(owner))));
            message.executeUpdate();
        }
    }

    private Path claudeProject(Path projects) throws Exception {
        return Files.createDirectories(projects.resolve(workspace.toString().replaceAll("[\\\\/:\\s]", "-")));
    }

    private String config(String source, String pattern) throws Exception {
        return json.writeValueAsString(Map.of("source", source, "pattern", pattern));
    }

    private String bindingMarker() {
        return bindingMarker(agent);
    }

    private String bindingMarker(AgentDescriptor owner) {
        return "Termestra session binding: workspace_id=" + owner.workspaceId()
                + "; agent_id=" + owner.agentId();
    }

    private AgentDescriptor secondAgent() {
        return new AgentDescriptor("workspace-1", "Learning Lab", workspace.toString(),
                "agent-2", "Bob", "Review tasks", "reviewer");
    }

    private String geminiSession(String sessionId, AgentDescriptor owner) throws Exception {
        return json.writeValueAsString(Map.of("sessionId", sessionId,
                "transcript", bindingMarker(owner)));
    }

    private String codexHeader(String sessionId, String cwd) throws Exception {
        return json.writeValueAsString(Map.of("payload", Map.of("id", sessionId, "cwd", cwd)));
    }

    private String uuid(int ordinal) {
        return "00000000-0000-4000-8000-" + String.format("%012x", ordinal);
    }
}
