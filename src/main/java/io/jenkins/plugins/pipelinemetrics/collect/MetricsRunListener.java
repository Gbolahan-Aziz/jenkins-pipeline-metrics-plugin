package io.jenkins.plugins.pipelinemetrics.collect;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.pipelinemetrics.config.PipelineMetricsGlobalConfig;
import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Captures every build as it reaches a final result. */
@Extension
public class MetricsRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(MetricsRunListener.class.getName());

    @Override
    public void onFinalized(Run<?, ?> run) {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        if (config != null && !config.isCollectionEnabled()) {
            return;
        }
        try {
            BuildRecord record = BuildFactory.fromRun(run);
            MetricsStore.get().upsertBuild(record);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to collect metrics for " + safeName(run), e);
        }
    }

    private static String safeName(Run<?, ?> run) {
        try {
            return run.getParent().getFullName() + "#" + run.getNumber();
        } catch (RuntimeException e) {
            return "<unknown build>";
        }
    }
}
