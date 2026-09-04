package io.jenkins.plugins.pipelinemetrics.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.jenkins.plugins.pipelinemetrics.model.BuildRecord;
import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the analytics queries against every {@code SqlDialect}: trends/heatmap bucketing
 * (each dialect's {@code periodBucketExpr}/{@code dayOfWeekExpr}/{@code hourOfDayExpr} must
 * actually execute correctly, not just look right as SQL text) and the Java-computed date
 * windows in {@link FilterSet}/{@code stages()}. {@code MetricsQueryServiceTest} runs this
 * against SQLite in the default build; the Postgres/MariaDB {@code *IT} subclasses run it under
 * the opt-in {@code -Pdb-it} Testcontainers profile.
 */
public abstract class AbstractMetricsQueryServiceParityTest {

    protected MetricsQueryService query;

    /** A fresh, empty, initialized store — must not share state across test methods. */
    protected abstract MetricsStore openStore() throws Exception;

    @Before
    public void setUp() throws Exception {
        MetricsStore store = openStore();
        assertTrue("store should initialize: " + store.getUnavailableReason(), store.isAvailable());

        long now = System.currentTimeMillis();
        BuildRecord success = build("demo", 1, "SUCCESS", now - HOURS(3));
        success.setDurationMs(1000);
        success.setQueueTimeMs(100);
        store.upsertBuild(success);

        BuildRecord failure = build("demo", 2, "FAILURE", now - HOURS(1));
        failure.setDurationMs(2000);
        failure.setQueueTimeMs(200);
        failure.getStages().add(new StageRecord("Build", "FAILED", 500, 0));
        store.upsertBuild(failure);

        query = new MetricsQueryService(store);
    }

    private static long HOURS(int h) {
        return h * 3_600_000L;
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
    public void trendsBucketsBothBuildsWithinWindow() throws Exception {
        JSONArray periods = query.trends(new FilterSet(1, "", "", ""), "hour");
        int total = 0;
        int success = 0;
        int failures = 0;
        for (Object o : periods) {
            JSONObject p = (JSONObject) o;
            total += p.optInt("total", 0);
            success += p.optInt("success", 0);
            failures += p.optInt("failures", 0);
        }
        assertEquals("both builds counted across periods", 2, total);
        assertEquals(1, success);
        assertEquals(1, failures);
        // Timestamps are 2h apart, well over the 1h bucket width, so they must land in
        // different "hour" periods (each period spans a single UTC hour).
        assertEquals("two distinct hour buckets", 2, periods.size());
    }

    @Test
    public void trendsRejectsInvalidGrouping() throws Exception {
        try {
            query.trends(new FilterSet(1, "", "", ""), "month");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("month"));
        }
    }

    @Test
    public void heatmapCountsAllBuildsAcrossDayHourBuckets() throws Exception {
        JSONArray cells = query.heatmap(new FilterSet(1, "", "", ""));
        int total = 0;
        int failures = 0;
        for (Object o : cells) {
            JSONObject c = (JSONObject) o;
            total += c.optInt("count", 0);
            failures += c.optInt("failures", 0);
        }
        assertEquals(2, total);
        assertEquals(1, failures);
    }

    @Test
    public void stagesReflectsOnlyRecordedStages() throws Exception {
        JSONArray stages = query.stages("demo", 1, "", "", "");
        assertEquals(1, stages.size());
        JSONObject stage = stages.getJSONObject(0);
        assertEquals("Build", stage.getString("stage_name"));
        assertEquals(1, stage.getInt("run_count"));
        assertEquals(1.0, stage.getDouble("failure_rate"), 0.0001);
    }

    @Test
    public void stagesHonorsFolderAndAgentFilters() throws Exception {
        // A separate store/dataset so this doesn't perturb the shared setUp() data other tests
        // in this class depend on for their exact counts.
        MetricsStore store = openStore();
        assertTrue(store.isAvailable());
        long now = System.currentTimeMillis();

        BuildRecord teamA = build("team-a-job", 1, "SUCCESS", now - HOURS(1));
        teamA.setJobFolder("team-a");
        teamA.setBuiltOn("agent1");
        teamA.getStages().add(new StageRecord("Build", "SUCCESS", 100, 0));
        store.upsertBuild(teamA);

        BuildRecord teamB = build("team-b-job", 1, "SUCCESS", now - HOURS(1));
        teamB.setJobFolder("team-b");
        teamB.setBuiltOn("agent2");
        teamB.getStages().add(new StageRecord("Deploy", "SUCCESS", 300, 0));
        store.upsertBuild(teamB);

        MetricsQueryService isolatedQuery = new MetricsQueryService(store);

        JSONArray byFolder = isolatedQuery.stages(null, 1, "team-b", "", "");
        assertEquals("folder filter should exclude team-a's stage", 1, byFolder.size());
        assertEquals("Deploy", byFolder.getJSONObject(0).getString("stage_name"));

        JSONArray byAgent = isolatedQuery.stages(null, 1, "", "agent1", "");
        assertEquals("agent filter should exclude team-b's stage", 1, byAgent.size());
        assertEquals("Build", byAgent.getJSONObject(0).getString("stage_name"));

        JSONArray unfiltered = isolatedQuery.stages(null, 1, "", "", "");
        assertEquals("no folder/agent filter returns both stages", 2, unfiltered.size());
    }

    @Test
    public void overviewDeltaWindowExcludesOlderPriorPeriod() throws Exception {
        JSONObject overview = query.overview(new FilterSet(1, "", "", ""));
        assertEquals(2, overview.getInt("total_builds"));
        assertEquals(1, overview.getInt("successful"));
        assertEquals(1, overview.getInt("failed"));
        // The preceding 1-day window (day -2..-1) has no data of its own.
        assertEquals(0, overview.getInt("prev_total_builds"));
    }
}
