package io.jenkins.plugins.pipelinemetrics.query;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.store.dialect.PostgresDialect;
import org.junit.Rule;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves {@link PostgresDialect}'s trends/heatmap bucketing expressions are valid, correct SQL
 * against a real server, not just plausible-looking text. Opt-in only
 * ({@code mvn -B verify -Pdb-it}); requires a local Docker daemon.
 */
public class PostgresMetricsQueryServiceIT extends AbstractMetricsQueryServiceParityTest {

    @Rule
    public PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    protected MetricsStore openStore() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(5);
        config.setPoolName("pipeline-metrics-postgresql-query-it");
        HikariDataSource ds = new HikariDataSource(config);
        MetricsStore store = new MetricsStore(ds, new PostgresDialect());
        store.init();
        return store;
    }
}
