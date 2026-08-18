package dev.termestra.platform.persistence.sqlite;

public final class SqlitePersistenceException extends RuntimeException {
    public SqlitePersistenceException(String operation, Throwable cause) {
        super("SQLite operation failed: " + operation, cause);
    }
}
