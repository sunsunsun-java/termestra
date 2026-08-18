package dev.termestra.platform.cli.team;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.termestra.tasks.application.service.TeamProtocolDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TeamCliTest {
    @TempDir Path temporaryDirectory;
    private HttpServer server;

    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void sendUsesTermestraProtocolAndJoinsTaskWordsAcrossRealHttp() throws Exception {
        AtomicReference<JsonNode> received=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/team/send",exchange->{received.set(new ObjectMapper().readTree(exchange.getRequestBody()));byte[] body="{\"dispatch_id\":\"b7e6b34e-d69c-44bb-ae41-42016c93fb61\",\"ok\":true}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("content-type","application/json");exchange.sendResponseHeaders(202,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.start();
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        TeamCli cli=new TeamCli(environment(server.getAddress().getPort()),InputStream.nullInputStream(),new PrintWriter(output,true),new PrintWriter(new ByteArrayOutputStream(),true),new ObjectMapper());

        cli.run(List.of("send","Alice","Implement","multi","word","task"));

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"ok\":true"));
        assertEquals("Implement multi word task",received.get().path("text").asText());
        assertEquals("workspace-1",received.get().path("project_id").asText());
        assertEquals(Integer.toString(server.getAddress().getPort()),received.get().path("runtime_port").asText());
        assertFalse(received.get().has("hive_port"));
        assertDoesNotThrow(() -> UUID.fromString(received.get().path("idempotency_key").asText()));
    }

    @Test void listUsesTermestraAuthenticationHeadersAcrossRealHttp() throws Exception {
        AtomicReference<String> agentId=new AtomicReference<>();
        AtomicReference<String> token=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/workspaces/workspace-1/team",exchange->{
            agentId.set(exchange.getRequestHeaders().getFirst("x-termestra-agent-id"));
            token.set(exchange.getRequestHeaders().getFirst("x-termestra-agent-token"));
            byte[] body="[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        TeamCli cli=new TeamCli(environment(server.getAddress().getPort()),InputStream.nullInputStream(),
                new PrintWriter(new ByteArrayOutputStream(),true),new PrintWriter(new ByteArrayOutputStream(),true),
                new ObjectMapper());

        cli.run(List.of("list"));

        assertEquals("worker-1",agentId.get());
        assertEquals("secret",token.get());
    }

    @Test void rejectsForeignEnvironmentAliases(){
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->TeamEnvironment.from(Map.of(
                "HIVE_PORT","4010","HIVE_PROJECT_ID","workspace-legacy",
                "HIVE_AGENT_ID","agent-legacy","HIVE_AGENT_TOKEN","token-legacy")));

        assertEquals("Missing required Termestra environment variables",error.getMessage());
    }

    @Test void rejectsBlankTermestraEnvironmentValues(){
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->TeamEnvironment.from(Map.of(
                "TERMESTRA_PORT"," ","TERMESTRA_WORKSPACE_ID","workspace-1",
                "TERMESTRA_AGENT_ID","agent-1","TERMESTRA_AGENT_TOKEN","token-1")));

        assertEquals("Missing required Termestra environment variables",error.getMessage());
    }

    @Test void statusRejectsDispatchFlagBeforeNetworkCall(){
        TeamCli cli=new TeamCli(Map.of("TERMESTRA_PORT","9","TERMESTRA_WORKSPACE_ID","workspace-1","TERMESTRA_AGENT_ID","worker-1","TERMESTRA_AGENT_TOKEN","secret"),InputStream.nullInputStream(),new PrintWriter(new ByteArrayOutputStream(),true),new PrintWriter(new ByteArrayOutputStream(),true),new ObjectMapper());
        Exception error=assertThrows(Exception.class,()->cli.run(List.of("status","working","--dispatch","dispatch-1")));
        assertTrue(error.getMessage().contains("team status does not accept --dispatch"));
    }

    @Test void extractsFocusedRuntimeGuideFromGeneratedProtocol(){
        String guide=TeamCli.extractGuide(TeamProtocolDocument.content(),"dispatch");
        assertNotNull(guide);
        assertTrue(guide.startsWith("## Guide: dispatch"));
        assertTrue(guide.contains("Refresh the roster"));
        assertFalse(guide.contains("## Guide: tasks"));
    }

    @Test void stdinRejectsContentBeyondTheCliPayloadLimitBeforeCallingTheRuntime() {
        byte[] oversized = new byte[TeamCli.MAX_STDIN_BYTES + 1];
        TeamCli cli = cli(new ByteArrayInputStream(oversized), temporaryDirectory);

        Exception error = assertThrows(Exception.class,
                () -> cli.run(List.of("report", "--stdin")));

        assertTrue(error.getMessage().contains("--stdin input exceeds " + TeamCli.MAX_STDIN_BYTES + " bytes"));
    }

    @Test void guideRejectsAnOversizedWorkspaceProtocolFile() throws Exception {
        Path metadata = Files.createDirectories(temporaryDirectory.resolve(".termestra"));
        Files.write(metadata.resolve("PROTOCOL.md"), new byte[TeamCli.MAX_PROTOCOL_BYTES + 1]);
        TeamCli cli = cli(InputStream.nullInputStream(), temporaryDirectory);

        Exception error = assertThrows(Exception.class,
                () -> cli.run(List.of("guide", "core")));

        assertTrue(error.getMessage().contains("protocol file exceeds "
                + TeamCli.MAX_PROTOCOL_BYTES + " bytes"));
    }

    @Test void guideReadsAValidProtocolThroughTheBoundedFilePath() throws Exception {
        Path metadata = Files.createDirectories(temporaryDirectory.resolve(".termestra"));
        Files.writeString(metadata.resolve("PROTOCOL.md"), TeamProtocolDocument.content());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TeamCli cli = new TeamCli(environment(9), InputStream.nullInputStream(),
                new PrintWriter(output, true), new PrintWriter(new ByteArrayOutputStream(), true),
                new ObjectMapper(), temporaryDirectory);

        cli.run(List.of("guide", "core"));

        assertTrue(output.toString(StandardCharsets.UTF_8).startsWith("## Guide: core"));
    }

    @Test void guideNeverFollowsAWorkspaceProtocolSymlink() throws Exception {
        Path metadata = Files.createDirectories(temporaryDirectory.resolve(".termestra"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside-protocol.md"),
                TeamProtocolDocument.content());
        try {
            Files.createSymbolicLink(metadata.resolve("PROTOCOL.md"), outside);
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.abort("Symbolic links unavailable: " + unsupported.getMessage());
        } catch (IOException unavailable) {
            Assumptions.abort("Symbolic links unavailable: " + unavailable.getMessage());
        }

        Exception error = assertThrows(Exception.class,
                () -> cli(InputStream.nullInputStream(), temporaryDirectory)
                        .run(List.of("guide", "core")));

        assertTrue(error.getMessage().contains("Termestra protocol not found"));
    }

    @Test void runtimeRequestsHaveARealDeadline() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/workspaces/workspace-1/team", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        TeamRuntimeClient client = new TeamRuntimeClient(HttpClient.newHttpClient(), new ObjectMapper(),
                TeamEnvironment.from(environment(server.getAddress().getPort())), Duration.ofMillis(50));

        IllegalStateException error = assertThrows(IllegalStateException.class, client::list);

        assertTrue(error.getMessage().contains("Timed out waiting for Termestra runtime"));
    }

    @Test void runtimeResponsesAreBoundedAndClosed() throws Exception {
        byte[] oversized = new byte[TeamRuntimeClient.MAX_RESPONSE_BYTES + 1];
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/workspaces/workspace-1/team", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(oversized);
            exchange.close();
        });
        server.start();
        TeamRuntimeClient client = new TeamRuntimeClient(HttpClient.newHttpClient(), new ObjectMapper(),
                TeamEnvironment.from(environment(server.getAddress().getPort())), Duration.ofSeconds(2));

        IllegalStateException error = assertThrows(IllegalStateException.class, client::list);

        assertTrue(error.getMessage().contains("response exceeds "
                + TeamRuntimeClient.MAX_RESPONSE_BYTES + " bytes"));
    }

    @Test void requestDeadlineAlsoCoversAResponseBodyThatStopsMakingProgress() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/workspaces/workspace-1/team", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write('a');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(500);
                exchange.getResponseBody().write('b');
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        TeamRuntimeClient client = new TeamRuntimeClient(HttpClient.newHttpClient(), new ObjectMapper(),
                TeamEnvironment.from(environment(server.getAddress().getPort())), Duration.ofMillis(50));
        long started = System.nanoTime();

        IllegalStateException error = assertThrows(IllegalStateException.class, client::list);

        assertTrue(error.getMessage().contains("Timed out waiting for Termestra runtime"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(300)) < 0,
                "the request timeout must cover downloading the response body");
    }

    @Test void defaultRuntimeClientBoundsConnectionSetup() {
        assertEquals(TeamRuntimeClient.CONNECT_TIMEOUT,
                TeamRuntimeClient.defaultHttpClient().connectTimeout().orElseThrow());
    }

    private TeamCli cli(InputStream input, Path workingDirectory) {
        return new TeamCli(environment(9), input, new PrintWriter(new ByteArrayOutputStream(), true),
                new PrintWriter(new ByteArrayOutputStream(), true), new ObjectMapper(), workingDirectory);
    }

    private static Map<String, String> environment(int port) {
        return Map.of("TERMESTRA_PORT", Integer.toString(port),
                "TERMESTRA_WORKSPACE_ID", "workspace-1",
                "TERMESTRA_AGENT_ID", "worker-1",
                "TERMESTRA_AGENT_TOKEN", "secret");
    }
}
