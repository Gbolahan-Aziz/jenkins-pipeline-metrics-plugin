package io.jenkins.plugins.pipelinemetrics.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import java.sql.ResultSet;
import org.junit.Before;
import org.junit.Test;

/**
 * Correctness properties every {@link MetricsStore} backend must satisfy, independent of which
 * {@code SqlDialect}/engine backs it: upsert idempotency, stage replace-on-write, and retention
 * monotonicity (see {@code design.md}'s Properties 1 and 4). Concrete subclasses supply a fresh,
 * empty, initialized store per test via {@link #openStore()} — {@code SqliteMetricsStoreTest}
 * runs this in the default build; the Postgres/MariaDB {@code *IT} subclasses run it under the
 * opt-in {@code -Pdb-it} Testcontainers profile.
 */
public abstract class AbstractMetricsStoreContractTest {

    protected MetricsStore store;

    @Before
    public void setUpStore() throws Exception {
        store = openStore();
        assertTrue("store should initialize: " + store.getUnavailableReason(), store.isAvailable());
    }

    /** A fresh, empty, initialized store — must not share state across test methods. */
    protected abstract MetricsStore openStore() throws Exception;

    protected static BuildRecord build(String job, int number, String result, long ts) {
        BuildRecord b = new BuildRecord();
        b.setJobFullName(job);
        b.setJobFolder("");
        b.setJobName(job);
        b.setBuildNumber(number);
        b.setResult(result);
        b.setDurationMs(1000);
        b.setTimestampMs(ts);
        b.setBuiltOn("master");
        return b;
    }

    @Test
    public void upsertIsIdempotent() throws Exception {
        BuildRecord b = build("demo", 1, "SUCCESS", System.currentTimeMillis());
        b.getStages().add(new StageRecord("Build", "SUCCESS", 500, 0));
        store.upsertBuild(b);
        store.upsertBuild(b);

        assertEquals("one build row after repeated upsert", 1, count("SELECT COUNT(*) FROM builds"));
        assertEquals("stages replaced, not duplicated", 1, count("SELECT COUNT(*) FROM stages"));
    }

    @Test
    public void upsertUpdatesResultAndReplacesStages() throws Exception {
        BuildRecord running = build("demo", 2, null, System.currentTimeMillis());
        running.getStages().add(new StageRecord("A", "SUCCESS", 100, 0));
        store.upsertBuild(running);

        BuildRecord finished = build("demo", 2, "FAILURE", System.currentTimeMillis());
        finished.getStages().add(new StageRecord("A", "SUCCESS", 100, 0));
        finished.getStages().add(new StageRecord("B", "FAILED", 200, 1));
        store.upsertBuild(finished);

        assertEquals(1, count("SELECT COUNT(*) FROM builds"));
        assertEquals(2, count("SELECT COUNT(*) FROM stages"));
        assertEquals("FAILURE", str("SELECT result FROM builds WHERE build_number=2"));
    }

    @Test
    public void retentionRemovesOldRecordsOnly() throws Exception {
        long now = System.currentTimeMillis();
        long old = now - (100L * 24 * 60 * 60 * 1000);
        store.upsertBuild(build("demo", 3, "SUCCESS", old));
        store.upsertBuild(build("demo", 4, "SUCCESS", now));

        int removed = store.deleteOlderThan(now - (30L * 24 * 60 * 60 * 1000));
        assertEquals(1, removed);
        assertEquals(1, count("SELECT COUNT(*) FROM builds"));
    }

    protected int count(String sql) throws Exception {
        return store.query(conn -> {
            try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    protected String str(String sql) throws Exception {
        return store.query(conn -> {
            try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }
}
