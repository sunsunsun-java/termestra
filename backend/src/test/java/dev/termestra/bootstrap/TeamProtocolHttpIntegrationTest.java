package dev.termestra.bootstrap;

import dev.termestra.auth.application.AgentCredentialService;
import dev.termestra.bootstrap.support.PtyTestFixture;
import dev.termestra.bootstrap.support.TestJavaCommand;
import dev.termestra.execution.application.port.in.AgentExecutionUseCase;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeamProtocolHttpIntegrationTest {
    private static final Path DATA_DIRECTORY=temp("termestra-team-http-");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("termestra.data-directory",DATA_DIRECTORY::toString);}
    @LocalServerPort int port;
    @Autowired AgentCredentialService credentials;
    @Autowired AgentExecutionUseCase execution;
    @Autowired SqliteDatabase database;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    private final Set<String> workspacesWithRealPtys=new LinkedHashSet<>();

    @AfterEach void stopRealPtys(){
        for(String workspaceId:workspacesWithRealPtys){
            execution.listActiveSummaries(workspaceId).forEach(run->execution.stop(run.runId()));
        }
        workspacesWithRealPtys.clear();
    }

    @Test void runsSendReportCancelListAndDispatchQueriesAcrossRealHttpAndSqlite(){
        WebTestClient client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();
        String cookie=uiCookie(client);
        Path workspacePath=temp("termestra-team-workspace-");
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Team Lab","path",workspacePath.toString(),"autostart_orchestrator",false)).exchange()
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();
        workspacesWithRealPtys.add(workspaceId);
        Map<?,?> worker=client.post().uri("/api/workspaces/"+workspaceId+"/workers").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Alice","role","coder","description","Implement tasks")).exchange()
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workerId=Objects.requireNonNull(worker).get("id").toString();
        TestJavaCommand command=TestJavaCommand.fixture(PtyTestFixture.class,"echo");
        client.post().uri("/api/workspaces/"+workspaceId+"/agents/"+workerId+"/config").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("command",command.command(),"args",command.arguments())).exchange().expectStatus().isNoContent();
        String orchestratorId=workspaceId+":orchestrator";
        String orchestratorToken=credentials.issue(orchestratorId);

        String first=send(client,workspaceId,orchestratorId,orchestratorToken,"First task");
        String second=send(client,workspaceId,orchestratorId,orchestratorToken,"Second task");
        String workerToken=awaitCurrentToken(workerId);

        client.post().uri("/api/team/report").bodyValue(Map.of("project_id",workspaceId,"from_agent_id",workerId,
                        "token",workerToken,"dispatch_id",second,"result","Second done","artifacts",List.of("src/Done.java")))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.dispatch_id").isEqualTo(second)
                .jsonPath("$.forwarded").isEqualTo(false);
        client.post().uri("/api/team/cancel").bodyValue(Map.of("project_id",workspaceId,"from_agent_id",orchestratorId,
                        "token",orchestratorToken,"dispatch_id",first,"reason","Direction changed"))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.dispatch_id").isEqualTo(first);
        workerToken=credentials.issue(workerId);
        client.post().uri("/api/team/status").bodyValue(Map.of("project_id",workspaceId,"from_agent_id",workerId,
                        "token",workerToken,"result","Waiting for review","artifacts",List.of()))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.dispatch_id").doesNotExist();

        String third=send(client,workspaceId,orchestratorId,orchestratorToken,"Third task");
        String fourth=send(client,workspaceId,orchestratorId,orchestratorToken,"Fourth task");
        workerToken=awaitCurrentToken(workerId);
        client.post().uri("/api/team/report").bodyValue(Map.of("project_id",workspaceId,"from_agent_id",workerId,
                        "token",workerToken,"result","All remaining work done","status","success","artifacts",List.of()))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.dispatch_id").isEqualTo(third);
        client.get().uri("/api/workspaces/"+workspaceId+"/team").header("x-termestra-agent-id",orchestratorId).header("x-termestra-agent-token",orchestratorToken)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].pending_task_count").isEqualTo(1).jsonPath("$[0].status").isEqualTo("working");
        client.post().uri("/api/team/report").bodyValue(Map.of("project_id",workspaceId,"from_agent_id",workerId,
                        "token",workerToken,"result","Final remaining work done","status","success","artifacts",List.of()))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.dispatch_id").isEqualTo(fourth);
        client.post().uri("/api/team/report").bodyValue(Map.of("project_id",workspaceId,"from_agent_id",workerId,
                        "token",workerToken,"dispatch_id",first,"result","Late report","artifacts",List.of()))
                .exchange().expectStatus().isEqualTo(409);

        client.get().uri("/api/ui/workspaces/"+workspaceId+"/dispatches").header(HttpHeaders.COOKIE,cookie).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[0].id").isEqualTo(first).jsonPath("$[0].state").isEqualTo("cancelled")
                .jsonPath("$[1].id").isEqualTo(second).jsonPath("$[1].state").isEqualTo("reported").jsonPath("$[1].artifacts[0]").isEqualTo("src/Done.java")
                .jsonPath("$[2].id").isEqualTo(third).jsonPath("$[2].state").isEqualTo("reported")
                .jsonPath("$[3].id").isEqualTo(fourth).jsonPath("$[3].state").isEqualTo("reported")
                .jsonPath("$[0].truncated").isEqualTo(false);
        client.get().uri("/api/ui/workspaces/"+workspaceId+"/dispatches?state=reported&limit=1&offset=0").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].id").isEqualTo(second);
        client.get().uri("/api/workspaces/"+workspaceId+"/team").header("x-termestra-agent-id",orchestratorId).header("x-termestra-agent-token",orchestratorToken)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].pending_task_count").isEqualTo(0).jsonPath("$[0].status").isEqualTo("idle");
        client.get().uri("/api/workspaces/"+workspaceId+"/team").header("x-termestra-agent-id",workerId).header("x-termestra-agent-token",workerToken)
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.error").isEqualTo("Role 'coder' is not allowed to run team list");

        Map<String,Integer> messages=database.read("count protocol messages by type",c->{
            try(var ps=c.prepareStatement("SELECT type, COUNT(*) FROM messages WHERE workspace_id=? GROUP BY type")){
                ps.setString(1,workspaceId);
                try(var rs=ps.executeQuery()){
                    Map<String,Integer> counts=new HashMap<>();
                    while(rs.next()) counts.put(rs.getString(1),rs.getInt(2));
                    return counts;
                }
            }
        });
        assertEquals(Map.of("send",4,"report",3,"status",1),messages);
    }

    @Test void rejectsForeignAgentAuthenticationHeadersAtTheHttpBoundary(){
        WebTestClient client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();
        String cookie=uiCookie(client);
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Legacy Header Lab","path",temp("termestra-legacy-header-").toString(),
                        "autostart_orchestrator",false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();
        String orchestratorId=workspaceId+":orchestrator";
        String token=credentials.issue(orchestratorId);

        client.get().uri("/api/workspaces/"+workspaceId+"/team")
                .header("x-hive-agent-id",orchestratorId).header("x-hive-agent-token",token)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test void boundsLargeDispatchHistoryInTheListAndKeepsTheExplicitDetailComplete() throws Exception {
        WebTestClient client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port)
                .codecs(codecs->codecs.defaultCodecs().maxInMemorySize(2*1024*1024)).build();
        String cookie=uiCookie(client);
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Large Dispatch Lab","path",temp("termestra-large-dispatch-").toString(),"autostart_orchestrator",false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();
        String dispatchId=UUID.randomUUID().toString();
        String oversizedDispatchId=UUID.randomUUID().toString();
        String workerId=UUID.randomUUID().toString();
        String text="task-"+"T".repeat(50_000);
        String report="report-"+"R".repeat(50_000);
        String artifact="artifact-"+"A".repeat(50_000);
        seedReportedDispatch(workspaceId,workerId,dispatchId,text,report,artifact);
        seedReportedDispatch(workspaceId,workerId,oversizedDispatchId,"X".repeat(200_000),"Y".repeat(200_000),"Z".repeat(200_000));

        byte[] listBody=client.get().uri("/api/ui/workspaces/"+workspaceId+"/dispatches").header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertNotNull(listBody);
        assertTrue(listBody.length<8192,"bounded dispatch list response was "+listBody.length+" bytes");
        var summary=json.readTree(listBody).get(0);
        Set<String> fields=new HashSet<>();summary.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id","workspace_id","from_agent_id","to_agent_id","text","state","created_at",
                "delivered_at","submitted_at","reported_at","report_text","artifacts","truncated",
                "delivery_state","delivery_attempt_count","delivery_error","delivery_next_attempt_at",
                "delivery_input_attempted"),fields);
        assertEquals(dispatchId,summary.path("id").asText());
        assertEquals(512,summary.path("text").asText().length());
        assertEquals(512,summary.path("report_text").asText().length());
        assertTrue(summary.path("artifacts").isEmpty());
        assertTrue(summary.path("truncated").asBoolean());

        byte[] detailBody=client.get().uri("/api/ui/workspaces/"+workspaceId+"/dispatches/"+dispatchId)
                .header(HttpHeaders.COOKIE,cookie).exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertNotNull(detailBody);
        var detail=json.readTree(detailBody);
        assertEquals(text,detail.path("text").asText());
        assertEquals(report,detail.path("report_text").asText());
        assertEquals(artifact,detail.path("artifacts").get(0).asText());
        assertFalse(detail.path("truncated").asBoolean());

        byte[] boundedDetailBody=client.get().uri("/api/ui/workspaces/"+workspaceId+"/dispatches/"+oversizedDispatchId)
                .header(HttpHeaders.COOKIE,cookie).exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertNotNull(boundedDetailBody);
        assertTrue(boundedDetailBody.length<140_000,"bounded dispatch detail response was "+boundedDetailBody.length+" bytes");
        var boundedDetail=json.readTree(boundedDetailBody);
        assertEquals(65_536,boundedDetail.path("text").asText().length());
        assertEquals(65_536,boundedDetail.path("report_text").asText().length());
        assertTrue(boundedDetail.path("artifacts").isEmpty());
        assertTrue(boundedDetail.path("truncated").asBoolean());
    }

    @Test void validatesDispatchQueryParameters(){WebTestClient client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();String cookie=uiCookie(client);
        client.get().uri("/api/ui/workspaces/missing/dispatches?status=queued").header(HttpHeaders.COOKIE,cookie).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").isEqualTo("Use state instead of status for dispatch filtering");
        client.get().uri("/api/ui/workspaces/missing/dispatches?limit=1abc").header(HttpHeaders.COOKIE,cookie).exchange().expectStatus().isBadRequest();}

    @Test void repeatedHttpSendWithTheSameIdempotencyKeyReturnsOneDurableDispatch(){
        WebTestClient client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();String cookie=uiCookie(client);
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Idempotency Lab","path",temp("termestra-idempotency-workspace-").toString(),"autostart_orchestrator",false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();
        Map<?,?> worker=client.post().uri("/api/workspaces/"+workspaceId+"/workers").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Once","role","coder")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String workerId=Objects.requireNonNull(worker).get("id").toString();String actor=workspaceId+":orchestrator";
        String token=credentials.issue(actor);String key=UUID.randomUUID().toString();
        Map<String,Object> request=Map.of("project_id",workspaceId,"from_agent_id",actor,"token",token,
                "to","Once","text","execute exactly once","idempotency_key",key);
        Map<?,?> first=client.post().uri("/api/team/send").bodyValue(request).exchange().expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<?,?> replay=client.post().uri("/api/team/send").bodyValue(request).exchange().expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertEquals(Objects.requireNonNull(first).get("dispatch_id"),Objects.requireNonNull(replay).get("dispatch_id"));
        assertEquals(1,count("messages",workspaceId,workerId));assertEquals(1,count("dispatches",workspaceId,workerId));
        assertEquals(1,count("dispatch_deliveries",workspaceId,workerId));
        String dispatchId=first.get("dispatch_id").toString();
        awaitDeliveryState(dispatchId,Set.of("retry_wait"));
        database.write("simulate uncertain operator decision",connection->{try(var statement=connection.prepareStatement("UPDATE dispatch_deliveries SET state='uncertain',input_attempted=1 WHERE dispatch_id=?")){statement.setString(1,dispatchId);statement.executeUpdate();}return null;});
        client.get().uri("/api/ui/workspaces/"+workspaceId+"/dispatch-delivery-issues?limit=100")
                .header(HttpHeaders.COOKIE,cookie).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].id").isEqualTo(dispatchId)
                .jsonPath("$[0].delivery_state").isEqualTo("uncertain")
                .jsonPath("$[0].delivery_input_attempted").isEqualTo(true);
        client.post().uri("/api/ui/workspaces/"+workspaceId+"/dispatches/"+dispatchId+"/retry")
                .header(HttpHeaders.COOKIE,cookie).exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.dispatch_id").isEqualTo(dispatchId);
        awaitDeliveryState(dispatchId,Set.of("pending","delivering","retry_wait"));
    }

    @Test void rollsBackFailedSendAndHardDeletesWorkerState(){
        WebTestClient client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();String cookie=uiCookie(client);
        Map<?,?> workspace=client.post().uri("/api/workspaces").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","Rollback Lab","path",temp("termestra-rollback-workspace-").toString(),"autostart_orchestrator",false))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String workspaceId=Objects.requireNonNull(workspace).get("id").toString();
        database.write("block launch configuration",connection->{try(var statement=connection.createStatement()){statement.execute("CREATE TRIGGER block_launch_config BEFORE INSERT ON agent_launch_configs BEGIN SELECT RAISE(ABORT, 'blocked launch config'); END");}return null;});
        client.post().uri("/api/workspaces/"+workspaceId+"/workers").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","ConfigFailure","role","coder","command_preset_id","claude"))
                .exchange().expectStatus().is5xxServerError();
        database.write("unblock launch configuration",connection->{try(var statement=connection.createStatement()){statement.execute("DROP TRIGGER block_launch_config");}return null;});
        client.get().uri("/api/ui/workspaces/"+workspaceId+"/team").header(HttpHeaders.COOKIE,cookie).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[?(@.name == 'ConfigFailure')]").isEmpty();
        Map<?,?> worker=client.post().uri("/api/workspaces/"+workspaceId+"/workers").header(HttpHeaders.COOKIE,cookie)
                .bodyValue(Map.of("name","NoConfig","role","coder")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String workerId=Objects.requireNonNull(worker).get("id").toString();String orchestratorId=workspaceId+":orchestrator";
        String token=credentials.issue(orchestratorId);

        Map<?,?> accepted=client.post().uri("/api/team/send").bodyValue(Map.of("project_id",workspaceId,"from_agent_id",orchestratorId,
                        "token",token,"to","NoConfig","text","must remain durable","idempotency_key",UUID.randomUUID().toString()))
                .exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult().getResponseBody();
        String acceptedDispatch=Objects.requireNonNull(accepted).get("dispatch_id").toString();
        awaitDeliveryState(acceptedDispatch,Set.of("retry_wait","failed"));
        assertEquals(1,count("messages",workspaceId,workerId));assertEquals(1,count("dispatches",workspaceId,workerId));
        client.get().uri("/api/ui/workspaces/"+workspaceId+"/team").header(HttpHeaders.COOKIE,cookie).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[0].pending_task_count").isEqualTo(1);

        long now=System.currentTimeMillis();
        database.write("seed worker lifecycle",connection->{try(var statement=connection.createStatement()){
            statement.executeUpdate("INSERT INTO messages(workspace_id,worker_id,type,text,artifacts,created_at) VALUES('"+workspaceId+"','"+workerId+"','send','delete','[]',"+now+")");
            statement.executeUpdate("INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at,artifacts) VALUES('"+UUID.randomUUID()+"','"+workspaceId+"','"+workerId+"','delete','queued',"+now+",'[]')");
            statement.executeUpdate("INSERT INTO agent_launch_configs(workspace_id,agent_id,command,args_json,created_at,updated_at) VALUES('"+workspaceId+"','"+workerId+"','cat','[]',"+now+","+now+")");
            statement.executeUpdate("INSERT INTO agent_sessions(agent_id,workspace_id,last_session_id,updated_at) VALUES('"+workerId+"','"+workspaceId+"','session-delete',"+now+")");
            statement.executeUpdate("INSERT INTO agent_runs(run_id,agent_id,status,started_at,created_at,updated_at) VALUES('"+UUID.randomUUID()+"','"+workerId+"','running',"+now+","+now+","+now+")");
        }return null;});
        client.delete().uri("/api/workspaces/"+workspaceId+"/workers/"+workerId).header(HttpHeaders.COOKIE,cookie)
                .exchange().expectStatus().isNoContent();
        for(String table:List.of("workers","messages","dispatches","dispatch_deliveries","agent_launch_configs","agent_sessions","agent_runs"))assertEquals(0,count(table,workspaceId,workerId),table);
    }

    private static String send(WebTestClient client,String workspace,String actor,String token,String text){Map<?,?> body=client.post().uri("/api/team/send").bodyValue(Map.of("project_id",workspace,"from_agent_id",actor,"token",token,"to","Alice","text",text,"runtime_port","3000","idempotency_key",UUID.randomUUID().toString())).exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult().getResponseBody();return Objects.requireNonNull(body).get("dispatch_id").toString();}
    private static String uiCookie(WebTestClient client){String header=client.get().uri("/api/ui/session").exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);return Objects.requireNonNull(header).substring(0,header.indexOf(';'));}
    private static Path temp(String prefix){try{return Files.createTempDirectory(prefix).toRealPath();}catch(IOException error){throw new ExceptionInInitializerError(error);}}
    private void seedReportedDispatch(String workspace,String worker,String dispatch,String text,String report,String artifact) throws com.fasterxml.jackson.core.JsonProcessingException {
        String artifacts=json.writeValueAsString(List.of(artifact));
        database.write("seed large dispatch history",connection->{
            try(var statement=connection.prepareStatement("INSERT INTO dispatches(id,workspace_id,from_agent_id,to_agent_id,text,status,created_at,reported_at,report_text,artifacts) VALUES(?,?,?,?,?,'reported',?,?,?,?)")){
                long now=System.currentTimeMillis();
                statement.setString(1,dispatch);statement.setString(2,workspace);statement.setString(3,workspace+":orchestrator");
                statement.setString(4,worker);statement.setString(5,text);statement.setLong(6,now);statement.setLong(7,now);
                statement.setString(8,report);statement.setString(9,artifacts);statement.executeUpdate();
            }
            return null;
        });
    }
    private void awaitDeliveryState(String dispatchId,Set<String> states){for(int attempt=0;attempt<100;attempt++){String state=database.read("read delivery state",connection->{try(var statement=connection.prepareStatement("SELECT state FROM dispatch_deliveries WHERE dispatch_id=?")){statement.setString(1,dispatchId);try(var rows=statement.executeQuery()){return rows.next()?rows.getString(1):null;}}});if(states.contains(state))return;try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}throw new AssertionError("Delivery did not reach "+states);}
    private String awaitCurrentToken(String workerId){for(int attempt=0;attempt<200;attempt++){Optional<String> token=credentials.currentToken(workerId);if(token.isPresent())return token.orElseThrow();try{Thread.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}throw new AssertionError("Worker did not receive a runtime token: "+workerId);}
    private int count(String table,String workspace,String worker){return database.read("count "+table,connection->{String condition=switch(table){case "agent_runs"->"agent_id=?";case "workers","messages"->"workspace_id=? AND "+(table.equals("workers")?"id":"worker_id")+"=?";case "dispatches","dispatch_deliveries"->"workspace_id=? AND to_agent_id=?";default->"workspace_id=? AND agent_id=?";};try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE "+condition)){if(table.equals("agent_runs")){statement.setString(1,worker);}else{statement.setString(1,workspace);statement.setString(2,worker);}try(var result=statement.executeQuery()){result.next();return result.getInt(1);}}});}
}
