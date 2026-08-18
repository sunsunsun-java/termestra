package dev.termestra.bootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TasksHttpWebSocketIntegrationTest {
    private static final Path DATA=temp("termestra-tasks-http-");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("termestra.data-directory",DATA::toString);}
    @LocalServerPort int port;
    @Test void persistsTasksAndBroadcastsApiAndExternalFileUpdates(){
        WebTestClient http=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();String cookie=uiCookie(http);Path workspacePath=temp("termestra-tasks-workspace-");
        Map<?,?> workspace=http.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie).bodyValue(Map.of("path",workspacePath.toString(),"autostart_orchestrator",false)).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();String id=Objects.requireNonNull(workspace).get("id").toString();
        http.get().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie).exchange().expectStatus().isOk().expectBody().jsonPath("$.content").isEqualTo("");
        TextListener listener=new TextListener();WebSocket socket=HttpClient.newHttpClient().newWebSocketBuilder().header("Cookie",cookie).buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/tasks/"+id),listener).join();
        listener.await("tasks-snapshot");
        http.put().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie).bodyValue(Map.of("content","- [ ] api update\n")).exchange().expectStatus().isOk();
        listener.await("api update");
        try{Files.writeString(workspacePath.resolve(".termestra/tasks.md"),"- [x] external update\n",StandardCharsets.UTF_8);}catch(IOException error){throw new IllegalStateException(error);}
        listener.await("external update");
        socket.sendClose(WebSocket.NORMAL_CLOSURE,"done");
    }

    @Test void rejectsMissingOrNonStringContentWithoutReplacingTheDocument(){
        WebTestClient http=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();
        String cookie=uiCookie(http);Path workspacePath=temp("termestra-tasks-validation-");
        String id=createWorkspace(http,cookie,workspacePath);
        http.put().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("content","keep me")).exchange().expectStatus().isOk();

        http.put().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of()).exchange().expectStatus().isBadRequest();
        http.put().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("content",42)).exchange().expectStatus().isBadRequest();

        http.get().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.content").isEqualTo("keep me");
    }

    @Test void rejectsAStaleRevisionAndReturnsTheCurrentDocument(){
        WebTestClient http=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();
        String cookie=uiCookie(http);Path workspacePath=temp("termestra-tasks-revision-");
        String id=createWorkspace(http,cookie,workspacePath);
        Map<?,?> initial=http.get().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertNotNull(initial);
        String initialRevision=initial.get("revision").toString();
        Map<?,?> updated=http.put().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("content","newer","revision",initialRevision)).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertNotNull(updated);

        Map<?,?> conflict=http.put().uri("/api/workspaces/"+id+"/tasks").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("content","stale overwrite","revision",initialRevision)).exchange()
                .expectStatus().isEqualTo(409).expectBody(Map.class).returnResult().getResponseBody();

        assertNotNull(conflict);
        assertEquals("TASKS_REVISION_CONFLICT",conflict.get("error_code"));
        assertEquals("newer",conflict.get("content"));
        assertEquals(updated.get("revision"),conflict.get("revision"));
        assertEquals("newer",Files.exists(workspacePath.resolve(".termestra/tasks.md"))
                ? read(workspacePath.resolve(".termestra/tasks.md")) : null);
    }

    private static String createWorkspace(WebTestClient http,String cookie,Path workspacePath){
        Map<?,?> workspace=http.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("path",workspacePath.toString(),"autostart_orchestrator",false)).exchange()
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        return Objects.requireNonNull(workspace).get("id").toString();
    }

    private static String read(Path path){try{return Files.readString(path,StandardCharsets.UTF_8);}catch(IOException error){throw new IllegalStateException(error);}}
    private static final class TextListener implements WebSocket.Listener {private final StringBuilder messages=new StringBuilder();private final Object monitor=new Object();@Override public void onOpen(WebSocket socket){socket.request(1);}@Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){synchronized(monitor){messages.append(text);monitor.notifyAll();}socket.request(1);return null;}void await(String expected){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);synchronized(monitor){while(!messages.toString().contains(expected)&&System.nanoTime()<deadline)try{monitor.wait(50);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}if(!messages.toString().contains(expected))throw new AssertionError("Missing "+expected+" in "+messages);}}}
    private static String uiCookie(WebTestClient client){String header=client.get().uri("/api/ui/session").exchange().expectStatus().isOk().expectBody().returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);return Objects.requireNonNull(header).substring(0,header.indexOf(';'));}
    private static Path temp(String prefix){try{return Files.createTempDirectory(prefix).toRealPath();}catch(IOException error){throw new ExceptionInInitializerError(error);}}
}
