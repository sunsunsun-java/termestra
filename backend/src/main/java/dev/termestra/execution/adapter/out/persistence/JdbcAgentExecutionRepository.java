package dev.termestra.execution.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.execution.application.port.in.ExecutionInputLimits;
import dev.termestra.execution.application.port.out.AgentExecutionRepository;
import dev.termestra.execution.domain.model.*;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class JdbcAgentExecutionRepository implements AgentExecutionRepository {
    private static final int JSON_MAXIMUM_ESCAPE_EXPANSION = 6;
    private static final int MAX_ARGUMENTS_JSON_CHARACTERS =
            JSON_MAXIMUM_ESCAPE_EXPANSION * ExecutionInputLimits.MAX_ARGUMENT_TOTAL_CHARACTERS
                    + 3 * ExecutionInputLimits.MAX_ARGUMENTS + 2;
    private static final int MAX_ENVIRONMENT_JSON_CHARACTERS =
            JSON_MAXIMUM_ESCAPE_EXPANSION * ExecutionInputLimits.MAX_ENVIRONMENT_TOTAL_CHARACTERS
                    + 6 * ExecutionInputLimits.MAX_ENVIRONMENT_ENTRIES + 2;
    private final SqliteDatabase database;private final ObjectMapper json;
    public JdbcAgentExecutionRepository(SqliteDatabase database,ObjectMapper json){this.database=database;this.json=json;}
    @Override public boolean saveConfiguration(String workspace,String agent,AgentLaunchConfiguration config,Instant at){return database.write("save launch configuration",c->saveConfiguration(c,workspace,agent,config,at));}

    private boolean saveConfiguration(Connection c,String workspace,String agent,AgentLaunchConfiguration config,Instant at)throws SQLException{String sql="""
            INSERT INTO agent_launch_configs(workspace_id,agent_id,command,args_json,command_preset_id,interactive_command,preset_augmentation_disabled,resume_args_template,session_id_capture_json,env_json,model_id,revision,created_at,updated_at)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,1,?,?
            WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active')
              AND (?=?||':orchestrator' OR ?=?||':shell' OR EXISTS(
                SELECT 1 FROM workers WHERE workspace_id=? AND id=? AND deleted_at IS NULL))
            ON CONFLICT(workspace_id,agent_id) DO UPDATE SET command=excluded.command,args_json=excluded.args_json,command_preset_id=excluded.command_preset_id,interactive_command=excluded.interactive_command,preset_augmentation_disabled=excluded.preset_augmentation_disabled,resume_args_template=excluded.resume_args_template,session_id_capture_json=excluded.session_id_capture_json,env_json=excluded.env_json,model_id=excluded.model_id,revision=agent_launch_configs.revision+1,updated_at=excluded.updated_at
            """;try(PreparedStatement ps=c.prepareStatement(sql)){ps.setString(1,workspace);ps.setString(2,agent);ps.setString(3,config.command());ps.setString(4,json(config.arguments()));ps.setString(5,config.commandPresetId());ps.setString(6,config.interactiveCommand());ps.setInt(7,config.presetAugmentationDisabled()?1:0);ps.setString(8,config.resumeArgsTemplate());ps.setString(9,config.sessionIdCaptureJson());ps.setString(10,json(config.environment()));ps.setString(11,config.modelId());ps.setLong(12,at.toEpochMilli());ps.setLong(13,at.toEpochMilli());ps.setString(14,workspace);ps.setString(15,agent);ps.setString(16,workspace);ps.setString(17,agent);ps.setString(18,workspace);ps.setString(19,workspace);ps.setString(20,agent);return ps.executeUpdate()==1;}}

    @Override public Optional<AgentLaunchConfiguration> copyConfigurationSnapshot(String workspace,String sourceAgent,String targetAgent,Long expectedSourceRevision,Instant at){return database.write("copy launch configuration snapshot",connection->{AgentLaunchConfiguration source=findConfiguration(connection,workspace,sourceAgent).orElse(null);if(source==null||source.commandPresetId()==null)return Optional.empty();if(expectedSourceRevision!=null&&source.revision()!=expectedSourceRevision)return Optional.empty();AgentLaunchConfiguration snapshot=new AgentLaunchConfiguration(source.command(),source.arguments(),source.commandPresetId(),source.interactiveCommand(),source.presetAugmentationDisabled(),source.resumeArgsTemplate(),source.sessionIdCaptureJson(),source.environment(),source.modelId(),1);return saveConfiguration(connection,workspace,targetAgent,snapshot,at)?findConfiguration(connection,workspace,targetAgent):Optional.empty();});}
    @Override public Optional<AgentLaunchConfiguration> findConfiguration(String workspace,String agent){
        return database.read("find launch configuration",connection->{
            String sql="""
                    SELECT substr(command,1,?) command,
                           substr(args_json,1,?) args_json,
                           substr(command_preset_id,1,?) command_preset_id,
                           substr(interactive_command,1,?) interactive_command,
                           preset_augmentation_disabled,
                           substr(resume_args_template,1,?) resume_args_template,
                           substr(session_id_capture_json,1,?) session_id_capture_json,
                           substr(env_json,1,?) env_json,
                           substr(model_id,1,?) model_id,
                           revision
                    FROM agent_launch_configs WHERE workspace_id=? AND agent_id=?
                    """;
            try(PreparedStatement statement=connection.prepareStatement(sql)){
                statement.setInt(1,ExecutionInputLimits.MAX_COMMAND_CHARACTERS+1);
                statement.setInt(2,MAX_ARGUMENTS_JSON_CHARACTERS+1);
                statement.setInt(3,ExecutionInputLimits.MAX_PRESET_ID_CHARACTERS+1);
                statement.setInt(4,ExecutionInputLimits.MAX_COMMAND_CHARACTERS+1);
                statement.setInt(5,ExecutionInputLimits.MAX_COMMAND_CHARACTERS+1);
                statement.setInt(6,ExecutionInputLimits.MAX_CAPTURE_JSON_CHARACTERS+1);
                statement.setInt(7,MAX_ENVIRONMENT_JSON_CHARACTERS+1);
                statement.setInt(8,ExecutionInputLimits.MAX_MODEL_ID_CHARACTERS+1);
                statement.setString(9,workspace);statement.setString(10,agent);
                try(ResultSet result=statement.executeQuery()){
                    return result.next()?Optional.of(configuration(result,workspace,agent)):Optional.empty();
                }
            }
        });
    }
    private Optional<AgentLaunchConfiguration> findConfiguration(Connection connection,String workspace,String agent)throws SQLException{
        String sql="""
                SELECT command,args_json,command_preset_id,interactive_command,preset_augmentation_disabled,
                       resume_args_template,session_id_capture_json,env_json,model_id,revision
                FROM agent_launch_configs WHERE workspace_id=? AND agent_id=?
                """;
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setString(1,workspace);statement.setString(2,agent);try(ResultSet result=statement.executeQuery()){return result.next()?Optional.of(configuration(result,workspace,agent)):Optional.empty();}}
    }
    @Override public boolean insertRun(String runId,String workspaceId,String agentId,long pid,RunStatus status,Instant started){return database.write("insert agent run",c->{String sql="""
            INSERT INTO agent_runs(run_id,workspace_id,agent_id,pid,status,started_at,created_at,updated_at)
            SELECT ?,?,?,?,?,?,?,?
            WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active')
              AND (?=?||':orchestrator' OR ?=?||':shell' OR EXISTS(
                SELECT 1 FROM workers WHERE workspace_id=? AND id=? AND deleted_at IS NULL))
            """;try(PreparedStatement ps=c.prepareStatement(sql)){ps.setString(1,runId);ps.setString(2,workspaceId);ps.setString(3,agentId);ps.setLong(4,pid);ps.setString(5,status.wireValue());ps.setLong(6,started.toEpochMilli());ps.setLong(7,started.toEpochMilli());ps.setLong(8,started.toEpochMilli());ps.setString(9,workspaceId);ps.setString(10,agentId);ps.setString(11,workspaceId);ps.setString(12,agentId);ps.setString(13,workspaceId);ps.setString(14,workspaceId);ps.setString(15,agentId);return ps.executeUpdate()==1;}});}
    @Override public boolean markRunning(String runId,Instant at){return database.write("mark agent run running",c->{try(PreparedStatement ps=c.prepareStatement("UPDATE agent_runs SET status='running',updated_at=? WHERE run_id=? AND status='starting'")){ps.setLong(1,at.toEpochMilli());ps.setString(2,runId);return ps.executeUpdate()==1;}});}
    @Override public boolean finishRun(String runId,RunStatus status,Integer exitCode,Instant ended,String workspace,String agent,String failedResumeSession){return database.write("finish agent run",c->{int updated;try(PreparedStatement ps=c.prepareStatement("UPDATE agent_runs SET status=?,exit_code=?,ended_at=?,updated_at=? WHERE run_id=? AND status IN ('starting','running')")){ps.setString(1,status.wireValue());if(exitCode==null)ps.setNull(2,Types.INTEGER);else ps.setInt(2,exitCode);ps.setLong(3,ended.toEpochMilli());ps.setLong(4,ended.toEpochMilli());ps.setString(5,runId);updated=ps.executeUpdate();}if(updated==1&&failedResumeSession!=null){try(PreparedStatement clear=c.prepareStatement("DELETE FROM agent_sessions WHERE workspace_id=? AND agent_id=? AND last_session_id=?")){clear.setString(1,workspace);clear.setString(2,agent);clear.setString(3,failedResumeSession);clear.executeUpdate();}}return updated==1;});}
    @Override public void markUnfinishedRunsStale(Instant at){database.write("mark stale agent runs",c->{try(PreparedStatement ps=c.prepareStatement("UPDATE agent_runs SET status='error',ended_at=?,updated_at=? WHERE status IN ('starting','running')")){ps.setLong(1,at.toEpochMilli());ps.setLong(2,at.toEpochMilli());ps.executeUpdate();}return null;});}
    @Override public Optional<String> findLastSession(String workspace,String agent){return database.read("find agent session",c->{try(PreparedStatement ps=c.prepareStatement("SELECT substr(last_session_id,1,?) last_session_id FROM agent_sessions WHERE workspace_id=? AND agent_id=?")){ps.setInt(1,ExecutionInputLimits.MAX_SESSION_ID_CHARACTERS+1);ps.setString(2,workspace);ps.setString(3,agent);try(ResultSet rs=ps.executeQuery()){if(!rs.next())return Optional.empty();try{return Optional.of(ExecutionInputLimits.sessionId(rs.getString("last_session_id")));}catch(IllegalArgumentException invalid){throw new ExecutionConflict("Invalid persisted session id for agent "+agent,invalid);}}}});}
    @Override public boolean saveLastSession(String workspace,String agent,String runId,String session,Instant at){String validatedSession;try{validatedSession=ExecutionInputLimits.sessionId(session);}catch(IllegalArgumentException invalid){throw new ExecutionConflict("Invalid session id for agent "+agent,invalid);}return database.write("save agent session",c->{String sql="""
            INSERT INTO agent_sessions(workspace_id,agent_id,last_session_id,updated_at)
            SELECT ?,?,?,?
            WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active')
              AND (?=?||':orchestrator' OR ?=?||':shell' OR EXISTS(
                SELECT 1 FROM workers WHERE workspace_id=? AND id=? AND deleted_at IS NULL))
              AND EXISTS(SELECT 1 FROM agent_runs
                         WHERE run_id=? AND workspace_id=? AND agent_id=?
                           AND status IN ('starting','running'))
            ON CONFLICT(workspace_id,agent_id) DO UPDATE SET last_session_id=excluded.last_session_id,updated_at=excluded.updated_at
            """;try(PreparedStatement ps=c.prepareStatement(sql)){ps.setString(1,workspace);ps.setString(2,agent);ps.setString(3,validatedSession);ps.setLong(4,at.toEpochMilli());ps.setString(5,workspace);ps.setString(6,agent);ps.setString(7,workspace);ps.setString(8,agent);ps.setString(9,workspace);ps.setString(10,workspace);ps.setString(11,agent);ps.setString(12,runId);ps.setString(13,workspace);ps.setString(14,agent);return ps.executeUpdate()==1;}});}
    @Override public void clearLastSession(String workspace,String agent){database.write("clear agent session",c->{try(PreparedStatement ps=c.prepareStatement("DELETE FROM agent_sessions WHERE workspace_id=? AND agent_id=?")){ps.setString(1,workspace);ps.setString(2,agent);ps.executeUpdate();}return null;});}
    private String json(List<String> values)throws SQLException{try{return json.writeValueAsString(values);}catch(JsonProcessingException error){throw new SQLException("invalid launch arguments",error);}}
    private String json(Map<String,String> values)throws SQLException{try{return json.writeValueAsString(values);}catch(JsonProcessingException error){throw new SQLException("invalid launch environment",error);}}
    private AgentLaunchConfiguration configuration(ResultSet result,String workspace,String agent)throws SQLException{
        try{
            String command=ExecutionInputLimits.command(result.getString("command"));
            List<String> arguments=arguments(result.getString("args_json"));
            String preset=ExecutionInputLimits.optionalPresetId(result.getString("command_preset_id"));
            String interactive=ExecutionInputLimits.optionalCommand(result.getString("interactive_command"),"interactive_command");
            String resume=ExecutionInputLimits.optionalCommand(result.getString("resume_args_template"),"resume_args_template");
            String capture=ExecutionInputLimits.optionalCaptureJson(result.getString("session_id_capture_json"));
            validateCaptureJson(capture);
            Map<String,String> environment=environment(result.getString("env_json"));
            String modelId=ExecutionInputLimits.optionalModelId(result.getString("model_id"));
            long revision=result.getLong("revision");
            return new AgentLaunchConfiguration(command,arguments,preset,interactive,
                    result.getInt("preset_augmentation_disabled")==1,resume,capture,environment,modelId,
                    Math.max(1,revision));
        }catch(IllegalArgumentException|IOException invalid){
            throw new ExecutionConflict("Invalid persisted launch configuration for agent "+agent+
                    " in workspace "+workspace,invalid);
        }
    }

    private List<String> arguments(String value)throws IOException{
        if(value==null)throw new IllegalArgumentException("persisted args_json must be an array");
        if(value.length()>MAX_ARGUMENTS_JSON_CHARACTERS)throw new IllegalArgumentException("persisted args_json exceeds its serialized limit");
        List<String> result=new ArrayList<>();long total=0;
        try(JsonParser parser=json.createParser(value)){
            if(parser.nextToken()!=JsonToken.START_ARRAY)throw new IllegalArgumentException("persisted args_json must be an array");
            JsonToken token;
            while((token=parser.nextToken())!=JsonToken.END_ARRAY){
                if(token==null||token!=JsonToken.VALUE_STRING)throw new IllegalArgumentException("persisted args_json must contain only strings");
                if(result.size()==ExecutionInputLimits.MAX_ARGUMENTS)throw new IllegalArgumentException("persisted args_json has too many entries");
                String argument=parser.getText();
                if(argument.length()>ExecutionInputLimits.MAX_ARGUMENT_CHARACTERS)throw new IllegalArgumentException("persisted argument is too long");
                total+=argument.length();
                if(total>ExecutionInputLimits.MAX_ARGUMENT_TOTAL_CHARACTERS)throw new IllegalArgumentException("persisted arguments exceed their total limit");
                result.add(argument);
            }
            if(parser.nextToken()!=null)throw new IllegalArgumentException("persisted args_json has trailing content");
        }
        return List.copyOf(result);
    }

    private Map<String,String> environment(String value)throws IOException{
        if(value==null)throw new IllegalArgumentException("persisted env_json must be an object");
        if(value.length()>MAX_ENVIRONMENT_JSON_CHARACTERS)throw new IllegalArgumentException("persisted env_json exceeds its serialized limit");
        Map<String,String> result=new LinkedHashMap<>();int entries=0;long total=0;
        try(JsonParser parser=json.createParser(value)){
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IllegalArgumentException("persisted env_json must be an object");
            JsonToken token;
            while((token=parser.nextToken())!=JsonToken.END_OBJECT){
                if(token==null||token!=JsonToken.FIELD_NAME)throw new IllegalArgumentException("persisted env_json is malformed");
                if(entries++==ExecutionInputLimits.MAX_ENVIRONMENT_ENTRIES)throw new IllegalArgumentException("persisted env_json has too many entries");
                String key=parser.currentName();
                if(parser.nextToken()!=JsonToken.VALUE_STRING)throw new IllegalArgumentException("persisted env_json values must be strings");
                String environmentValue=parser.getText();
                if(key==null||key.isBlank())throw new IllegalArgumentException("persisted env_json contains a blank key");
                if(key.length()>ExecutionInputLimits.MAX_ENVIRONMENT_KEY_CHARACTERS)throw new IllegalArgumentException("persisted env_json key is too long");
                if(environmentValue.length()>ExecutionInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS)throw new IllegalArgumentException("persisted env_json value is too long");
                total+=key.length()+environmentValue.length();
                if(total>ExecutionInputLimits.MAX_ENVIRONMENT_TOTAL_CHARACTERS)throw new IllegalArgumentException("persisted environment exceeds its total limit");
                result.put(key,environmentValue);
            }
            if(parser.nextToken()!=null)throw new IllegalArgumentException("persisted env_json has trailing content");
        }
        return Map.copyOf(result);
    }

    private void validateCaptureJson(String value)throws JsonProcessingException{
        if(value==null)return;
        try(JsonParser parser=json.createParser(value)){
            var parsed=json.readTree(parser);
            if(parsed==null||!parsed.isObject())throw new IllegalArgumentException("persisted session_id_capture_json must be an object");
            if(parser.nextToken()!=null)throw new IllegalArgumentException("persisted session_id_capture_json has trailing content");
        }catch(IOException invalid){
            if(invalid instanceof JsonProcessingException jsonFailure)throw jsonFailure;
            throw new IllegalArgumentException("persisted session_id_capture_json could not be read",invalid);
        }
    }
}
