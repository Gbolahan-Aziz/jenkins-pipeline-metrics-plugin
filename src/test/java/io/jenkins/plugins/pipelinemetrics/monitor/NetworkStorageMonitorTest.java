package io.jenkins.plugins.pipelinemetrics.monitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.pipelinemetrics.config.PipelineMetricsGlobalConfig;
import io.jenkins.plugins.pipelinemetrics.store.backend.PostgresStorageBackend;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class NetworkStorageMonitorTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void recognizesKnownNetworkFilesystemTypes() {
        assertTrue(NetworkStorageMonitor.isNetworkFilesystemType("nfs4"));
        assertTrue(NetworkStorageMonitor.isNetworkFilesystemType("NFS4"));
        assertTrue(NetworkStorageMonitor.isNetworkFilesystemType("cifs"));
        assertFalse(NetworkStorageMonitor.isNetworkFilesystemType("ext4"));
        assertFalse(NetworkStorageMonitor.isNetworkFilesystemType("xfs"));
        assertFalse(NetworkStorageMonitor.isNetworkFilesystemType("apfs"));
        assertFalse(NetworkStorageMonitor.isNetworkFilesystemType(null));
    }

    @Test
    public void inactiveOnLocalTempStorageInCi() {
        // The test JENKINS_HOME is always local temp storage, so the monitor must not fire here.
        NetworkStorageMonitor monitor = new NetworkStorageMonitor();
        assertFalse(monitor.isActivated());
    }

    @Test
    public void inactiveWhenBackendIsNotSqlite() {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "db-creds");
        PipelineMetricsGlobalConfig.get().setStorageBackend(backend);
        NetworkStorageMonitor monitor = new NetworkStorageMonitor();
        assertFalse("only relevant when SQLite is the active backend", monitor.isActivated());
    }
}
