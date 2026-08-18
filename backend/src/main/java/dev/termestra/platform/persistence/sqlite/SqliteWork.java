package dev.termestra.platform.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqliteWork<T> {
    T execute(Connection connection) throws SQLException;
}
