package io.jenkins.plugins.pipelinemetrics.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.pipelinemetrics.store.backend.SQLiteStorageBackend;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.junit.jupiter.WithLocalData;

/**
 * Proves the zero-action upgrade guarantee: an install whose persisted config.xml predates the
 * {@code storageBackend} field (no such element in the XML) keeps behaving exactly as before —
 * it resolves to local SQLite at the historical fixed path, not to some undefined/null state.
 */
@WithJenkins
class PipelineMetricsGlobalConfigUpgradeTest {

    @Test
    @WithLocalData
    void resolvesToSqliteWhenStorageBackendAbsentFromPersistedConfig(JenkinsRule j) {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        assertEquals(60, config.getRetentionDays(), "pre-existing fields still load correctly");
        assertEquals(500, config.getBackfillLimit());
        assertTrue(config.getStorageBackend() instanceof SQLiteStorageBackend,
                "pre-existing config.xml with no storageBackend element must default to SQLite");
    }
}
