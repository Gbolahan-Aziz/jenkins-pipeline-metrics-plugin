package io.jenkins.plugins.pipelinemetrics.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.store.dialect.PostgresDialect;
import org.junit.Rule;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Runs the shared store contract against a real PostgreSQL server via Testcontainers. Opt-in
 * only ({@code mvn -B verify -Pdb-it}); requires a local Docker daemon. A fresh container per
 * test method keeps each test's row counts isolated, matching the SQLite variant's fresh temp
 * file per test.
 */
public class PostgresMetricsStoreIT extends AbstractMetricsStoreContractTest {

    @Rule
    public PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    protected MetricsStore openStore() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(5);
        config.setPoolName("pipeline-metrics-postgresql-it");
        HikariDataSource ds = new HikariDataSource(config);
        MetricsStore store = new MetricsStore(ds, new PostgresDialect());
        store.init();
        return store;
    }
}
