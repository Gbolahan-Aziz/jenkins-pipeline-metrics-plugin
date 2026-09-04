package io.jenkins.plugins.pipelinemetrics.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.store.dialect.MySqlDialect;
import org.junit.Rule;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Runs the shared store contract against a real MariaDB server via Testcontainers, proving the
 * one MySQL/MariaDB dialect and driver work against the MariaDB side of that pairing. Opt-in
 * only ({@code mvn -B verify -Pdb-it}); requires a local Docker daemon.
 */
public class MariaDbMetricsStoreIT extends AbstractMetricsStoreContractTest {

    @Rule
    public MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11");

    @Override
    protected MetricsStore openStore() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mariadb.getJdbcUrl());
        config.setUsername(mariadb.getUsername());
        config.setPassword(mariadb.getPassword());
        config.setMaximumPoolSize(5);
        config.setPoolName("pipeline-metrics-mysql-it");
        HikariDataSource ds = new HikariDataSource(config);
        MetricsStore store = new MetricsStore(ds, new MySqlDialect());
        store.init();
        return store;
    }
}
