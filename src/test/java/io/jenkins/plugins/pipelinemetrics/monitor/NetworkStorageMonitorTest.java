package io.jenkins.plugins.pipelinemetrics.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.pipelinemetrics.config.PipelineMetricsGlobalConfig;
import io.jenkins.plugins.pipelinemetrics.store.backend.PostgresStorageBackend;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class NetworkStorageMonitorTest {

    @Test
    void recognizesKnownNetworkFilesystemTypes(JenkinsRule j) {
        assertTrue(NetworkStorageMonitor.isNetworkFilesystemType("nfs4"));
        assertTrue(NetworkStorageMonitor.isNetworkFilesystemType("NFS4"));
        assertTrue(NetworkStorageMonitor.isNetworkFilesystemType("cifs"));
        assertFalse(NetworkStorageMonitor.isNetworkFilesystemType("ext4"));
        assertFalse(NetworkStorageMonitor.isNetworkFilesystemType("xfs"));
        assertFalse(NetworkStorageMonitor.isNetworkFilesystemType("apfs"));
        assertFalse(NetworkStorageMonitor.isNetworkFilesystemType(null));
    }

    @Test
    void inactiveOnLocalTempStorageInCi(JenkinsRule j) {
        // The test JENKINS_HOME is always local temp storage, so the monitor must not fire here.
        NetworkStorageMonitor monitor = new NetworkStorageMonitor();
        assertFalse(monitor.isActivated());
    }

    @Test
    void inactiveWhenBackendIsNotSqlite(JenkinsRule j) {
        PostgresStorageBackend backend =
                new PostgresStorageBackend("pg.internal", 5432, "jenkins_metrics", "db-creds");
        PipelineMetricsGlobalConfig.get().setStorageBackend(backend);
        NetworkStorageMonitor monitor = new NetworkStorageMonitor();
        assertFalse(monitor.isActivated(), "only relevant when SQLite is the active backend");
    }
}
