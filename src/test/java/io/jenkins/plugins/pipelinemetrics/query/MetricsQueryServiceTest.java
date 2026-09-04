package io.jenkins.plugins.pipelinemetrics.query;

import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.io.File;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/** Runs the shared query parity contract against embedded SQLite. Fast, Docker-free, default gate. */
public class MetricsQueryServiceTest extends AbstractMetricsQueryServiceParityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Override
    protected MetricsStore openStore() {
        MetricsStore store = new MetricsStore(new File(tmp.getRoot(), "metrics.db"));
        store.init();
        return store;
    }
}
