package io.jenkins.plugins.pipelinemetrics.store.backend;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import io.jenkins.plugins.pipelinemetrics.store.dialect.PostgresDialect;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqlDialect;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * External PostgreSQL backend — a client-server database that safely arbitrates concurrent
 * writers, removing both the network-filesystem and multi-controller risks embedded SQLite has.
 */
public class PostgresStorageBackend extends JdbcStorageBackend {

    @DataBoundConstructor
    public PostgresStorageBackend(String host, int port, String database, String credentialsId) {
        super(host, port, database, credentialsId);
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:postgresql://" + getHost() + ":" + getPort() + "/" + getDatabase()
                + "?ssl=" + isUseSsl() + (isUseSsl() ? "&sslmode=require" : "&sslmode=disable");
    }

    @Override
    protected String poolName() {
        return "pipeline-metrics-postgresql";
    }

    @Override
    @NonNull
    public SqlDialect dialect() {
        return new PostgresDialect();
    }

    @Extension
    @Symbol("postgresql")
    public static class DescriptorImpl extends JdbcBackendDescriptor {

        @Override
        @NonNull
        public String getDisplayName() {
            return "External PostgreSQL";
        }
    }
}
