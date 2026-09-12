package io.jenkins.plugins.pipelinemetrics.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.store.dialect.MySqlDialect;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the shared store contract against a real MariaDB server via Testcontainers, proving the
 * one MySQL/MariaDB dialect and driver work against the MariaDB side of that pairing. Opt-in
 * only ({@code mvn -B verify -Pdb-it}); requires a local Docker daemon.
 */
@Testcontainers
public class MariaDbMetricsStoreIT extends AbstractMetricsStoreContractTest {

    @Container
    MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11");

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
