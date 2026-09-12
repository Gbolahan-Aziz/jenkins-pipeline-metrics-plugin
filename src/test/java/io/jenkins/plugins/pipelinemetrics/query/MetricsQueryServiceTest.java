package io.jenkins.plugins.pipelinemetrics.query;

import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.io.File;
import org.junit.jupiter.api.io.TempDir;

/** Runs the shared query parity contract against embedded SQLite. Fast, Docker-free, default gate. */
public class MetricsQueryServiceTest extends AbstractMetricsQueryServiceParityTest {

    @TempDir
    File tmp;

    @Override
    protected MetricsStore openStore() {
        MetricsStore store = new MetricsStore(new File(tmp, "metrics.db"));
        store.init();
        return store;
    }
}
