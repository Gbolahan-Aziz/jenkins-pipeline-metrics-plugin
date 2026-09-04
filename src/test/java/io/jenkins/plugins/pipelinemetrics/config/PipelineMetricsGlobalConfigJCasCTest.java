package io.jenkins.plugins.pipelinemetrics.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.pipelinemetrics.store.backend.MySqlStorageBackend;
import io.jenkins.plugins.pipelinemetrics.store.backend.PostgresStorageBackend;
import io.jenkins.plugins.pipelinemetrics.store.backend.SQLiteStorageBackend;
import org.junit.Rule;
import org.junit.Test;

/**
 * JCasC round-trip coverage for the {@code storageBackend} selector: default (unset, stays
 * SQLite — the zero-action upgrade guarantee), external PostgreSQL, and external MySQL/MariaDB.
 */
public class PipelineMetricsGlobalConfigJCasCTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("defaultBackend.yml")
    public void omittingStorageBackendStaysOnLocalSqlite() {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        assertEquals(45, config.getRetentionDays());
        assertTrue("no storageBackend in YAML should leave the SQLite default",
                config.getStorageBackend() instanceof SQLiteStorageBackend);
    }

    @Test
    @ConfiguredWithCode("postgres.yml")
    public void appliesExternalPostgresConfig() {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        assertTrue(config.getStorageBackend() instanceof PostgresStorageBackend);
        PostgresStorageBackend backend = (PostgresStorageBackend) config.getStorageBackend();
        assertEquals("pg.internal", backend.getHost());
        assertEquals(5432, backend.getPort());
        assertEquals("jenkins_metrics", backend.getDatabase());
        assertEquals("pipeline-metrics-db", backend.getCredentialsId());
        assertTrue(backend.isUseSsl());
        assertEquals(10, backend.getMaxPoolSize());
    }

    @Test
    @ConfiguredWithCode("mysql.yml")
    public void appliesExternalMySqlConfig() {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        assertTrue(config.getStorageBackend() instanceof MySqlStorageBackend);
        MySqlStorageBackend backend = (MySqlStorageBackend) config.getStorageBackend();
        assertEquals("mysql.internal", backend.getHost());
        assertEquals(3306, backend.getPort());
        assertEquals("jenkins_metrics", backend.getDatabase());
        assertEquals("pipeline-metrics-db", backend.getCredentialsId());
        assertTrue("useSsl: false in the YAML", !backend.isUseSsl());
        assertEquals(8, backend.getMaxPoolSize());
    }
}
