package io.jenkins.plugins.pipelinemetrics.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.store.SqliteConnectionFactory;
import io.jenkins.plugins.pipelinemetrics.store.dialect.SqliteDialect;
import java.io.File;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression test for a bug found during review: {@code MetricsApi} holds a single, long-lived
 * {@code MetricsQueryService} built once at plugin-load time. Its no-arg constructor must
 * re-resolve {@link MetricsStore#get()} on every query rather than capturing a snapshot,
 * otherwise switching the configured storage backend (which swaps and closes the singleton via
 * {@link MetricsStore#reconfigure}) leaves the dashboard permanently bound to a closed pool
 * until Jenkins restarts.
 */
@WithJenkins
class MetricsQueryServiceLiveBackendSwitchTest {

    @TempDir
    File tmp;

    @Test
    void noArgServiceFollowsTheStoreAcrossAReconfigure(JenkinsRule j) throws Exception {
        // Force-create the default singleton first, the same way any other extension would on boot.
        MetricsStore.get();

        MetricsQueryService query = new MetricsQueryService();

        JSONObject before = query.overview(new FilterSet(30, "", "", ""));
        assertEquals(0, before.getInt("total_builds"), "no data in the original store yet");

        // Simulate an admin switching the storage backend: a fresh pool, swapped into the
        // singleton, with the old one closed underneath it.
        HikariDataSource newDs = SqliteConnectionFactory.forFile(new File(tmp, "swapped.db"));
        boolean applied = MetricsStore.reconfigure(newDs, new SqliteDialect());
        assertEquals(true, applied);

        BuildRecord b = new BuildRecord();
        b.setJobFullName("demo");
        b.setJobFolder("");
        b.setJobName("demo");
        b.setBuildNumber(1);
        b.setResult("SUCCESS");
        b.setTimestampMs(System.currentTimeMillis());
        b.setBuiltOn("master");
        MetricsStore.get().upsertBuild(b);

        // Must see the new store's data through the SAME MetricsQueryService instance, and must
        // not throw from trying to use the now-closed old pool.
        JSONObject after = query.overview(new FilterSet(30, "", "", ""));
        assertEquals(1, after.getInt("total_builds"),
                "query must follow the reconfigured store, not a stale snapshot");
    }
}
