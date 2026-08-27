package dev.termestra.team.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.team.application.port.out.ScenarioMemberProvisioningRepository;
import dev.termestra.team.application.port.out.WorkerLaunchPlan;
import dev.termestra.team.domain.model.TeamMember;
import dev.termestra.team.application.exception.TeamConflict;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class JdbcScenarioMemberProvisioningRepository implements ScenarioMemberProvisioningRepository {
    private final SqliteDatabase database;
    private final ObjectMapper json;

    public JdbcScenarioMemberProvisioningRepository(SqliteDatabase database, ObjectMapper json) {
        this.database = database;
        this.json = json;
    }

    @Override public void saveWithLaunch(TeamMember member, WorkerLaunchPlan launchPlan) {
        database.write("save scenario member with launch configuration", connection -> {
            try (PreparedStatement worker = connection.prepareStatement(
                    """
                    INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                    SELECT ?,?,?,?,?,?
                    WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active')
                      AND (SELECT COUNT(*) FROM workers WHERE workspace_id=? AND deleted_at IS NULL) < ?
                    """)) {
                worker.setString(1, member.id().toString());
                worker.setString(2, member.workspaceId().toString());
                worker.setString(3, member.name());
                worker.setString(4, member.description());
                worker.setString(5, member.role().wireValue());
                worker.setLong(6, member.createdAt().toEpochMilli());
                worker.setString(7,member.workspaceId().toString());
                worker.setString(8,member.workspaceId().toString());
                worker.setInt(9,dev.termestra.team.application.port.out.TeamMemberRepository.MAX_MEMBERS_PER_WORKSPACE);
                if(worker.executeUpdate()!=1)throw new TeamConflict("Workspace is unavailable or its worker limit was reached");
            }
            try (PreparedStatement launch = connection.prepareStatement("""
                    INSERT INTO agent_launch_configs(
                      workspace_id,agent_id,command,args_json,command_preset_id,interactive_command,
                      preset_augmentation_disabled,resume_args_template,session_id_capture_json,env_json,created_at,updated_at)
                    VALUES(?,?,?,?,?,NULL,0,?,?,?,?,?)
                    """)) {
                launch.setString(1, member.workspaceId().toString());
                launch.setString(2, member.id().toString());
                launch.setString(3, launchPlan.command());
                launch.setString(4, writeArguments(launchPlan));
                launch.setString(5, launchPlan.commandPresetId());
                launch.setString(6, launchPlan.resumeArgsTemplate());
                launch.setString(7, launchPlan.sessionIdCaptureJson());
                launch.setString(8, writeEnvironment(launchPlan));
                launch.setLong(9, member.createdAt().toEpochMilli());
                launch.setLong(10, member.createdAt().toEpochMilli());
                launch.executeUpdate();
            }
            return null;
        });
    }

    private String writeArguments(WorkerLaunchPlan launchPlan) throws SQLException {
        try { return json.writeValueAsString(launchPlan.arguments()); }
        catch (JsonProcessingException failure) {
            throw new SQLException("invalid scenario launch arguments", failure);
        }
    }

    private String writeEnvironment(WorkerLaunchPlan launchPlan) throws SQLException {
        try { return json.writeValueAsString(launchPlan.environment()); }
        catch (JsonProcessingException failure) {
            throw new SQLException("invalid scenario launch environment", failure);
        }
    }
}
