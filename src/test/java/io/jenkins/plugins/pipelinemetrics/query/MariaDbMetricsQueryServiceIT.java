package io.jenkins.plugins.pipelinemetrics.query;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.store.dialect.MySqlDialect;
import org.junit.Rule;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Proves {@link MySqlDialect}'s trends/heatmap bucketing expressions are valid, correct SQL
 * against a real server, not just plausible-looking text. Opt-in only
 * ({@code mvn -B verify -Pdb-it}); requires a local Docker daemon.
 */
public class MariaDbMetricsQueryServiceIT extends AbstractMetricsQueryServiceParityTest {

    @Rule
    public MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11");

    @Override
    protected MetricsStore openStore() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mariadb.getJdbcUrl());
        config.setUsername(mariadb.getUsername());
        config.setPassword(mariadb.getPassword());
        config.setMaximumPoolSize(5);
        config.setPoolName("pipeline-metrics-mysql-query-it");
        HikariDataSource ds = new HikariDataSource(config);
        MetricsStore store = new MetricsStore(ds, new MySqlDialect());
        store.init();
        return store;
    }
}
