package io.jenkins.plugins.pipelinemetrics.config;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.pipelinemetrics.PipelineMetricsPermissions;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.store.backend.SQLiteStorageBackend;
import io.jenkins.plugins.pipelinemetrics.store.backend.StorageBackend;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import org.jenkinsci.Symbol;

/**
 * Global, JCasC-addressable configuration for the Pipeline Metrics plugin.
 */
@Extension
@Symbol("pipelineMetrics")
public class PipelineMetricsGlobalConfig extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(PipelineMetricsGlobalConfig.class.getName());

    static final int MIN_RETENTION_DAYS = 1;
    static final int MAX_RETENTION_DAYS = 365;
    static final int MIN_BACKFILL_LIMIT = 0;
    static final int MAX_BACKFILL_LIMIT = 10000;

    private boolean collectionEnabled = true;
    private int retentionDays = 90;
    private int backfillLimit = 100;
    private StorageBackend storageBackend = new SQLiteStorageBackend();

    public PipelineMetricsGlobalConfig() {
        load();
        applyStorageBackend();
    }

    public static PipelineMetricsGlobalConfig get() {
        return GlobalConfiguration.all().get(PipelineMetricsGlobalConfig.class);
    }

    public boolean isCollectionEnabled() {
        return collectionEnabled;
    }

    @DataBoundSetter
    public void setCollectionEnabled(boolean collectionEnabled) {
        this.collectionEnabled = collectionEnabled;
        save();
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    @DataBoundSetter
    public void setRetentionDays(int retentionDays) {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException(
                    "retentionDays must be between " + MIN_RETENTION_DAYS + " and " + MAX_RETENTION_DAYS);
        }
        this.retentionDays = retentionDays;
        save();
    }

    public int getBackfillLimit() {
        return backfillLimit;
    }

    @DataBoundSetter
    public void setBackfillLimit(int backfillLimit) {
        if (backfillLimit < MIN_BACKFILL_LIMIT || backfillLimit > MAX_BACKFILL_LIMIT) {
            throw new IllegalArgumentException(
                    "backfillLimit must be between " + MIN_BACKFILL_LIMIT + " and " + MAX_BACKFILL_LIMIT);
        }
        this.backfillLimit = backfillLimit;
        save();
    }

    @NonNull
    public StorageBackend getStorageBackend() {
        return storageBackend;
    }

    @DataBoundSetter
    public void setStorageBackend(StorageBackend storageBackend) {
        this.storageBackend = storageBackend == null ? new SQLiteStorageBackend() : storageBackend;
        save();
        applyStorageBackend();
    }

    /** The available backend types, for the Global Config "pick one" selector. */
    @NonNull
    public List<Descriptor<StorageBackend>> getStorageBackendDescriptors() {
        return Jenkins.get().getDescriptorList(StorageBackend.class);
    }

    /**
     * Builds a pool for the currently configured backend and swaps it into {@link MetricsStore}.
     * Called on load (so a restart re-applies a non-default configured backend, regardless of
     * extension-loading order relative to whatever first calls {@code MetricsStore.get()}) and
     * whenever the backend is changed via the UI or JCasC. Never throws — a failure here must
     * not prevent Jenkins from starting up; it is logged and the previous backend is kept.
     */
    private void applyStorageBackend() {
        try {
            MetricsStore.reconfigure(storageBackend.createDataSource(), storageBackend.dialect());
        } catch (SQLException | RuntimeException e) {
            // RuntimeException is caught defensively too: a backend's createDataSource() reaches
            // into a JDBC driver / connection pool we don't control, and an unchecked failure
            // there (e.g. a driver throwing before a connection is even attempted) must not be
            // able to crash Jenkins startup or a JCasC apply.
            LOGGER.log(Level.SEVERE,
                    "Failed to apply Pipeline Metrics storage backend " + storageBackend.describe(), e);
        }
    }

    @POST
    public FormValidation doCheckRetentionDays(@QueryParameter String value) {
        Jenkins.get().checkPermission(PipelineMetricsPermissions.CONFIGURE);
        return checkRange(value, MIN_RETENTION_DAYS, MAX_RETENTION_DAYS);
    }

    @POST
    public FormValidation doCheckBackfillLimit(@QueryParameter String value) {
        Jenkins.get().checkPermission(PipelineMetricsPermissions.CONFIGURE);
        return checkRange(value, MIN_BACKFILL_LIMIT, MAX_BACKFILL_LIMIT);
    }

    private static FormValidation checkRange(@NonNull String value, int min, int max) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < min || n > max) {
                return FormValidation.error("Value must be between " + min + " and " + max);
            }
            return FormValidation.ok();
        } catch (NumberFormatException e) {
            return FormValidation.error("Enter a whole number");
        }
    }
}
