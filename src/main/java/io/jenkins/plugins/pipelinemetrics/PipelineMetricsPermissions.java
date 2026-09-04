package io.jenkins.plugins.pipelinemetrics;

import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import jenkins.model.Jenkins;

/**
 * Permission group for the Pipeline Metrics plugin: VIEW gates the dashboard and
 * read APIs, CONFIGURE gates configuration changes, backfill, import, and manual collection.
 */
public final class PipelineMetricsPermissions {

    public static final PermissionGroup GROUP =
            new PermissionGroup(PipelineMetricsPermissions.class, Messages._PipelineMetrics_PermissionGroup());

    public static final Permission VIEW = new Permission(
            GROUP,
            "View",
            Messages._PipelineMetrics_ViewPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    public static final Permission CONFIGURE = new Permission(
            GROUP,
            "Configure",
            Messages._PipelineMetrics_ConfigurePermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    private PipelineMetricsPermissions() {
    }
}
