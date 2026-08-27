package dev.termestra.execution.adapter.out.persistence;

import dev.termestra.execution.application.port.out.AgentRecoveryContextProvider;
import dev.termestra.execution.application.exception.ExecutionConflict;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class JdbcAgentRecoveryContextProvider implements AgentRecoveryContextProvider {
    static final int MAX_RECOVERY_MESSAGES = 256;
    static final int MAX_RECOVERY_MESSAGE_CHARS = 4_096;
    static final int MAX_RECOVERY_TASKS_CHARS = 1_536;
    static final int MAX_RECOVERY_WORKERS = 256;
    static final int MAX_RECOVERY_WORKER_ID_CHARS = 256;
    static final int MAX_RECOVERY_WORKER_NAME_CHARS = 128;
    static final int MAX_RECOVERY_WORKER_ROLE_CHARS = 64;
    static final int MAX_RECOVERY_WORKSPACE_PATH_CHARS = 4_096;
    static final int MAX_RECOVERY_AGENT_ID_CHARS = 256;
    static final int MAX_RECOVERY_STATUS_CHARS = 64;
    private static final Set<String> RECOVERY_WORKER_ROLES=Set.of("coder","reviewer","tester","custom");
    private final SqliteDatabase database;
    public JdbcAgentRecoveryContextProvider(SqliteDatabase database) { this.database = database; }

    @Override public boolean hasPreviousRun(String agentId, String currentRunId) {
        return database.read("find previous agent run", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM agent_runs WHERE agent_id=? AND run_id<>? LIMIT 1")) {
                statement.setString(1,agentId); statement.setString(2,currentRunId);
                return statement.executeQuery().next();
            }
        });
    }

    @Override public RecoveryContext load(String workspaceId, Instant recentSince) {
        return database.read("load agent recovery context", connection -> new RecoveryContext(
                readTasks(workspacePath(connection,workspaceId)),
                recentMessages(connection,workspaceId,recentSince.toEpochMilli()),
                openDispatches(connection,workspaceId), workers(connection,workspaceId)));
    }

    @Override public long appendSystemRecoveryMessage(String workspaceId, String agentId, String text, Instant at) {
        return appendMessage(workspaceId,agentId,"system_recovery_summary",text,at);
    }

    @Override public long appendUserInput(String workspaceId,String agentId,String text,Instant at){
        return appendMessage(workspaceId,agentId,"user_input",text,at);
    }

    private long appendMessage(String workspaceId,String agentId,String type,String text,Instant at){
        return database.write("append system recovery summary", connection -> {
            String sql="""
                    INSERT INTO messages(workspace_id,worker_id,type,to_agent_id,text,artifacts,created_at)
                    SELECT ?,?,?,?,?,'[]',?
                    WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active')
                      AND (?=?||':orchestrator' OR ?=?||':shell' OR EXISTS(
                        SELECT 1 FROM workers WHERE workspace_id=? AND id=? AND deleted_at IS NULL))
                    RETURNING sequence
                    """;
            try (PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1,workspaceId);statement.setString(2,agentId);statement.setString(3,type);statement.setString(4,agentId);
                statement.setString(5,text);statement.setLong(6,at.toEpochMilli());statement.setString(7,workspaceId);
                statement.setString(8,agentId);statement.setString(9,workspaceId);statement.setString(10,agentId);
                statement.setString(11,workspaceId);statement.setString(12,workspaceId);statement.setString(13,agentId);
                try(ResultSet result=statement.executeQuery()){
                    if(result.next())return result.getLong(1);
                    throw new SQLException("workspace or recovery agent is no longer active");
                }
            }
        });
    }

    @Override public void deleteMessage(long sequence) {
        database.write("rollback runtime message", connection -> {
            try(PreparedStatement statement=connection.prepareStatement("DELETE FROM messages WHERE sequence=?")){
                statement.setLong(1,sequence);statement.executeUpdate();
            }
            return null;
        });
    }

    private static String workspacePath(Connection connection,String workspaceId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("SELECT substr(path,1,?) path FROM workspaces WHERE id=? AND deleted_at IS NULL AND lifecycle_state='active'")){
            statement.setInt(1,MAX_RECOVERY_WORKSPACE_PATH_CHARS+1);statement.setString(2,workspaceId);try(ResultSet result=statement.executeQuery()){
                if(!result.next())throw new SQLException("workspace not found: "+workspaceId);
                String path=result.getString("path");
                if(path==null||path.isBlank()||path.length()>MAX_RECOVERY_WORKSPACE_PATH_CHARS){
                    throw new ExecutionConflict("Invalid persisted workspace path for recovery: "+workspaceId);
                }
                try{
                    if(!Path.of(path).isAbsolute())throw new ExecutionConflict(
                            "Invalid persisted workspace path for recovery: "+workspaceId);
                }catch(InvalidPathException invalid){
                    throw new ExecutionConflict("Invalid persisted workspace path for recovery: "+workspaceId,invalid);
                }
                return path;
            }
        }
    }
    private static String readTasks(String workspacePath)throws SQLException{
        Path directory=Path.of(workspacePath).toAbsolutePath().normalize().resolve(".termestra");
        Path tasks=directory.resolve("tasks.md");
        if(!Files.exists(directory,java.nio.file.LinkOption.NOFOLLOW_LINKS))return "";
        if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            throw new SQLException("workspace metadata directory must be a real directory: "+directory);
        }
        if(!Files.exists(tasks,java.nio.file.LinkOption.NOFOLLOW_LINKS))return "";
        if(Files.isSymbolicLink(tasks)||!Files.isRegularFile(tasks,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            throw new SQLException("workspace tasks file must be a regular file: "+tasks);
        }
        try(Reader reader=new InputStreamReader(Files.newInputStream(tasks,
                java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS),
                StandardCharsets.UTF_8)){
            return readTasksPrefix(reader);
        }catch(IOException error){throw new SQLException("failed to read tasks for recovery",error);}
    }

    static String readTasksPrefix(Reader reader)throws IOException{
            char[] head=new char[MAX_RECOVERY_TASKS_CHARS];int count=0;
            while(count<head.length){
                int read=reader.read(head,count,head.length-count);
                if(read<0)break;
                if(read==0){
                    int character=reader.read();
                    if(character<0)break;
                    head[count++]=(char)character;
                }else count+=read;
            }
            if(count>0&&Character.isHighSurrogate(head[count-1]))count--;
            return count==0?"":new String(head,0,count);
    }
    private static List<RecoveryMessage> recentMessages(Connection connection,String workspaceId,long since)throws SQLException{
        String sql="""
                SELECT type,from_agent_id,to_agent_id,text,status FROM (
                  SELECT sequence,
                         type,
                         substr(from_agent_id,1,?) from_agent_id,
                         substr(to_agent_id,1,?) to_agent_id,
                         substr(COALESCE(text,''),1,?) text,
                         substr(status,1,?) status
                  FROM messages WHERE workspace_id=? AND created_at>=?
                    AND type IN ('user_input','send','status','report')
                    AND (type='user_input'
                      OR (type='send' AND length(to_agent_id) BETWEEN 1 AND ?)
                      OR (type IN ('status','report') AND length(from_agent_id) BETWEEN 1 AND ?))
                  ORDER BY sequence DESC LIMIT ?
                ) ORDER BY sequence
                """;
        List<RecoveryMessage> messages=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement(sql)){
            statement.setInt(1,MAX_RECOVERY_AGENT_ID_CHARS+1);
            statement.setInt(2,MAX_RECOVERY_AGENT_ID_CHARS+1);
            statement.setInt(3,MAX_RECOVERY_MESSAGE_CHARS+1);
            statement.setInt(4,MAX_RECOVERY_STATUS_CHARS+1);
            statement.setString(5,workspaceId);statement.setLong(6,since);
            statement.setInt(7,MAX_RECOVERY_AGENT_ID_CHARS);
            statement.setInt(8,MAX_RECOVERY_AGENT_ID_CHARS);
            statement.setInt(9,MAX_RECOVERY_MESSAGES);
            try(ResultSet result=statement.executeQuery()){while(result.next()){
                RecoveryMessage message=recoveryMessage(result);
                if(validRecoveryMessage(message))messages.add(message);
            }}
        }
        return List.copyOf(messages);
    }
    private static List<RecoveryMessage> openDispatches(Connection connection,String workspaceId)throws SQLException{
        String sql="""
                SELECT type,from_agent_id,to_agent_id,text,status FROM (
                  SELECT sequence,'send' type,
                         substr(from_agent_id,1,?) from_agent_id,
                         substr(to_agent_id,1,?) to_agent_id,
                         substr(COALESCE(text,''),1,?) text,NULL status
                  FROM dispatches WHERE workspace_id=? AND status IN ('queued','submitted')
                    AND length(to_agent_id) BETWEEN 1 AND ?
                  ORDER BY sequence DESC LIMIT ?
                ) ORDER BY sequence
                """;
        List<RecoveryMessage> messages=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement(sql)){
            statement.setInt(1,MAX_RECOVERY_AGENT_ID_CHARS+1);
            statement.setInt(2,MAX_RECOVERY_AGENT_ID_CHARS+1);
            statement.setInt(3,MAX_RECOVERY_MESSAGE_CHARS+1);
            statement.setString(4,workspaceId);
            statement.setInt(5,MAX_RECOVERY_AGENT_ID_CHARS);
            statement.setInt(6,MAX_RECOVERY_MESSAGES);
            try(ResultSet result=statement.executeQuery()){while(result.next()){
                RecoveryMessage message=recoveryMessage(result);
                if(validRecoveryMessage(message))messages.add(message);
            }}
        }
        return List.copyOf(messages);
    }
    private static List<RecoveryWorker> workers(Connection connection,String workspaceId)throws SQLException{
        String sql="""
                WITH selected_workers AS (
                  SELECT rowid,id full_id,substr(id,1,?) id,substr(name,1,?) name,
                         substr(role,1,?) role,workspace_id,created_at
                  FROM workers
                  WHERE workspace_id=? AND deleted_at IS NULL
                    AND length(id) BETWEEN 1 AND ? AND length(name) BETWEEN 1 AND ?
                    AND unicode(substr(name,1,1))>32 AND unicode(substr(name,-1,1))>32
                    AND role IN ('coder','reviewer','tester','custom')
                  ORDER BY created_at,rowid
                  LIMIT ?
                )
                SELECT w.id,w.name,w.role,min(COUNT(d.sequence),2147483647) pending
                FROM selected_workers w
                LEFT JOIN dispatches d ON d.workspace_id=w.workspace_id AND d.to_agent_id=w.full_id
                  AND d.status IN ('queued','submitted')
                GROUP BY w.rowid,w.full_id,w.id,w.name,w.role,w.created_at
                ORDER BY w.created_at,w.rowid
                """;
        List<RecoveryWorker> workers=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement(sql)){
            statement.setInt(1,MAX_RECOVERY_WORKER_ID_CHARS+1);
            statement.setInt(2,MAX_RECOVERY_WORKER_NAME_CHARS+1);
            statement.setInt(3,MAX_RECOVERY_WORKER_ROLE_CHARS+1);
            statement.setString(4,workspaceId);
            statement.setInt(5,MAX_RECOVERY_WORKER_ID_CHARS);
            statement.setInt(6,MAX_RECOVERY_WORKER_NAME_CHARS);
            statement.setInt(7,MAX_RECOVERY_WORKERS);
            try(ResultSet result=statement.executeQuery()){
                while(result.next()){
                    String id=result.getString("id");String name=result.getString("name");
                    String role=result.getString("role");
                    if(validIdentifier(id,MAX_RECOVERY_WORKER_ID_CHARS)
                            &&validWorkerName(name)&&validWorkerRole(role)){
                        workers.add(new RecoveryWorker(id,name,role,result.getInt("pending")));
                    }
                }
            }
        }
        return List.copyOf(workers);
    }

    private static RecoveryMessage recoveryMessage(ResultSet result)throws SQLException{
        return new RecoveryMessage(result.getString("type"),
                identifierOrNull(result.getString("from_agent_id")),
                identifierOrNull(result.getString("to_agent_id")),
                displayText(result.getString("text"),MAX_RECOVERY_MESSAGE_CHARS),
                displayTextOrNull(result.getString("status"),MAX_RECOVERY_STATUS_CHARS));
    }

    private static String identifierOrNull(String value){
        return validIdentifier(value,MAX_RECOVERY_AGENT_ID_CHARS)?value:null;
    }

    private static boolean validRecoveryMessage(RecoveryMessage message){
        return switch(message.type()){
            case "user_input" -> true;
            case "send" -> message.toAgentId()!=null;
            case "status","report" -> message.fromAgentId()!=null;
            default -> false;
        };
    }

    private static boolean validIdentifier(String value,int maximum){
        return value!=null&&!value.isBlank()&&value.length()<=maximum;
    }

    private static boolean validWorkerName(String value){
        return validIdentifier(value,MAX_RECOVERY_WORKER_NAME_CHARS)&&value.equals(value.trim());
    }

    private static boolean validWorkerRole(String value){
        return value!=null&&value.length()<=MAX_RECOVERY_WORKER_ROLE_CHARS
                &&RECOVERY_WORKER_ROLES.contains(value);
    }

    private static String displayTextOrNull(String value,int maximum){
        return value==null?null:displayText(value,maximum);
    }

    private static String displayText(String value,int maximum){
        if(value==null)return "";
        int end=Math.min(value.length(),maximum);
        if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))
                &&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.substring(0,end);
    }
}
