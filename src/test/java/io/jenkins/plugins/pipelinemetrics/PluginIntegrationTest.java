package io.jenkins.plugins.pipelinemetrics;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.RootAction;
import io.jenkins.plugins.pipelinemetrics.query.FilterSet;
import io.jenkins.plugins.pipelinemetrics.query.MetricsQueryService;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.web.PipelineMetricsRootAction;
import net.sf.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class PluginIntegrationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void rootActionIsRegistered() {
        PipelineMetricsRootAction action = null;
        for (RootAction a : j.jenkins.getExtensionList(RootAction.class)) {
            if (a instanceof PipelineMetricsRootAction) {
                action = (PipelineMetricsRootAction) a;
            }
        }
        assertNotNull("root action must be registered", action);
        assertTrueEquals("pipeline-metrics", action.getUrlName());
        assertNotNull("api node must be exposed", action.getApi());
    }

    @Test
    public void freestyleBuildIsCollected() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("demo-job");
        j.buildAndAssertSuccess(p);

        MetricsStore store = MetricsStore.get();
        JSONObject overview = new MetricsQueryService(store).overview(new FilterSet(30, "", "", ""));
        assertTrue("the finished build should be recorded", overview.getInt("total_builds") >= 1);
    }

    private static void assertTrueEquals(String expected, String actual) {
        assertTrue("expected " + expected + " but was " + actual, expected.equals(actual));
    }
}
