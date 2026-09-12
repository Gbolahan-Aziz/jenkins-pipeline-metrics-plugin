package io.jenkins.plugins.pipelinemetrics.store.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SQLiteStorageBackendTest {

    @Test
    void buildsAWorkingPoolUnderJenkinsHome(JenkinsRule j) throws Exception {
        SQLiteStorageBackend backend = new SQLiteStorageBackend();
        try (HikariDataSource ds = backend.createDataSource()) {
            MetricsStore store = new MetricsStore(ds, backend.dialect());
            store.init();
            assertTrue(store.isAvailable(), "store should initialize against the backend-built pool");
            assertTrue(store.buildCount() >= 0);
        }
    }

    @Test
    void describesItselfByFixedPath(JenkinsRule j) {
        assertTrue(new SQLiteStorageBackend().describe().contains("metrics.db"));
    }
}
