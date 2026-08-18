package dev.termestra.team.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.platform.persistence.sqlite.*;
import dev.termestra.team.adapter.out.persistence.*;
import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.port.in.CancelTaskCommand;
import dev.termestra.team.application.port.in.ReportTaskCommand;
import dev.termestra.team.application.port.in.SendTaskCommand;
import dev.termestra.team.application.port.in.TeamOperationResult;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TeamApplicationServiceRollbackTest {
    @TempDir Path tempDirectory;

    @Test void requestAcceptanceRetainsDurableWorkWhenWorkerIsUnavailable() {
        SqliteDatabase database=database("team.db");
        String workspace=UUID.randomUUID().toString();
        database.write("seed workspace",connection->{try(var statement=connection.prepareStatement("INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")){statement.setString(1,workspace);statement.setString(2,"Alpha");statement.setString(3,"/tmp/alpha");statement.setLong(4,System.currentTimeMillis());statement.executeUpdate();}return null;});
        JdbcTeamMemberRepository members=new JdbcTeamMemberRepository(database);
        TeamMember worker=TeamMember.create(WorkspaceId.parse(workspace),"Alice","Implement tasks",AgentRole.CODER,Instant.now());
        members.save(worker);
        AgentTeamNotifier unavailable=new AgentTeamNotifier(){
            @Override public DeliveryResult deliver(Dispatch dispatch,TeamMember member,String port){return DeliveryResult.unavailable("No worker launch config available");}
            @Override public DeliveryResult report(Dispatch dispatch,TeamMember member){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult status(String id,TeamMember member,String text,java.util.List<String> artifacts){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult cancel(Dispatch dispatch,TeamMember member){return DeliveryResult.unavailable("unused");}
        };
        JdbcTeamLedger ledger=new JdbcTeamLedger(database,new ObjectMapper());
        PendingTaskProjection pending=new PendingTaskProjection(ledger);
        TeamApplicationService service=new TeamApplicationService(ledger,members,(id,token)->true,unavailable,
                workspaceId->java.util.Set.of(),pending,Clock.systemUTC());

        TeamOperationResult accepted=service.send(new SendTaskCommand(workspace,workspace+":orchestrator","token","Alice","Build it","4010"));
        assertFalse(accepted.forwarded());
        database.read("verify durable dispatch acceptance",connection->{assertEquals(1,count(connection,"messages"));assertEquals(1,count(connection,"dispatches"));assertEquals(1,count(connection,"dispatch_deliveries"));return null;});
        assertEquals(1,service.listForUi(workspace).getFirst().pendingTaskCount());
    }

    @Test void preservesQueuedDispatchWhenDeliveryMayHaveReachedWorkerInput() {
        SqliteDatabase database=database("uncertain-delivery.db");
        String workspace=seedWorkspace(database);
        JdbcTeamMemberRepository members=new JdbcTeamMemberRepository(database);
        TeamMember worker=TeamMember.create(WorkspaceId.parse(workspace),"Alice","Implement tasks",AgentRole.CODER,Instant.now());
        members.save(worker);
        JdbcTeamLedger ledger=new JdbcTeamLedger(database,new ObjectMapper());
        PendingTaskProjection pending=new PendingTaskProjection(ledger);
        AgentTeamNotifier uncertain=notifierReturning(DeliveryResult.uncertain("PTY closed after input write started"));
        TeamApplicationService service=new TeamApplicationService(ledger,members,(id,token)->true,uncertain,
                workspaceId->java.util.Set.of(),pending,Clock.systemUTC());

        TeamOperationResult accepted=service.send(
                new SendTaskCommand(workspace,workspace+":orchestrator","token","Alice","Build it","4010"));
        assertFalse(accepted.forwarded());
        database.read("verify uncertain dispatch retained",connection->{
            assertEquals(1,count(connection,"messages"));
            assertEquals(1,count(connection,"dispatches"));
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT status FROM dispatches")){
                assertTrue(rows.next());
                assertEquals("queued",rows.getString(1));
            }
            return null;
        });
        assertEquals(1,service.listForUi(workspace).getFirst().pendingTaskCount());
    }

    @Test void preservesQueuedDispatchWhenDeliveryAcknowledgementCannotBePersisted() {
        SqliteDatabase database=database("acknowledgement-failure.db");
        String workspace=seedWorkspace(database);
        JdbcTeamMemberRepository members=new JdbcTeamMemberRepository(database);
        TeamMember worker=TeamMember.create(WorkspaceId.parse(workspace),"Alice","Implement tasks",AgentRole.CODER,Instant.now());
        members.save(worker);
        JdbcTeamLedger durableLedger=new JdbcTeamLedger(database,new ObjectMapper());
        TeamLedger failingAcknowledgement=new DelegatingTeamLedger(durableLedger) {
            @Override public void markDeliverySubmitted(String attemptId,Instant submittedAt) {
                throw new IllegalStateException("simulated acknowledgement failure");
            }
        };
        PendingTaskProjection pending=new PendingTaskProjection(durableLedger);
        TeamApplicationService service=new TeamApplicationService(failingAcknowledgement,members,(id,token)->true,
                notifierReturning(new DeliveryResult(true,null)),workspaceId->java.util.Set.of(),pending,Clock.systemUTC());

        service.send(new SendTaskCommand(workspace,workspace+":orchestrator","token","Alice","Build it","4010"));
        DispatchDeliveryApplicationService deliveries=new DispatchDeliveryApplicationService(
                failingAcknowledgement,members,notifierReturning(new DeliveryResult(true,null)),
                new RuntimeOperationCoordinator(),Clock.systemUTC());
        IllegalStateException error=assertThrows(IllegalStateException.class,deliveries::processNext);
        assertEquals("simulated acknowledgement failure",error.getMessage());
        assertEquals(1,deliveries.recoverInterrupted());
        database.read("verify acknowledged input dispatch retained",connection->{
            assertEquals(1,count(connection,"messages"));
            assertEquals(1,count(connection,"dispatches"));
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT status,delivered_at FROM dispatches")){
                assertTrue(rows.next());
                assertEquals("queued",rows.getString(1));
                assertNull(rows.getObject(2));
            }
            return null;
        });
        assertEquals(1,service.listForUi(workspace).getFirst().pendingTaskCount());
    }

    @Test void cancellationClosesOneTaskWithoutStoppingTheActiveWorker() {
        SqliteDatabase database=database("cancel.db");
        String workspace=UUID.randomUUID().toString();
        database.write("seed workspace",connection->{try(var statement=connection.prepareStatement("INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")){statement.setString(1,workspace);statement.setString(2,"Alpha");statement.setString(3,"/tmp/alpha");statement.setLong(4,System.currentTimeMillis());statement.executeUpdate();}return null;});
        JdbcTeamMemberRepository members=new JdbcTeamMemberRepository(database);
        TeamMember worker=TeamMember.create(WorkspaceId.parse(workspace),"Alice","Implement tasks",AgentRole.CODER,Instant.now());
        members.save(worker);
        java.util.List<String> events=new java.util.ArrayList<>();
        AgentTeamNotifier notifier=new AgentTeamNotifier(){
            @Override public DeliveryResult deliver(Dispatch dispatch,TeamMember member,String port){return new DeliveryResult(true,null);}
            @Override public DeliveryResult report(Dispatch dispatch,TeamMember member){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult status(String id,TeamMember member,String text,java.util.List<String> artifacts){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult cancel(Dispatch dispatch,TeamMember member){events.add("prompt");return DeliveryResult.unavailable("prompt unavailable");}
        };
        JdbcTeamLedger ledger=new JdbcTeamLedger(database,new ObjectMapper());
        PendingTaskProjection pending=new PendingTaskProjection(ledger);
        TeamApplicationService service=new TeamApplicationService(ledger,members,(id,token)->true,notifier,
                workspaceId->java.util.Set.of(worker.id().toString()),pending,Clock.systemUTC());
        TeamOperationResult sent=service.send(new SendTaskCommand(workspace,workspace+":orchestrator","token","Alice","Build it","4010"));

        TeamOperationResult cancelled=service.cancel(new dev.termestra.team.application.port.in.CancelTaskCommand(workspace,workspace+":orchestrator","token",sent.dispatchId(),"obsolete"));

        assertEquals(java.util.List.of("prompt"),events);
        assertFalse(cancelled.forwarded());
        var view=service.listForUi(workspace).getFirst();
        assertEquals(0,view.pendingTaskCount());
        assertEquals("idle",view.status());
    }

    @Test void cancellationAndReportDoNotHydratePoisonedLegacyDispatchBodies() {
        SqliteDatabase database=database("poisoned-terminal-transitions.db");
        String workspace=seedWorkspace(database);
        JdbcTeamMemberRepository members=new JdbcTeamMemberRepository(database);
        TeamMember worker=TeamMember.create(WorkspaceId.parse(workspace),"Alice","Implement tasks",
                AgentRole.CODER,Instant.now());
        members.save(worker);
        String cancelledId=UUID.randomUUID().toString();
        String reportedId=UUID.randomUUID().toString();
        database.write("seed poisoned open dispatch bodies",connection->{
            try(var dispatch=connection.prepareStatement("""
                    INSERT INTO dispatches(
                      id,workspace_id,to_agent_id,text,status,created_at,artifacts)
                    VALUES(?,?,?,'','queued',?,'{')
                    """)){
                dispatch.setString(1,cancelledId);dispatch.setString(2,workspace);
                dispatch.setString(3,worker.id().toString());dispatch.setLong(4,System.currentTimeMillis());
                dispatch.executeUpdate();
                dispatch.setString(1,reportedId);dispatch.setLong(4,System.currentTimeMillis()+1);
                dispatch.executeUpdate();
            }
            return null;
        });
        List<String> notifications=new java.util.ArrayList<>();
        AgentTeamNotifier notifier=new AgentTeamNotifier(){
            @Override public DeliveryResult deliver(Dispatch dispatch,TeamMember member,String port){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult report(Dispatch dispatch,TeamMember member){notifications.add("report");return new DeliveryResult(true,null);}
            @Override public DeliveryResult status(String id,TeamMember member,String text,List<String> artifacts){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult cancel(Dispatch dispatch,TeamMember member){notifications.add("cancel");return new DeliveryResult(true,null);}
        };
        JdbcTeamLedger ledger=new JdbcTeamLedger(database,new ObjectMapper());
        TeamApplicationService service=new TeamApplicationService(ledger,members,(id,token)->true,notifier,
                ignored->java.util.Set.of(),new PendingTaskProjection(ledger),Clock.systemUTC());

        TeamOperationResult cancelled=service.cancel(new CancelTaskCommand(workspace,
                workspace+":orchestrator","token",cancelledId,"obsolete"));
        TeamOperationResult reported=service.report(new ReportTaskCommand(workspace,
                worker.id().toString(),"token",reportedId,"done",null,List.of()));

        assertTrue(cancelled.forwarded());
        assertTrue(reported.forwarded());
        assertEquals(List.of("cancel","report"),notifications);
        assertEquals(0,service.listForUi(workspace).getFirst().pendingTaskCount());
        database.read("verify poisoned transitions committed",connection->{
            try(var statement=connection.prepareStatement("""
                    SELECT COUNT(*) FROM dispatches
                    WHERE workspace_id=? AND status IN ('cancelled','reported')
                    """)){
                statement.setString(1,workspace);
                try(var rows=statement.executeQuery()){rows.next();assertEquals(2,rows.getInt(1));}
            }
            return null;
        });
    }

    @Test void cancellationStillReportsItsDurableResultWhenTheWorkerIsDeletedConcurrently() {
        SqliteDatabase database=database("cancel-worker-race.db");
        String workspace=seedWorkspace(database);
        JdbcTeamMemberRepository members=new JdbcTeamMemberRepository(database);
        TeamMember worker=TeamMember.create(WorkspaceId.parse(workspace),"Alice","Implement tasks",AgentRole.CODER,Instant.now());
        members.save(worker);
        JdbcTeamLedger durableLedger=new JdbcTeamLedger(database,new ObjectMapper());
        TeamLedger deletingLedger=new DelegatingTeamLedger(durableLedger){
            @Override public Optional<StoredDispatch> cancelOne(String workspaceId,String dispatchId,String reason,Instant cancelledAt){
                Optional<StoredDispatch> cancelled=super.cancelOne(workspaceId,dispatchId,reason,cancelledAt);
                members.delete(workspaceId,worker.id().toString());
                return cancelled;
            }
        };
        TeamApplicationService service=new TeamApplicationService(deletingLedger,members,(id,token)->true,
                notifierReturning(new DeliveryResult(true,null)),ignored->java.util.Set.of(),
                new PendingTaskProjection(durableLedger),Clock.systemUTC());
        TeamOperationResult sent=service.send(new SendTaskCommand(
                workspace,workspace+":orchestrator","token","Alice","Build it","4010"));

        TeamOperationResult cancelled=service.cancel(new dev.termestra.team.application.port.in.CancelTaskCommand(
                workspace,workspace+":orchestrator","token",sent.dispatchId(),"obsolete"));

        assertFalse(cancelled.forwarded());
        assertTrue(cancelled.forwardError().contains("dispatch was cancelled"));
        assertTrue(service.findDispatch(workspace,sent.dispatchId()).isEmpty(),
                "worker hard deletion removes its already-cancelled dispatch graph");
    }

    @Test void rebuildsStoppedWithPendingWorkFromTheDurableDispatchLedger() {
        SqliteDatabase database=database("rebuild.db");
        String workspace=UUID.randomUUID().toString();
        database.write("seed workspace",connection->{try(var statement=connection.prepareStatement("INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")){statement.setString(1,workspace);statement.setString(2,"Alpha");statement.setString(3,"/tmp/alpha");statement.setLong(4,System.currentTimeMillis());statement.executeUpdate();}return null;});
        JdbcTeamMemberRepository members=new JdbcTeamMemberRepository(database);
        TeamMember worker=TeamMember.create(WorkspaceId.parse(workspace),"Alice","Implement tasks",AgentRole.CODER,Instant.now());
        members.save(worker);
        JdbcTeamLedger ledger=new JdbcTeamLedger(database,new ObjectMapper());
        Instant now=Instant.now();
        Dispatch dispatch=Dispatch.create(WorkspaceId.parse(workspace),workspace+":orchestrator",worker.id(),new TaskText("Build it"),now);
        ledger.create(dispatch,new TeamMessage(workspace,worker.id().toString(),"send",workspace+":orchestrator",
                worker.id().toString(),"Build it",null,java.util.List.of(),now));
        AgentTeamNotifier unused=new AgentTeamNotifier(){
            @Override public DeliveryResult deliver(Dispatch value,TeamMember member,String port){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult report(Dispatch value,TeamMember member){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult status(String id,TeamMember member,String text,java.util.List<String> artifacts){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult cancel(Dispatch value,TeamMember member){return DeliveryResult.unavailable("unused");}
        };

        TeamApplicationService restarted=new TeamApplicationService(ledger,members,(id,token)->true,unused,
                ignored->java.util.Set.of(),new PendingTaskProjection(ledger),Clock.systemUTC());

        var view=restarted.listForUi(workspace).getFirst();
        assertEquals(1,view.pendingTaskCount());
        assertEquals("stopped",view.status());
    }

    @Test void hardDeletesWorkerStateAndRollsBackIfTheFinalDeleteFails() {
        SqliteDatabase database=database("worker.db");
        String workspace=UUID.randomUUID().toString(); String worker=UUID.randomUUID().toString(); long now=System.currentTimeMillis();
        database.write("seed worker graph",connection->{try(var statement=connection.createStatement()){
            statement.executeUpdate("INSERT INTO workspaces(id,name,path,created_at) VALUES('"+workspace+"','Alpha','/tmp/alpha',"+now+")");
            statement.executeUpdate("INSERT INTO workers(id,workspace_id,name,role,description,created_at) VALUES('"+worker+"','"+workspace+"','Alice','coder','',"+now+")");
            statement.executeUpdate("INSERT INTO messages(workspace_id,worker_id,type,text,artifacts,created_at) VALUES('"+workspace+"','"+worker+"','send','task','[]',"+now+")");
            statement.executeUpdate("INSERT INTO dispatches(id,workspace_id,to_agent_id,text,status,created_at,artifacts) VALUES('dispatch-1','"+workspace+"','"+worker+"','task','queued',"+now+",'[]')");
            statement.executeUpdate("INSERT INTO agent_launch_configs(workspace_id,agent_id,command,args_json,created_at,updated_at) VALUES('"+workspace+"','"+worker+"','cat','[]',"+now+","+now+")");
            statement.executeUpdate("INSERT INTO agent_sessions(agent_id,workspace_id,last_session_id,updated_at) VALUES('"+worker+"','"+workspace+"','session-1',"+now+")");
            statement.executeUpdate("INSERT INTO agent_runs(run_id,workspace_id,agent_id,status,started_at,created_at,updated_at) VALUES('run-1','"+workspace+"','"+worker+"','running',"+now+","+now+","+now+")");
            statement.execute("CREATE TRIGGER block_worker_delete BEFORE DELETE ON workers BEGIN SELECT RAISE(ABORT, 'blocked worker delete'); END");
        }return null;});
        JdbcTeamMemberRepository repository=new JdbcTeamMemberRepository(database);
        assertThrows(SqlitePersistenceException.class,()->repository.delete(workspace,worker));
        database.read("verify worker delete rollback",connection->{assertEquals(1,count(connection,"workers"));assertEquals(1,count(connection,"messages"));assertEquals(1,count(connection,"dispatches"));return null;});
        database.write("remove blocker",connection->{try(var statement=connection.createStatement()){statement.execute("DROP TRIGGER block_worker_delete");}return null;});
        assertTrue(repository.delete(workspace,worker));
        database.read("verify worker hard delete",connection->{for(String table:java.util.List.of("workers","messages","dispatches","agent_launch_configs","agent_sessions","agent_runs"))assertEquals(0,count(connection,table));assertEquals(1,count(connection,"workspaces"));return null;});
    }

    @Test void workerRemovalCannotOvertakeAnInFlightDispatchDelivery() throws Exception {
        SqliteDatabase database = database("send-delete-race.db");
        String workspace = seedWorkspace(database);
        JdbcTeamMemberRepository members = new JdbcTeamMemberRepository(database);
        TeamMember worker = TeamMember.create(WorkspaceId.parse(workspace), "Alice",
                "Implement tasks", AgentRole.CODER, Instant.now());
        members.save(worker);
        JdbcTeamLedger ledger = new JdbcTeamLedger(database, new ObjectMapper());
        CountDownLatch deliveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        AgentTeamNotifier notifier = new AgentTeamNotifier() {
            @Override public DeliveryResult deliver(Dispatch dispatch, TeamMember member, String port) {
                deliveryEntered.countDown();
                try {
                    if (!releaseDelivery.await(2, TimeUnit.SECONDS)) {
                        return DeliveryResult.unavailable("test delivery release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return DeliveryResult.unavailable("test delivery interrupted");
                }
                return new DeliveryResult(true, null);
            }
            @Override public DeliveryResult report(Dispatch dispatch, TeamMember member) { return DeliveryResult.unavailable("unused"); }
            @Override public DeliveryResult status(String id, TeamMember member, String text, List<String> artifacts) { return DeliveryResult.unavailable("unused"); }
            @Override public DeliveryResult cancel(Dispatch dispatch, TeamMember member) { return DeliveryResult.unavailable("unused"); }
        };
        RuntimeOperationCoordinator operations = new RuntimeOperationCoordinator();
        TeamApplicationService team = new TeamApplicationService(ledger, members, (id, token) -> true,
                notifier, ignored -> java.util.Set.of(), new PendingTaskProjection(ledger),
                Clock.systemUTC(), operations);
        AtomicBoolean runtimeCleaned = new AtomicBoolean();
        WorkerRemovalService removal = new WorkerRemovalService(team,
                (workspaceId, workerId) -> runtimeCleaned.set(true), operations);

        team.send(new SendTaskCommand(
                workspace, workspace + ":orchestrator", "token", "Alice", "Build it", "4010"));
        DispatchDeliveryApplicationService deliveries = new DispatchDeliveryApplicationService(
                ledger, members, notifier, operations, Clock.systemUTC());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sent = executor.submit(deliveries::processNext);
            assertTrue(deliveryEntered.await(1, TimeUnit.SECONDS));
            var deleted = executor.submit(() -> removal.remove(workspace, worker.id().toString()));

            assertThrows(TimeoutException.class, () -> deleted.get(100, TimeUnit.MILLISECONDS),
                    "worker deletion must wait until terminal delivery is resolved");
            releaseDelivery.countDown();
            assertTrue(sent.get(2, TimeUnit.SECONDS));
            deleted.get(2, TimeUnit.SECONDS);
        } finally {
            releaseDelivery.countDown();
        }

        assertTrue(runtimeCleaned.get());
        database.read("verify no orphan dispatch survives worker removal", connection -> {
            assertEquals(0, count(connection, "workers"));
            assertEquals(0, count(connection, "messages"));
            assertEquals(0, count(connection, "dispatches"));
            return null;
        });
    }

    private SqliteDatabase database(String name){SqliteDatabase database=new SqliteDatabase(tempDirectory.resolve(name));new SqliteSchemaMigrator(database,Clock.systemUTC()).migrate();return database;}
    private static String seedWorkspace(SqliteDatabase database){
        String workspace=UUID.randomUUID().toString();
        database.write("seed workspace",connection->{try(var statement=connection.prepareStatement("INSERT INTO workspaces(id,name,path,created_at) VALUES(?,?,?,?)")){statement.setString(1,workspace);statement.setString(2,"Alpha");statement.setString(3,"/tmp/alpha");statement.setLong(4,System.currentTimeMillis());statement.executeUpdate();}return null;});
        return workspace;
    }
    private static AgentTeamNotifier notifierReturning(DeliveryResult delivery){
        return new AgentTeamNotifier(){
            @Override public DeliveryResult deliver(Dispatch dispatch,TeamMember member,String port){return delivery;}
            @Override public DeliveryResult report(Dispatch dispatch,TeamMember member){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult status(String id,TeamMember member,String text,List<String> artifacts){return DeliveryResult.unavailable("unused");}
            @Override public DeliveryResult cancel(Dispatch dispatch,TeamMember member){return DeliveryResult.unavailable("unused");}
        };
    }
    private static class DelegatingTeamLedger implements TeamLedger {
        private final TeamLedger delegate;
        private DelegatingTeamLedger(TeamLedger delegate){this.delegate=delegate;}
        @Override public DispatchEnqueueResult enqueue(Dispatch dispatch,TeamMessage message,String runtimePort,String idempotencyKey){return delegate.enqueue(dispatch,message,runtimePort,idempotencyKey);}
        @Override public Optional<DispatchDeliveryWork> claimNextDelivery(String leaseOwner,Instant now,Instant leaseExpiresAt){return delegate.claimNextDelivery(leaseOwner,now,leaseExpiresAt);}
        @Override public void deferDeliveryClaim(String attemptId,String reason,Instant nextAttemptAt,Instant updatedAt){delegate.deferDeliveryClaim(attemptId,reason,nextAttemptAt,updatedAt);}
        @Override public void markDeliverySubmitted(String attemptId,Instant submittedAt){delegate.markDeliverySubmitted(attemptId,submittedAt);}
        @Override public void rescheduleDelivery(String attemptId,String error,Instant nextAttemptAt,Instant updatedAt){delegate.rescheduleDelivery(attemptId,error,nextAttemptAt,updatedAt);}
        @Override public void markDeliveryUncertain(String attemptId,String error,Instant updatedAt){delegate.markDeliveryUncertain(attemptId,error,updatedAt);}
        @Override public void markDeliveryFailed(String attemptId,String error,Instant updatedAt){delegate.markDeliveryFailed(attemptId,error,updatedAt);}
        @Override public int recoverInterruptedDeliveries(Instant recoveredAt){return delegate.recoverInterruptedDeliveries(recoveredAt);}
        @Override public boolean retryDelivery(String workspaceId,String dispatchId,Instant retriedAt){return delegate.retryDelivery(workspaceId,dispatchId,retriedAt);}
        @Override public long create(Dispatch dispatch,TeamMessage message){return delegate.create(dispatch,message);}
        @Override public void discardCreated(String dispatchId,long messageSequence){delegate.discardCreated(dispatchId,messageSequence);}
        @Override public Optional<StoredDispatch> reportOne(String workspaceId,String workerId,String dispatchId,String result,List<String> artifacts,Instant reportedAt,TeamMessage message){return delegate.reportOne(workspaceId,workerId,dispatchId,result,artifacts,reportedAt,message);}
        @Override public Optional<StoredDispatch> cancelOne(String workspaceId,String dispatchId,String reason,Instant cancelledAt){return delegate.cancelOne(workspaceId,dispatchId,reason,cancelledAt);}
        @Override public void append(TeamMessage message){delegate.append(message);}
        @Override public List<DispatchSummaryProjection> listSummaries(String workspaceId,String state,int limit,int offset){return delegate.listSummaries(workspaceId,state,limit,offset);}
        @Override public List<DispatchSummaryProjection> listDeliveryIssues(String workspaceId,int limit){return delegate.listDeliveryIssues(workspaceId,limit);}
        @Override public Optional<DispatchDetailProjection> findDetailById(String workspaceId,String dispatchId){return delegate.findDetailById(workspaceId,dispatchId);}
        @Override public void markDelivered(StoredDispatch dispatch){delegate.markDelivered(dispatch);}
    }
    private static int count(java.sql.Connection connection,String table)throws java.sql.SQLException{try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM "+table)){result.next();return result.getInt(1);}}
}
