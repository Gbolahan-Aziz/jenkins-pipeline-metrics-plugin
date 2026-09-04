package io.jenkins.plugins.pipelinemetrics.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.io.File;
import java.sql.ResultSet;
import net.sf.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StorageMigratorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MetricsStore source;
    private MetricsStore target;

    @Before
    public void setUp() {
        source = new MetricsStore(new File(tmp.getRoot(), "source.db"));
        source.init();
        assertTrue(source.isAvailable());

        target = new MetricsStore(new File(tmp.getRoot(), "target.db"));
        target.init();
        assertTrue(target.isAvailable());

        BuildRecord b1 = build("demo", 1, "SUCCESS", 1_000L);
        b1.getStages().add(new StageRecord("Build", "SUCCESS", 500, 0));
        b1.getStages().add(new StageRecord("Test", "SUCCESS", 800, 1));
        source.upsertBuild(b1);

        BuildRecord b2 = build("demo", 2, "FAILURE", 2_000L);
        source.upsertBuild(b2);
    }

    private static BuildRecord build(String job, int number, String result, long ts) {
        BuildRecord b = new BuildRecord();
        b.setJobFullName(job);
        b.setJobFolder("");
        b.setJobName(job);
        b.setBuildNumber(number);
        b.setResult(result);
        b.setTimestampMs(ts);
        b.setBuiltOn("master");
        return b;
    }

    @Test
    public void copiesAllBuildsAndStagesFromSourceToTarget() throws Exception {
        JSONObject result = StorageMigrator.migrate(source, target);

        assertEquals("ok", result.getString("status"));
        assertEquals(2, result.getInt("read"));
        assertEquals(2, result.getInt("inserted"));
        assertEquals(0, result.getInt("updated"));
        assertEquals(0, result.getInt("skipped"));

        assertEquals(2, target.buildCount());
        int stageCount = target.query(conn -> {
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM stages")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
        assertEquals(2, stageCount);
    }

    @Test
    public void reRunningMigrationUpsertsRatherThanDuplicates() throws Exception {
        StorageMigrator.migrate(source, target);
        JSONObject second = StorageMigrator.migrate(source, target);

        assertEquals(2, second.getInt("read"));
        assertEquals(0, second.getInt("inserted"));
        assertEquals(2, second.getInt("updated"));
        assertEquals(2, target.buildCount());
    }
}
