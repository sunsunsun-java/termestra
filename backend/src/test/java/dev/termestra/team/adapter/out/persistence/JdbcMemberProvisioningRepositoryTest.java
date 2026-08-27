package dev.termestra.team.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqliteSchemaMigrator;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.port.out.WorkerLaunchPlan;
import dev.termestra.team.application.port.out.WorkerLaunchProvisioning;
import dev.termestra.team.domain.model.AgentRole;
import dev.termestra.team.domain.model.TeamMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JdbcMemberProvisioningRepositoryTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"),ZoneOffset.UTC);
    @TempDir Path temporaryDirectory;

    @Test void rollsBackMemberWhenResolvedLaunchCannotBeInserted(){
        Fixture fixture=fixture();
        TeamMember member=fixture.member("Atomic");
        fixture.database.write("reserve conflicting launch",connection->{
            try(var statement=connection.prepareStatement("""
                    INSERT INTO agent_launch_configs(workspace_id,agent_id,command,args_json,created_at,updated_at)
                    VALUES(?,?,'existing','[]',1,1)
                    """)){
                statement.setString(1,fixture.workspaceId);statement.setString(2,member.id().toString());
                statement.executeUpdate();
            }
            return null;
        });

        WorkerLaunchPlan plan=new WorkerLaunchPlan("codex",List.of("--model","gpt-test"),
                "codex",null,null,null,Map.of(),"gpt-test",true);
        assertThrows(TeamConflict.class,()->fixture.repository.saveWithLaunch(member,
                new WorkerLaunchProvisioning.Resolved(plan)));
        assertEquals(0,fixture.memberCount(member.id().toString()));
    }

    @Test void staleSourceRevisionRollsBackMemberAndSnapshot(){
        Fixture fixture=fixture();
        TeamMember member=fixture.member("Snapshot");
        fixture.database.write("save orchestrator source",connection->{
            try(var statement=connection.prepareStatement("""
                    INSERT INTO agent_launch_configs(
                      workspace_id,agent_id,command,args_json,command_preset_id,env_json,model_id,revision,created_at,updated_at)
                    VALUES(?,?,'codex','[]','codex','{}','gpt-test',2,1,1)
                    """)){
                statement.setString(1,fixture.workspaceId);
                statement.setString(2,fixture.workspaceId+":orchestrator");statement.executeUpdate();
            }
            return null;
        });

        ExecutionConflict conflict=assertThrows(ExecutionConflict.class,()->fixture.repository.saveWithLaunch(
                member,new WorkerLaunchProvisioning.SourceSnapshot(
                        fixture.workspaceId+":orchestrator",1L)));
        assertEquals("ORCHESTRATOR_LAUNCH_CHANGED",conflict.errorCode());
        assertEquals(0,fixture.memberCount(member.id().toString()));
        assertEquals(0,fixture.launchCount(member.id().toString()));
    }

    private Fixture fixture(){
        SqliteDatabase database=new SqliteDatabase(temporaryDirectory.resolve(java.util.UUID.randomUUID()+".db"));
        new SqliteSchemaMigrator(database,CLOCK).migrate();
        String workspaceId=WorkspaceId.newId().toString();
        database.write("save active workspace",connection->{
            try(var statement=connection.prepareStatement("""
                    INSERT INTO workspaces(id,name,path,canonical_path,canonical_path_owner,lifecycle_state,created_at)
                    VALUES(?, 'Workspace', ?, ?, 1, 'active', 1)
                    """)){
                String path=temporaryDirectory.resolve(workspaceId).toString();
                statement.setString(1,workspaceId);statement.setString(2,path);statement.setString(3,path);
                statement.executeUpdate();
            }
            return null;
        });
        return new Fixture(database,workspaceId,
                new JdbcMemberProvisioningRepository(database,new ObjectMapper()));
    }

    private record Fixture(SqliteDatabase database,String workspaceId,
                           JdbcMemberProvisioningRepository repository){
        TeamMember member(String name){return TeamMember.create(WorkspaceId.parse(workspaceId),name,null,
                AgentRole.CODER,Instant.now(CLOCK));}
        int memberCount(String agentId){return count("workers",agentId);}
        int launchCount(String agentId){return count("agent_launch_configs",agentId);}
        private int count(String table,String agentId){return database.read("count "+table,connection->{
            try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE workspace_id=? AND "+
                    (table.equals("workers")?"id":"agent_id")+"=?")){
                statement.setString(1,workspaceId);statement.setString(2,agentId);
                try(var result=statement.executeQuery()){result.next();return result.getInt(1);}
            }
        });}
    }
}
