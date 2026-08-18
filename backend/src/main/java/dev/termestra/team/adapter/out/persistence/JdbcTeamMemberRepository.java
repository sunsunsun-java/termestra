package dev.termestra.team.adapter.out.persistence;

import dev.termestra.shared.id.*;
import dev.termestra.platform.persistence.sqlite.SqliteDatabase;
import dev.termestra.team.application.port.out.TeamMemberRepository;
import dev.termestra.team.application.port.out.TeamMemberSummary;
import dev.termestra.team.application.port.in.TeamInputLimits;
import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.exception.InvalidTeamMemberRecord;
import dev.termestra.team.domain.model.*;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class JdbcTeamMemberRepository implements TeamMemberRepository {
    private final SqliteDatabase database;
    public JdbcTeamMemberRepository(SqliteDatabase database) { this.database = database; }

    @Override public boolean workspaceExists(String workspaceId) {
        return database.read("find workspace for team", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL")) { ps.setString(1,workspaceId); return ps.executeQuery().next(); }
        });
    }

    @Override public void save(TeamMember member) {
        try {
            database.write("save worker", c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO workers(id,workspace_id,name,description,role,created_at)
                        SELECT ?,?,?,?,?,?
                        WHERE EXISTS(SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL)
                          AND (SELECT COUNT(*) FROM workers WHERE workspace_id=? AND deleted_at IS NULL) < ?
                        """)) {
                    ps.setString(1,member.id().toString()); ps.setString(2,member.workspaceId().toString()); ps.setString(3,member.name());
                    ps.setString(4,member.description()); ps.setString(5,member.role().wireValue()); ps.setLong(6,member.createdAt().toEpochMilli());
                    ps.setString(7,member.workspaceId().toString());ps.setString(8,member.workspaceId().toString());ps.setInt(9,MAX_MEMBERS_PER_WORKSPACE);
                    if(ps.executeUpdate()!=1){
                        if(activeWorkspace(c,member.workspaceId().toString()))throw new TeamConflict("Workspace worker limit reached: "+MAX_MEMBERS_PER_WORKSPACE);
                        throw new TeamConflict("Workspace not found: "+member.workspaceId());
                    }
                }
                return null;
            });
        } catch (dev.termestra.platform.persistence.sqlite.SqlitePersistenceException failure) {
            if (uniqueConstraint(failure)) throw new TeamConflict("Worker already exists: " + member.name(), failure);
            throw failure;
        }
    }

    @Override public Optional<TeamMember> findById(String workspaceId, String agentId) { return find(workspaceId,"id",agentId); }
    @Override public Optional<TeamMember> findByName(String workspaceId, String name) { return find(workspaceId,"name",name); }

    private Optional<TeamMember> find(String workspaceId, String column, String value) {
        int valueLimit = column.equals("id")
                ? TeamInputLimits.MAX_MEMBER_ID_CHARACTERS
                : TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS;
        if (!boundedIdentifier(workspaceId, TeamInputLimits.MAX_MEMBER_ID_CHARACTERS)
                || !boundedIdentifier(value, valueLimit)) return Optional.empty();
        return database.read("find team member", c -> {
            String sql = """
                    SELECT CASE WHEN length(id) BETWEEN 1 AND ? THEN id ELSE NULL END AS id,
                           workspace_id,
                           CASE WHEN length(name) BETWEEN 1 AND ?
                                  AND unicode(substr(name,1,1))>32
                                  AND unicode(substr(name,-1,1))>32
                                THEN name ELSE NULL END AS name,
                           substr(COALESCE(description,''),1,?) AS description,
                           CASE WHEN length(role) BETWEEN 1 AND ? THEN role ELSE NULL END AS role,
                           created_at
                    FROM workers
                    WHERE workspace_id=? AND deleted_at IS NULL AND %s=?
                    """.formatted(column);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, TeamInputLimits.MAX_MEMBER_ID_CHARACTERS);
                ps.setInt(2, TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS);
                ps.setInt(3, TeamInputLimits.MAX_MEMBER_DESCRIPTION_CHARACTERS);
                ps.setInt(4, TeamInputLimits.MAX_ROLE_CHARACTERS);
                ps.setString(5,workspaceId); ps.setString(6,value);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(member(rs)) : Optional.empty(); }
            }
        });
    }

    @Override public List<TeamMemberSummary> list(String workspaceId) {
        return database.read("list team members", c -> {
            String sql = """
                    SELECT w.id,
                      w.name,
                      w.role,
                      CASE WHEN alc.preset_augmentation_disabled=0
                             AND length(alc.command_preset_id) BETWEEN 1 AND ?
                           THEN alc.command_preset_id ELSE NULL END command_preset_id
                    FROM workers w LEFT JOIN agent_launch_configs alc ON alc.workspace_id=w.workspace_id AND alc.agent_id=w.id
                    WHERE w.workspace_id=? AND w.deleted_at IS NULL
                      AND length(w.id) BETWEEN 1 AND ?
                      AND length(w.name) BETWEEN 1 AND ?
                      AND unicode(substr(w.name,1,1))>32
                      AND unicode(substr(w.name,-1,1))>32
                      AND w.role IN ('coder','reviewer','tester','custom')
                    ORDER BY w.created_at,w.rowid LIMIT ?
                    """;
            List<TeamMemberSummary> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, TeamInputLimits.MAX_PRESET_ID_CHARACTERS);
                ps.setString(2,workspaceId);
                ps.setInt(3, TeamInputLimits.MAX_MEMBER_ID_CHARACTERS);
                ps.setInt(4, TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS);
                ps.setInt(5, MAX_MEMBERS_PER_WORKSPACE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(new TeamMemberSummary(rs.getString("id"),
                            persistedMemberName(rs.getString("name")), rs.getString("role"),
                            rs.getString("command_preset_id")));
                }
            }
            return List.copyOf(result);
        });
    }
    @Override public boolean rename(String workspaceId,String agentId,String name){try{return database.write("rename worker",c->{try(var ps=c.prepareStatement("UPDATE workers SET name=? WHERE workspace_id=? AND id=? AND deleted_at IS NULL")){ps.setString(1,name);ps.setString(2,workspaceId);ps.setString(3,agentId);return ps.executeUpdate()>0;}});}catch(dev.termestra.platform.persistence.sqlite.SqlitePersistenceException failure){if(uniqueConstraint(failure))throw new TeamConflict("Worker already exists: "+name,failure);throw failure;}}
    @Override public boolean delete(String workspaceId,String agentId){return database.write("delete worker",c->{
        try (PreparedStatement owner = c.prepareStatement("SELECT 1 FROM workers WHERE workspace_id=? AND id=? AND deleted_at IS NULL")) {
            owner.setString(1, workspaceId);
            owner.setString(2, agentId);
            try (ResultSet result = owner.executeQuery()) { if (!result.next()) return false; }
        }
        execute(c,"DELETE FROM dispatch_deliveries WHERE workspace_id=? AND to_agent_id=?",workspaceId,agentId);
        execute(c,"DELETE FROM dispatches WHERE workspace_id=? AND to_agent_id=?",workspaceId,agentId);
        execute(c,"DELETE FROM messages WHERE workspace_id=? AND worker_id=?",workspaceId,agentId);
        execute(c,"DELETE FROM agent_launch_configs WHERE workspace_id=? AND agent_id=?",workspaceId,agentId);
        execute(c,"DELETE FROM agent_sessions WHERE workspace_id=? AND agent_id=?",workspaceId,agentId);
        execute(c,"DELETE FROM agent_runs WHERE agent_id=? AND (workspace_id=? OR workspace_id IS NULL)",agentId,workspaceId);
        return execute(c,"DELETE FROM workers WHERE workspace_id=? AND id=?",workspaceId,agentId)==1;
    });}

    private static boolean uniqueConstraint(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLiteException sqlite
                    && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) return true;
        }
        return false;
    }

    private static boolean activeWorkspace(Connection connection,String workspaceId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("SELECT 1 FROM workspaces WHERE id=? AND deleted_at IS NULL")){
            statement.setString(1,workspaceId);try(ResultSet result=statement.executeQuery()){return result.next();}
        }
    }

    private static int execute(Connection connection,String sql,Object... values)throws SQLException{try(PreparedStatement statement=connection.prepareStatement(sql)){for(int index=0;index<values.length;index++)statement.setObject(index+1,values[index]);return statement.executeUpdate();}}

    private static TeamMember member(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        String role = rs.getString("role");
        if (id == null || name == null || role == null) throw new InvalidTeamMemberRecord();
        try {
            return new TeamMember(AgentId.parse(id), WorkspaceId.parse(rs.getString("workspace_id")),
                    persistedMemberName(name),
                    TeamInputLimits.boundedMemberDescription(rs.getString("description")),
                    AgentRole.parse(role),
                    Instant.ofEpochMilli(rs.getLong("created_at")));
        } catch (IllegalArgumentException invalidRecord) {
            throw new InvalidTeamMemberRecord(invalidRecord);
        }
    }

    private static boolean boundedIdentifier(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static String persistedMemberName(String value) {
        try {
            String normalized = TeamInputLimits.memberName(value);
            if (!normalized.equals(value)) throw new InvalidTeamMemberRecord();
            return value;
        } catch (dev.termestra.team.application.exception.TeamBadRequest invalidName) {
            throw new InvalidTeamMemberRecord(invalidName);
        }
    }
}
