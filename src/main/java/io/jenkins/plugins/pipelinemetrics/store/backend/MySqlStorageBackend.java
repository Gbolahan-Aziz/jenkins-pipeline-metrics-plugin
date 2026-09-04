package io.jenkins.plugins.pipelinemetrics.store.backend;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.pipelinemetrics.PipelineMetricsPermissions;
import io.jenkins.plugins.pipelinemetrics.store.dialect.MySqlDialect;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqlDialect;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

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
    public static class DescriptorImpl extends Descriptor<StorageBackend> {

        @Override
        @NonNull
        public String getDisplayName() {
            return "External MySQL/MariaDB";
        }

        public FormValidation doCheckMaxPoolSize(@QueryParameter String value) {
            try {
                int n = Integer.parseInt(value.trim());
                if (n < JdbcStorageBackend.MIN_POOL_SIZE || n > JdbcStorageBackend.MAX_POOL_SIZE) {
                    return FormValidation.error("Value must be between "
                            + JdbcStorageBackend.MIN_POOL_SIZE + " and " + JdbcStorageBackend.MAX_POOL_SIZE);
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
