package dev.termestra.platform.persistence.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class SchemaSupport {
    private SchemaSupport() { }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equals(result.getString("name"))) return true;
            return false;
        }
    }

    static boolean hasTable(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }
}
