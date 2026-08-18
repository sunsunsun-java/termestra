package dev.termestra.platform.persistence.sqlite;

public final class SqliteMigrationException extends RuntimeException {
    public SqliteMigrationException(int version, Throwable cause) { super("SQLite schema migration v" + version + " failed", cause); }
}
