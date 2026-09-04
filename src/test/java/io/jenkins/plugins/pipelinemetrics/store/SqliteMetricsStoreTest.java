package io.jenkins.plugins.pipelinemetrics.store;

import java.io.File;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/** Runs the shared store contract against embedded SQLite. Fast, Docker-free, default gate. */
public class SqliteMetricsStoreTest extends AbstractMetricsStoreContractTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Override
    protected MetricsStore openStore() {
        MetricsStore store = new MetricsStore(new File(tmp.getRoot(), "metrics.db"));
        store.init();
        return store;
    }
}
