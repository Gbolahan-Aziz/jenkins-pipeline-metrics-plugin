package io.jenkins.plugins.pipelinemetrics.service;

import hudson.Extension;
import io.jenkins.plugins.pipelinemetrics.config.PipelineMetricsGlobalConfig;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.util.logging.Level;
import java.util.logging.Logger;
import hudson.model.listeners.ItemListener;

/**
 * On controller startup (after all items are loaded), if collection is enabled and the metrics
 * store has no builds yet, run a one-time backfill so a fresh install populates automatically.
 * Because it only fires when the store is empty, subsequent restarts do not re-scan.
 */
@Extension
public class AutoBackfill extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(AutoBackfill.class.getName());

    @Override
    public void onLoaded() {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        if (config == null || !config.isCollectionEnabled()) {
            return;
        }
        MetricsStore store = MetricsStore.get();
        if (!store.isAvailable() || store.buildCount() > 0) {
            return;
        }
        LOGGER.log(Level.INFO, "Metrics store is empty; starting one-time automatic backfill");
        BackfillService.get().start(config.getBackfillLimit());
    }
}
