package dev.termestra.platform.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import static dev.termestra.platform.persistence.sqlite.SchemaSupport.execute;
import static dev.termestra.platform.persistence.sqlite.SchemaSupport.hasColumn;
import static dev.termestra.platform.persistence.sqlite.SchemaSupport.hasTable;

final class ConfigurationSchemaMigrations {
    private final Clock clock;
    ConfigurationSchemaMigrations(Clock clock) { this.clock = clock; }

    List<SchemaMigration> migrations() {
        return List.of(
                new SchemaMigration(7, this::v7),
                new SchemaMigration(8, c -> execute(c, "ALTER TABLE agent_launch_configs ADD COLUMN command_preset_id TEXT")),
                new SchemaMigration(9, this::refreshPresets), new SchemaMigration(10, this::refreshPresets),
                new SchemaMigration(11, this::refreshPresets), new SchemaMigration(12, this::refreshRoles),
                new SchemaMigration(13, this::refreshRoles),
                new SchemaMigration(16, c -> execute(c, "ALTER TABLE agent_launch_configs ADD COLUMN preset_augmentation_disabled INTEGER NOT NULL DEFAULT 0")),
                new SchemaMigration(17, this::refreshPresets),
                new SchemaMigration(18, c -> execute(c, "ALTER TABLE agent_launch_configs ADD COLUMN interactive_command TEXT")),
                new SchemaMigration(19, this::extendSettingsTables),
                new SchemaMigration(22, this::refreshRoles),
                new SchemaMigration(23, this::refreshPresets),
                new SchemaMigration(24, this::refreshPresets),
                new SchemaMigration(31, this::addStructuredModelSelection),
                new SchemaMigration(32, this::addDiscoverableBuiltinModels));
    }

