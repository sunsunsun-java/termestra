package dev.termestra.platform.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class SqliteDatabase {
    private final String jdbcUrl;
    private final Object persistentPragmaLock = new Object();
    private volatile boolean persistentPragmasConfigured;

    public SqliteDatabase(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile must not be null");
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath().normalize();
    }

    public <T> T read(String operation, SqliteWork<T> work) {
        try (Connection connection = open()) {
            return work.execute(connection);
        } catch (SQLException error) {
            throw new SqlitePersistenceException(operation, error);
        }
    }

    public synchronized <T> T write(String operation, SqliteWork<T> work) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    error.addSuppressed(rollbackFailure);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlitePersistenceException(operation, error);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        boolean configured = false;
        try {
            configurePersistentPragmas(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA journal_size_limit = 67108864");
                statement.execute("PRAGMA synchronous = NORMAL");
                statement.execute("PRAGMA foreign_keys = ON");
            }
            configured = true;
            return connection;
        } finally {
            if (!configured) connection.close();
        }
    }

    private void configurePersistentPragmas(Connection connection) throws SQLException {
        if (persistentPragmasConfigured) return;
        synchronized (persistentPragmaLock) {
            if (persistentPragmasConfigured) return;
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
            }
            persistentPragmasConfigured = true;
        }
    }
}
