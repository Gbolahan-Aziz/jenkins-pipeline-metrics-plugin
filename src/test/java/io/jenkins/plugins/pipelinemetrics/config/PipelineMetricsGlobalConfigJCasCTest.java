package io.jenkins.plugins.pipelinemetrics.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.pipelinemetrics.store.backend.MySqlStorageBackend;
import io.jenkins.plugins.pipelinemetrics.store.backend.PostgresStorageBackend;
import io.jenkins.plugins.pipelinemetrics.store.backend.SQLiteStorageBackend;
import org.junit.jupiter.api.Test;

/**
 * JCasC round-trip coverage for the {@code storageBackend} selector: default (unset, stays
 * SQLite — the zero-action upgrade guarantee), external PostgreSQL, and external MySQL/MariaDB.
 */
@WithJenkinsConfiguredWithCode
class PipelineMetricsGlobalConfigJCasCTest {

    @Test
    @ConfiguredWithCode("defaultBackend.yml")
    void omittingStorageBackendStaysOnLocalSqlite(JenkinsConfiguredWithCodeRule j) {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        assertEquals(45, config.getRetentionDays());
        assertTrue(config.getStorageBackend() instanceof SQLiteStorageBackend,
                "no storageBackend in YAML should leave the SQLite default");
    }

    @Test
    @ConfiguredWithCode("postgres.yml")
    void appliesExternalPostgresConfig(JenkinsConfiguredWithCodeRule j) {
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
    void appliesExternalMySqlConfig(JenkinsConfiguredWithCodeRule j) {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        assertTrue(config.getStorageBackend() instanceof MySqlStorageBackend);
        MySqlStorageBackend backend = (MySqlStorageBackend) config.getStorageBackend();
        assertEquals("mysql.internal", backend.getHost());
        assertEquals(3306, backend.getPort());
        assertEquals("jenkins_metrics", backend.getDatabase());
        assertEquals("pipeline-metrics-db", backend.getCredentialsId());
        assertTrue(!backend.isUseSsl(), "useSsl: false in the YAML");
        assertEquals(8, backend.getMaxPoolSize());
    }
}
