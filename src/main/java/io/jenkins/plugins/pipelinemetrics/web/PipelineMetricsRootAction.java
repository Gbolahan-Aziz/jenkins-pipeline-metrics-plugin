package io.jenkins.plugins.pipelinemetrics.web;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import io.jenkins.plugins.pipelinemetrics.Messages;
import io.jenkins.plugins.pipelinemetrics.PipelineMetricsPermissions;
import jenkins.model.Jenkins;
import hudson.model.RootAction;

/** Global sidebar entry and dashboard host for the plugin. */
@Extension
public class PipelineMetricsRootAction implements RootAction {

    private final MetricsApi api = new MetricsApi();

    @Override
    @CheckForNull
    public String getIconFileName() {
        if (!Jenkins.get().hasPermission(PipelineMetricsPermissions.VIEW)) {
            return null;
        }
        return "/plugin/pipeline-metrics/images/analytics.svg";
    }

    @Override
    @CheckForNull
    public String getDisplayName() {
        return Messages.PipelineMetricsRootAction_DisplayName();
    }

    @Override
    public String getUrlName() {
        return "pipeline-metrics";
    }

    /** Exposes {@code /pipeline-metrics/api/*}. */
    public MetricsApi getApi() {
        return api;
    }

    public boolean isCanConfigure() {
        return Jenkins.get().hasPermission(PipelineMetricsPermissions.CONFIGURE);
    }
}
