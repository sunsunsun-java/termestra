package dev.termestra.team.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.platform.persistence.sqlite.SqlitePersistenceException;
import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.TeamMember;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Owns the cross-context creation transaction for a TeamMember and its launch snapshot. */
public final class JdbcMemberProvisioningRepository implements MemberProvisioningRepository {
    private final SqliteDatabase database;
    private final ObjectMapper json;

    public JdbcMemberProvisioningRepository(SqliteDatabase database,ObjectMapper json){
        this.database=database;this.json=json;
    }

    @Override public void saveWithLaunch(TeamMember member,WorkerLaunchProvisioning provisioning){
        try{
            database.write("save member with launch configuration",connection->{
                insertMember(connection,member);
                switch(provisioning){
                    case WorkerLaunchProvisioning.Resolved resolved ->
                            insertResolved(connection,member,resolved.plan());
                    case WorkerLaunchProvisioning.SourceSnapshot snapshot ->
                            insertSnapshot(connection,member,snapshot);
                }
                return null;
            });
        }catch(SqlitePersistenceException failure){
            if(uniqueConstraint(failure))throw new TeamConflict("Worker already exists: "+member.name(),failure);
            throw failure;
        }
    }

    private static void insertMember(java.sql.Connection connection,TeamMember member)throws SQLException{
        try(PreparedStatement worker=connection.prepareStatement("""
                INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                SELECT ?,?,?,?,?,?
                WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active')
                  AND (SELECT COUNT(*) FROM workers WHERE workspace_id=? AND deleted_at IS NULL) < ?
                """)){
            worker.setString(1,member.id().toString());worker.setString(2,member.workspaceId().toString());
            worker.setString(3,member.name());worker.setString(4,member.description());
            worker.setString(5,member.role().wireValue());worker.setLong(6,member.createdAt().toEpochMilli());
            worker.setString(7,member.workspaceId().toString());worker.setString(8,member.workspaceId().toString());
            worker.setInt(9,TeamMemberRepository.MAX_MEMBERS_PER_WORKSPACE);
            if(worker.executeUpdate()!=1)throw new TeamConflict(
                    "Workspace is unavailable or its worker limit was reached");
        }
    }

    private void insertResolved(java.sql.Connection connection,TeamMember member,WorkerLaunchPlan plan)
            throws SQLException{
        try(PreparedStatement launch=connection.prepareStatement("""
                INSERT INTO agent_launch_configs(
                  workspace_id,agent_id,command,args_json,command_preset_id,interactive_command,
                  preset_augmentation_disabled,resume_args_template,session_id_capture_json,env_json,
                  model_id,revision,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,1,?,?)
                """)){
            launch.setString(1,member.workspaceId().toString());launch.setString(2,member.id().toString());
            launch.setString(3,plan.command());launch.setString(4,write(plan.arguments(),"arguments"));
            launch.setString(5,plan.commandPresetId());launch.setString(6,plan.interactiveCommand());
            launch.setInt(7,plan.presetAugmentationDisabled()?1:0);
            launch.setString(8,plan.resumeArgsTemplate());launch.setString(9,plan.sessionIdCaptureJson());
            launch.setString(10,write(plan.environment(),"environment"));launch.setString(11,plan.modelId());
            launch.setLong(12,member.createdAt().toEpochMilli());launch.setLong(13,member.createdAt().toEpochMilli());
            launch.executeUpdate();
        }
    }

    private static void insertSnapshot(java.sql.Connection connection,TeamMember member,
                                       WorkerLaunchProvisioning.SourceSnapshot snapshot)throws SQLException{
        try(PreparedStatement launch=connection.prepareStatement("""
                INSERT INTO agent_launch_configs(
                  workspace_id,agent_id,command,args_json,command_preset_id,interactive_command,
                  preset_augmentation_disabled,resume_args_template,session_id_capture_json,env_json,
                  model_id,revision,created_at,updated_at)
                SELECT ?,?,command,args_json,command_preset_id,interactive_command,
                       preset_augmentation_disabled,resume_args_template,session_id_capture_json,env_json,
                       model_id,1,?,?
                FROM agent_launch_configs
                WHERE workspace_id=? AND agent_id=? AND command_preset_id IS NOT NULL
                  AND (? IS NULL OR revision=?)
                """)){
            long now=member.createdAt().toEpochMilli();
            launch.setString(1,member.workspaceId().toString());launch.setString(2,member.id().toString());
            launch.setLong(3,now);launch.setLong(4,now);launch.setString(5,member.workspaceId().toString());
            launch.setString(6,snapshot.sourceAgentId());
            if(snapshot.expectedSourceRevision()==null){launch.setObject(7,null);launch.setObject(8,null);}
            else{launch.setLong(7,snapshot.expectedSourceRevision());launch.setLong(8,snapshot.expectedSourceRevision());}
            if(launch.executeUpdate()!=1)throw new ExecutionConflict("ORCHESTRATOR_LAUNCH_CHANGED",
                    "ORCHESTRATOR_LAUNCH_CHANGED: launch snapshot is unavailable or stale");
        }
    }

    private String write(Object value,String field)throws SQLException{
        try{return json.writeValueAsString(value);}
        catch(JsonProcessingException failure){throw new SQLException("invalid launch "+field,failure);}
    }

    private static boolean uniqueConstraint(Throwable failure){
        for(Throwable current=failure;current!=null;current=current.getCause()){
            if(current instanceof SQLiteException sqlite
                    && (sqlite.getResultCode()==SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                    ||sqlite.getResultCode()==SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY))return true;
        }
        return false;
    }
}
