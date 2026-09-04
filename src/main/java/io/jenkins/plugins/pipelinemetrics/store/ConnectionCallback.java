package io.jenkins.plugins.pipelinemetrics.store;

import java.sql.Connection;
import java.sql.SQLException;

/** Callback executed with the store's JDBC connection held under the store lock. */
@FunctionalInterface
public interface ConnectionCallback<T> {
    T run(Connection conn) throws SQLException;
}
