package dev.termestra.platform.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

record SchemaMigration(int version, Step step) {
    void apply(Connection connection) throws SQLException { step.apply(connection); }

    @FunctionalInterface
    interface Step { void apply(Connection connection) throws SQLException; }
}
