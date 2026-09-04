package io.jenkins.plugins.pipelinemetrics.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.pipelinemetrics.store.backend.SQLiteStorageBackend;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

/**
 * Proves the zero-action upgrade guarantee: an install whose persisted config.xml predates the
 * {@code storageBackend} field (no such element in the XML) keeps behaving exactly as before —
 * it resolves to local SQLite at the historical fixed path, not to some undefined/null state.
 */
public class PipelineMetricsGlobalConfigUpgradeTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    @LocalData
    public void resolvesToSqliteWhenStorageBackendAbsentFromPersistedConfig() {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        assertEquals("pre-existing fields still load correctly", 60, config.getRetentionDays());
        assertEquals(500, config.getBackfillLimit());
        assertTrue("pre-existing config.xml with no storageBackend element must default to SQLite",
                config.getStorageBackend() instanceof SQLiteStorageBackend);
    }
}
