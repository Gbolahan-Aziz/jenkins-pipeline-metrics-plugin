package io.jenkins.plugins.pipelinemetrics.collect;

import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.pipelinemetrics.model.StageRecord;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Regression test for a bug found during review: computing a stage's duration as "the next
 * stage's start time minus this one's" silently corrupts durations for any stage inside a
 * {@code parallel} block, since branches are siblings rather than a sequence. Runs a real
 * scripted pipeline through the CPS engine (rather than hand-built FlowNode mocks) so the fix —
 * using each stage's own {@code BlockEndNode} — is proven against the real flow graph shape.
 */
public class StageExtractorTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void parallelBranchesGetTheirOwnAccurateDuration() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "parallel-stages");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
              + "  stage('Build') {\n"
              + "    echo 'building'\n"
              + "  }\n"
              + "  stage('Tests') {\n"
              + "    parallel(\n"
              + "      fast: { sleep 1 },\n"
              + "      slow: { sleep 3 }\n"
              + "    )\n"
              + "  }\n"
              + "}\n", true));

        WorkflowRun run = j.buildAndAssertSuccess(job);

        List<StageRecord> stages = StageExtractor.extract(run);
        Map<String, StageRecord> byName = stages.stream()
                .collect(Collectors.toMap(StageRecord::getStageName, s -> s, (a, b) -> a));

        assertTrue("expected a 'fast' branch stage, got " + byName.keySet(), byName.containsKey("Branch: fast"));
        assertTrue("expected a 'slow' branch stage, got " + byName.keySet(), byName.containsKey("Branch: slow"));

        long fastMs = byName.get("Branch: fast").getDurationMs();
        long slowMs = byName.get("Branch: slow").getDurationMs();

        // With the bug, 'fast' and 'slow' start at nearly the same wall-clock moment, so
        // inferring duration from "whichever labeled node starts next" collapses one of them to
        // a near-zero or wildly wrong value instead of its own real ~1s/~3s sleep.
        assertTrue("fast branch duration should reflect its own ~1s sleep, was " + fastMs,
                fastMs >= 500 && fastMs < 2500);
        assertTrue("slow branch duration should reflect its own ~3s sleep, was " + slowMs,
                slowMs >= 2500);
    }
}
