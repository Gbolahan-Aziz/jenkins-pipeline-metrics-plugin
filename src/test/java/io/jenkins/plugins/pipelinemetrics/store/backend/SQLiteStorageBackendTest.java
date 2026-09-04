package io.jenkins.plugins.pipelinemetrics.store.backend;

import static org.junit.Assert.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class SQLiteStorageBackendTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void buildsAWorkingPoolUnderJenkinsHome() throws Exception {
        SQLiteStorageBackend backend = new SQLiteStorageBackend();
        try (HikariDataSource ds = backend.createDataSource()) {
            MetricsStore store = new MetricsStore(ds, backend.dialect());
            store.init();
            assertTrue("store should initialize against the backend-built pool", store.isAvailable());
            assertTrue(store.buildCount() >= 0);
        }
    }

    @Test
    public void describesItselfByFixedPath() {
        assertTrue(new SQLiteStorageBackend().describe().contains("metrics.db"));
    }
}
