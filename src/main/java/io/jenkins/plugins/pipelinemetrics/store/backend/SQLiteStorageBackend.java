package io.jenkins.plugins.pipelinemetrics.store.backend;

import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import io.jenkins.plugins.pipelinemetrics.store.SqliteConnectionFactory;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqlDialect;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqliteDialect;
import java.io.File;
import java.sql.SQLException;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * The default, zero-configuration backend: embedded SQLite at the historical fixed path
 * {@code $JENKINS_HOME/pipeline-metrics/metrics.db}. Deliberately has no admin-editable fields —
 * an install that never touches the storage backend setting keeps behaving exactly as before.
 */
public class SQLiteStorageBackend extends StorageBackend {

    @DataBoundConstructor
    public SQLiteStorageBackend() {
    }

    @Override
    @NonNull
    public HikariDataSource createDataSource() throws SQLException {
        File dir = new File(Jenkins.get().getRootDir(), "pipeline-metrics");
        return SqliteConnectionFactory.forFile(new File(dir, "metrics.db"));
    }

    @Override
    @NonNull
    public SqlDialect dialect() {
        return new SqliteDialect();
    }

    @Override
    @NonNull
    public String describe() {
        return "Embedded SQLite ($JENKINS_HOME/pipeline-metrics/metrics.db)";
    }

    @Extension
    @Symbol("sqlite")
    public static class DescriptorImpl extends Descriptor<StorageBackend> {
        @Override
        @NonNull
        public String getDisplayName() {
            return "Local SQLite (default)";
        }
    }
}
