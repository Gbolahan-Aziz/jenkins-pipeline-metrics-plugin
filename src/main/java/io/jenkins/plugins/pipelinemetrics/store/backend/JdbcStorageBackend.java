package io.jenkins.plugins.pipelinemetrics.store.backend;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.pipelinemetrics.PipelineMetricsPermissions;
import io.jenkins.plugins.pipelinemetrics.credentials.CredentialsResolver;
import java.sql.SQLException;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Shared shape for the external client-server backends (Postgres, MySQL/MariaDB): a host/port/
 * database/credentials connection plus a bounded pool size. Never stores a username or password
 * directly — only a Jenkins credentials ID, resolved at connect time.
 */
public abstract class JdbcStorageBackend extends StorageBackend {

    public static final int MIN_POOL_SIZE = 1;
    public static final int MAX_POOL_SIZE = 20;
    public static final int DEFAULT_POOL_SIZE = 5;

    private final String host;
    private final int port;
    private final String database;
    private final String credentialsId;
    private boolean useSsl = true;
    private int maxPoolSize = DEFAULT_POOL_SIZE;

    protected JdbcStorageBackend(String host, int port, String database, String credentialsId) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (database == null || database.trim().isEmpty()) {
            throw new IllegalArgumentException("database must not be empty");
        }
        if (credentialsId == null || credentialsId.trim().isEmpty()) {
            throw new IllegalArgumentException("credentialsId must not be empty");
        }
        this.host = host;
        this.port = port;
        this.database = database;
        this.credentialsId = credentialsId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public boolean isUseSsl() {
        return useSsl;
    }

    @DataBoundSetter
    public void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    @DataBoundSetter
    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize < MIN_POOL_SIZE || maxPoolSize > MAX_POOL_SIZE) {
            throw new IllegalArgumentException(
                    "maxPoolSize must be between " + MIN_POOL_SIZE + " and " + MAX_POOL_SIZE);
        }
        this.maxPoolSize = maxPoolSize;
    }

    /** The full {@code jdbc:...} URL, including the SSL mode implied by {@link #isUseSsl()}. */
    protected abstract String jdbcUrl();

    /** Pool name for logs/metrics, e.g. {@code "pipeline-metrics-postgresql"}. */
    protected abstract String poolName();

    @Override
    @NonNull
    public HikariDataSource createDataSource() throws SQLException {
        StandardUsernamePasswordCredentials creds = CredentialsResolver.lookup(credentialsId);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(creds.getUsername());
        config.setPassword(Secret.toString(creds.getPassword()));
        config.setMaximumPoolSize(maxPoolSize);
        config.setPoolName(poolName());
        // A temporarily unreachable external database must not throw out of pool construction
        // (which would otherwise be an *unchecked* PoolInitializationException, able to crash a
        // JCasC apply or Jenkins startup itself). Defer connection attempts to first use instead;
        // MetricsStore.init()'s own getConnection() call already handles that failure as a
        // checked SQLException, leaving the store cleanly "unavailable" rather than crashing.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Override
    @NonNull
    public String describe() {
        return dialect().id() + "://" + host + ":" + port + "/" + database
                + (useSsl ? " (TLS)" : " (no TLS)");
    }

    /**
     * Shared descriptor behavior for every JDBC backend — pool-size/credentials-id validation
     * and the credentials listbox. Only {@link Descriptor#getDisplayName()} differs per backend,
     * so concrete {@code DescriptorImpl}s extend this instead of re-implementing it three times.
     */
    public abstract static class JdbcBackendDescriptor extends Descriptor<StorageBackend> {

        public FormValidation doCheckMaxPoolSize(@QueryParameter String value) {
            try {
                int n = Integer.parseInt(value.trim());
                if (n < MIN_POOL_SIZE || n > MAX_POOL_SIZE) {
                    return FormValidation.error("Value must be between " + MIN_POOL_SIZE + " and " + MAX_POOL_SIZE);
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Enter a whole number");
            }
        }

        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            Jenkins.get().checkPermission(PipelineMetricsPermissions.CONFIGURE);
            if (value == null || value.isEmpty()) {
                return FormValidation.error("Select a credentials ID");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            Jenkins jenkins = Jenkins.get();
            StandardListBoxModel result = new StandardListBoxModel();
            if (!jenkins.hasPermission(PipelineMetricsPermissions.CONFIGURE)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM2, jenkins, StandardUsernamePasswordCredentials.class,
                            Collections.emptyList(), CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
