package io.jenkins.plugins.pipelinemetrics.store;

import java.io.File;
import org.junit.jupiter.api.io.TempDir;

/** Runs the shared store contract against embedded SQLite. Fast, Docker-free, default gate. */
public class SqliteMetricsStoreTest extends AbstractMetricsStoreContractTest {

    @TempDir
    File tmp;

    @Override
    protected MetricsStore openStore() {
        MetricsStore store = new MetricsStore(new File(tmp, "metrics.db"));
        store.init();
        return store;
    }
}
