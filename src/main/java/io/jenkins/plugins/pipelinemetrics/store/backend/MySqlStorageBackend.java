package io.jenkins.plugins.pipelinemetrics.store.backend;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import io.jenkins.plugins.pipelinemetrics.store.dialect.MySqlDialect;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqlDialect;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * External MySQL/MariaDB backend, using the MariaDB driver (LGPL) against either server family —
 * see the one dialect and driver covering both in {@link MySqlDialect}.
 */
public class MySqlStorageBackend extends JdbcStorageBackend {

    @DataBoundConstructor
    public MySqlStorageBackend(String host, int port, String database, String credentialsId) {
        super(host, port, database, credentialsId);
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:mariadb://" + getHost() + ":" + getPort() + "/" + getDatabase()
                + "?sslMode=" + (isUseSsl() ? "TRUST" : "DISABLED");
    }

    @Override
    protected String poolName() {
        return "pipeline-metrics-mysql";
    }

    @Override
    @NonNull
    public SqlDialect dialect() {
        return new MySqlDialect();
    }

    @Extension
    @Symbol("mysql")
    public static class DescriptorImpl extends JdbcBackendDescriptor {

        @Override
        @NonNull
        public String getDisplayName() {
            return "External MySQL/MariaDB";
        }
    }
}
