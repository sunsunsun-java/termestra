package dev.termestra.platform.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Comparator;
import java.util.stream.Stream;

import static dev.termestra.platform.persistence.sqlite.SchemaSupport.execute;

public final class SqliteSchemaMigrator {
    public static final int CURRENT_SCHEMA_VERSION = 30;
    private final SqliteDatabase database;
    private final Clock clock;

    public SqliteSchemaMigrator(SqliteDatabase database, Clock clock) { this.database = database; this.clock = clock; }

    public void migrate() {
        database.write("migrate schema", connection -> {
            execute(connection, "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            int current = currentVersion(connection);
            if (current > CURRENT_SCHEMA_VERSION) throw new SQLException("database schema " + current + " is newer than supported schema " + CURRENT_SCHEMA_VERSION);
            var configuration = new ConfigurationSchemaMigrations(clock);
            Stream.concat(Stream.concat(new CoreSchemaMigrations().migrations().stream(), configuration.migrations().stream()),
                            new DispatchSchemaMigrations().migrations().stream())
                    .sorted(Comparator.comparingInt(SchemaMigration::version))
                    .filter(migration -> migration.version() > current)
                    .forEach(migration -> applyAndRecord(connection, migration));
            return null;
        });
    }

    private void applyAndRecord(Connection connection, SchemaMigration migration) {
        try {
            migration.apply(connection);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO schema_version(version,applied_at) VALUES (?,?)")) {
                statement.setInt(1, migration.version()); statement.setLong(2, clock.millis()); statement.executeUpdate();
            }
        } catch (SQLException error) {
            throw new SqliteMigrationException(migration.version(), error);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }
}
