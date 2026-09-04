package io.jenkins.plugins.pipelinemetrics.service;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.pipelinemetrics.config.PipelineMetricsGlobalConfig;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.util.concurrent.TimeUnit;

/** Periodically removes build/stage records older than the configured retention period. */
@Extension
public class RetentionWork extends AsyncPeriodicWork {

    public RetentionWork() {
        super("Pipeline Metrics retention");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.HOURS.toMillis(6);
    }

    @Override
    protected void execute(TaskListener listener) {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        if (config == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.getRetentionDays());
        int removed = MetricsStore.get().deleteOlderThan(cutoff);
        if (removed > 0) {
            listener.getLogger().println("Pipeline Metrics: removed " + removed + " expired build records");
        }
    }
}