    private void v7(Connection c) throws SQLException {
        execute(c, "CREATE TABLE command_presets (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, command TEXT NOT NULL, args_json TEXT NOT NULL, resume_args_template TEXT, session_id_capture_json TEXT, yolo_args_json TEXT, is_builtin INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        execute(c, "CREATE TABLE role_templates (id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, is_builtin INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        execute(c, "CREATE TABLE app_state (key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER NOT NULL)");
        refreshPresets(c); refreshRoles(c);
        try (PreparedStatement statement = c.prepareStatement("INSERT OR IGNORE INTO app_state(key,value,updated_at) VALUES ('active_workspace_id',NULL,?)")) {
            statement.setLong(1, clock.millis()); statement.executeUpdate();
        }
    }

    private void refreshPresets(Connection c) throws SQLException {
        long now = clock.millis();
        upsertPreset(c,"claude","Claude Code (CC)","claude","--resume {session_id}","{\"source\":\"claude_project_jsonl_dir\",\"pattern\":\"~/.claude/projects/{encoded_cwd}/*.jsonl\"}","[\"--dangerously-skip-permissions\",\"--permission-mode=bypassPermissions\",\"--disallowedTools=Task\"]",now);
        upsertPreset(c,"codex","Codex","codex","resume {session_id}","{\"source\":\"codex_session_jsonl_dir\",\"pattern\":\"~/.codex/sessions/**/*.jsonl\"}","[\"--dangerously-bypass-approvals-and-sandbox\"]",now);
        upsertPreset(c,"opencode","OpenCode","opencode","--session {session_id}","{\"source\":\"opencode_session_db\",\"pattern\":\"~/.local/share/opencode/opencode.db\"}","[]",now);
        upsertPreset(c,"gemini","Gemini","gemini","--resume {session_id}","{\"source\":\"gemini_session_json_dir\",\"pattern\":\"~/.gemini/tmp/*/chats/*.json\"}","[\"--yolo\"]",now);
        upsertPreset(c,"hermes","Hermes","hermes","--resume {session_id}","{\"source\":\"stdout_regex\",\"pattern\":\"Session:\\\\s*([A-Za-z0-9_-]+)\"}","[\"--yolo\"]",now);
        upsertPreset(c,"qwen","Qwen Code","qwen","--resume {session_id}","{\"source\":\"qwen_session_json_dir\",\"pattern\":\"~/.qwen/sessions/**/*.json\"}","[\"--approval-mode\",\"yolo\"]",now);
        upsertPreset(c,"pi","Pi","pi",null,null,"[\"--approve\"]",now);
        upsertPreset(c,"agy","Antigravity CLI","agy","--conversation {session_id}","{\"source\":\"stdout_regex\",\"pattern\":\"(?:^|\\\\s)(?:\\\\S*[\\\\\\\\/])?agy(?:\\\\.(?:cmd|exe))?\\\\s+--conversation\\\\s+([0-9a-fA-F-]{36})\\\\b\"}","[\"--dangerously-skip-permissions\"]",now);
        upsertPreset(c,"cursor","Cursor CLI","cursor-agent",null,null,"[\"--force\"]",now);
        upsertPreset(c,"grok","Grok Build","grok",null,null,"[\"--always-approve\"]",now);
    }

    private void upsertPreset(Connection c, String id, String display, String command, String resume, String capture, String yolo, long now) throws SQLException {
        boolean extended = hasColumn(c, "command_presets", "env");
        String sql = extended
                ? "INSERT INTO command_presets(id,display_name,command,args_json,env,resume_args_template,session_id_capture_json,yolo_args_json,is_builtin,created_at,updated_at) VALUES (?,?,?,'[]','{}',?,?,?,1,?,?) ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name,command=excluded.command,resume_args_template=excluded.resume_args_template,session_id_capture_json=excluded.session_id_capture_json,yolo_args_json=excluded.yolo_args_json,updated_at=excluded.updated_at WHERE command_presets.is_builtin=1"
                : "INSERT INTO command_presets(id,display_name,command,args_json,resume_args_template,session_id_capture_json,yolo_args_json,is_builtin,created_at,updated_at) VALUES (?,?,?,'[]',?,?,?,1,?,?) ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name,command=excluded.command,resume_args_template=excluded.resume_args_template,session_id_capture_json=excluded.session_id_capture_json,yolo_args_json=excluded.yolo_args_json,updated_at=excluded.updated_at WHERE command_presets.is_builtin=1";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1,id); statement.setString(2,display); statement.setString(3,command); statement.setString(4,resume); statement.setString(5,capture); statement.setString(6,yolo); statement.setLong(7,now); statement.setLong(8,now); statement.executeUpdate();
        }
    }

    private void refreshRoles(Connection c) throws SQLException {
        long now = clock.millis();
        upsertRole(c,"orchestrator","Orchestrator","你是 Termestra 的 Orchestrator，负责直接响应用户并组织右侧真实成员协作。\n工作方式：\n- 澄清目标，把需求拆成可派发的小任务。\n- 维护 .termestra/tasks.md，让当前计划、进度和阻塞可追踪。\n- 根据成员汇报推进下一步，不把选择题无谓丢回给用户。",now);
        upsertRole(c,"coder","Coder","你是实现型 Coder，负责把明确任务落成最小正确代码改动。\n工作方式：\n- 先阅读相关文件和现有模式，再动手。\n- 优先小步修改，避免无关重构和范围扩张。\n- 改动后运行能覆盖风险的验证命令；不能验证时说明原因。\n交付说明要包含：改动文件、验证结果、剩余风险或阻塞。",now);
        upsertRole(c,"reviewer","Reviewer","你是监工型 Reviewer，负责质量审查，不替代 Orchestrator，也不默认改代码。\n工作方式：\n- 优先找真实 bug、回归风险、边界条件和测试缺口。\n- 发现问题时给出严重度、文件/行号、触发条件和最小修复建议。\n- 没有高风险问题时明确说清剩余风险和未验证范围。\n交付说明按严重度排序，先列 blocking 问题。",now);
        upsertRole(c,"tester","Tester","你是验证型 Tester，负责复现、测试和证据化验证。\n工作方式：\n- 先明确要验证的行为、入口和失败条件。\n- 优先跑真实命令或真实链路；必要时补充最小测试。\n- 记录命令、结果、关键输出和不能覆盖的场景。\n交付说明要区分通过、失败、未验证和建议下一步。",now);
    }

