package io.jenkins.plugins.pipelinemetrics.store.backend;

import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqlDialect;
import java.sql.SQLException;

/**
 * A storage backend the admin can select for Pipeline Metrics: where the {@code builds}/
 * {@code stages} tables actually live. Implementations are {@link hudson.model.Describable}, so
 * Global Config renders a "pick one" selector for them and JCasC addresses them by symbol —
 * the same mechanism Jenkins uses for SCM or Cloud type selection.
 */
public abstract class StorageBackend extends AbstractDescribableImpl<StorageBackend> implements ExtensionPoint {

    /**
     * Builds a pooled connection for this backend. Called whenever the active backend changes;
     * implementations should build a fresh pool each call rather than caching one, since the
     * caller ({@code MetricsStore}) owns the pool's lifecycle from here on.
     */
    @NonNull
    public abstract HikariDataSource createDataSource() throws SQLException;

    /** The SQL dialect this backend's schema/queries are written against. */
    @NonNull
    public abstract SqlDialect dialect();

    /** Short, human-readable description for logs and the network-storage warning monitor. */
    @NonNull
    public abstract String describe();
}
