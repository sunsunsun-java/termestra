package dev.termestra.execution.adapter.out.persistence;

import dev.termestra.execution.application.port.out.*;
import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.*;
import java.util.Optional;
import java.util.Set;

public final class JdbcAgentDirectory implements AgentDirectory {
    static final int MAX_WORKSPACE_NAME_CHARACTERS=256;
    static final int MAX_WORKSPACE_PATH_CHARACTERS=4_096;
    static final int MAX_WORKER_NAME_CHARACTERS=128;
    static final int MAX_WORKER_DESCRIPTION_CHARACTERS=4_096;
    static final int MAX_WORKER_ROLE_CHARACTERS=64;
    private static final Set<String> WORKER_ROLES=Set.of("coder","reviewer","tester","custom");
    private final SqliteDatabase database;
    public JdbcAgentDirectory(SqliteDatabase database) { this.database=database; }

    @Override public Optional<AgentDescriptor> find(String workspaceId,String agentId) {
        return database.read("find execution agent", connection -> {
            try (PreparedStatement workspace = connection.prepareStatement("SELECT substr(name,1,?) name,substr(path,1,?) path FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active'")) {
                workspace.setInt(1,MAX_WORKSPACE_NAME_CHARACTERS+1);
                workspace.setInt(2,MAX_WORKSPACE_PATH_CHARACTERS+1);
                workspace.setString(3,workspaceId);
                try (ResultSet result = workspace.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    String path=workspacePath(result.getString("path"),workspaceId);
                    String workspaceName=displayText(result.getString("name"),MAX_WORKSPACE_NAME_CHARACTERS);
                    if (agentId.equals(workspaceId+":orchestrator")) {
                        return Optional.of(new AgentDescriptor(workspaceId,workspaceName,path,agentId,"Orchestrator","协调团队并向用户交付结果","orchestrator"));
                    }
                    if(agentId.equals(workspaceId+":shell"))return Optional.of(new AgentDescriptor(workspaceId,workspaceName,path,agentId,"Shell","Workspace shell","shell"));
                    return findWorker(connection,workspaceId,workspaceName,agentId,path);
                }
            }
        });
    }

    private Optional<AgentDescriptor> findWorker(Connection connection,String workspaceId,String workspaceName,String agentId,String path) throws SQLException {
        try (PreparedStatement worker=connection.prepareStatement("SELECT substr(name,1,?) name,substr(COALESCE(description,''),1,?) description,substr(role,1,?) role FROM workers WHERE workspace_id=? AND id=? AND deleted_at IS NULL")) {
            worker.setInt(1,MAX_WORKER_NAME_CHARACTERS+1);
            worker.setInt(2,MAX_WORKER_DESCRIPTION_CHARACTERS+1);
            worker.setInt(3,MAX_WORKER_ROLE_CHARACTERS+1);
            worker.setString(4,workspaceId); worker.setString(5,agentId);
            try (ResultSet result=worker.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String name=result.getString("name");
                if(name==null||name.isBlank()||!name.equals(name.trim())||name.length()>MAX_WORKER_NAME_CHARACTERS){
                    throw new ExecutionConflict("Invalid persisted worker name for execution: "+agentId);
                }
                String role=result.getString("role");
                if(role==null||role.length()>MAX_WORKER_ROLE_CHARACTERS||!WORKER_ROLES.contains(role)){
                    throw new ExecutionConflict("Invalid persisted worker role for execution: "+agentId);
                }
                return Optional.of(new AgentDescriptor(workspaceId,workspaceName,path,agentId,name,
                        displayText(result.getString("description"),MAX_WORKER_DESCRIPTION_CHARACTERS),role));
            }
        }
    }

    private static String workspacePath(String value,String workspaceId){
        if(value==null||value.isBlank()||value.length()>MAX_WORKSPACE_PATH_CHARACTERS){
            throw new ExecutionConflict("Invalid persisted workspace path for execution: "+workspaceId);
        }
        try{
            if(!Path.of(value).isAbsolute())throw new ExecutionConflict(
                    "Invalid persisted workspace path for execution: "+workspaceId);
        }catch(InvalidPathException invalid){
            throw new ExecutionConflict("Invalid persisted workspace path for execution: "+workspaceId,invalid);
        }
        return value;
    }

    private static String displayText(String value,int maximum){
        if(value==null)return "";
        int end=Math.min(value.length(),maximum);
        if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))
                &&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.substring(0,end);
    }
}