    private void upsertRole(Connection c, String id, String name, String description, long now) throws SQLException {
        boolean extended = hasColumn(c, "role_templates", "role_type");
        String sql = extended
                ? "INSERT INTO role_templates(id,name,role_type,description,default_command,default_args,default_env,is_builtin,created_at,updated_at) VALUES (?,?,?,?,'claude','[]','{}',1,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,role_type=excluded.role_type,description=excluded.description,updated_at=excluded.updated_at WHERE role_templates.is_builtin=1"
                : "INSERT INTO role_templates(id,name,description,is_builtin,created_at,updated_at) VALUES (?,?,?,1,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,description=excluded.description,updated_at=excluded.updated_at WHERE role_templates.is_builtin=1";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1,id); statement.setString(2,name);
            if (extended) {
                statement.setString(3,id); statement.setString(4,description); statement.setLong(5,now); statement.setLong(6,now);
            } else {
                statement.setString(3,description); statement.setLong(4,now); statement.setLong(5,now);
            }
            statement.executeUpdate();
        }
    }

    private void extendSettingsTables(Connection c) throws SQLException {
        addColumnUnlessPresent(c,"command_presets","env","TEXT NOT NULL DEFAULT '{}'");
        addColumnUnlessPresent(c,"role_templates","role_type","TEXT NOT NULL DEFAULT 'custom'");
        addColumnUnlessPresent(c,"role_templates","default_command","TEXT NOT NULL DEFAULT 'claude'");
        addColumnUnlessPresent(c,"role_templates","default_args","TEXT NOT NULL DEFAULT '[]'");
        addColumnUnlessPresent(c,"role_templates","default_env","TEXT NOT NULL DEFAULT '{}'");
        execute(c,"UPDATE role_templates SET role_type=id WHERE id IN ('orchestrator','coder','reviewer','tester') AND is_builtin=1");
    }

    private void addStructuredModelSelection(Connection connection) throws SQLException {
        if(hasTable(connection,"command_presets")){
            addColumnUnlessPresent(connection,"command_presets","model_args_template_json","TEXT");
            addColumnUnlessPresent(connection,"command_presets","suggested_models_json","TEXT NOT NULL DEFAULT '[]'");
            addColumnUnlessPresent(connection,"command_presets","allow_custom_model","INTEGER NOT NULL DEFAULT 0");
            addColumnUnlessPresent(connection,"command_presets","revision","INTEGER NOT NULL DEFAULT 1");
            execute(connection,"""
                UPDATE command_presets
                SET model_args_template_json='["--model","{model_id}"]',
                    suggested_models_json='[]',
                    allow_custom_model=1
                WHERE is_builtin=1 AND id IN ('codex','claude')
                """);
        }
        if(hasTable(connection,"agent_launch_configs")){
            addColumnUnlessPresent(connection,"agent_launch_configs","model_id","TEXT");
            addColumnUnlessPresent(connection,"agent_launch_configs","revision","INTEGER NOT NULL DEFAULT 1");
        }
    }

    private void addDiscoverableBuiltinModels(Connection connection) throws SQLException {
        if(!hasTable(connection,"command_presets")
                ||!hasColumn(connection,"command_presets","model_args_template_json"))return;
        execute(connection,"""
                UPDATE command_presets
                SET model_args_template_json='["--model","{model_id}"]',
                    suggested_models_json='[]',
                    allow_custom_model=1
                WHERE is_builtin=1 AND id IN ('codex','cursor','opencode','pi')
                """);
    }

    private static void addColumnUnlessPresent(Connection connection, String table, String column, String definition)
            throws SQLException {
        if (!hasColumn(connection, table, column)) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
