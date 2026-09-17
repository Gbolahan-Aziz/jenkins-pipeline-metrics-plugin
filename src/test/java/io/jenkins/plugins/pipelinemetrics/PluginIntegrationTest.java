package io.jenkins.plugins.pipelinemetrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.RootAction;
import io.jenkins.plugins.pipelinemetrics.query.FilterSet;
import io.jenkins.plugins.pipelinemetrics.query.MetricsQueryService;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.web.PipelineMetricsRootAction;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PluginIntegrationTest {

    @Test
    void rootActionIsRegistered(JenkinsRule j) {
        PipelineMetricsRootAction action = null;
        for (RootAction a : j.jenkins.getExtensionList(RootAction.class)) {
            if (a instanceof PipelineMetricsRootAction) {
                action = (PipelineMetricsRootAction) a;
            }
        }
        assertNotNull(action, "root action must be registered");
        assertTrueEquals("pipeline-metrics", action.getUrlName());
        assertNotNull(action.getApi(), "api node must be exposed");
    }

    @Test
    void freestyleBuildIsCollected(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("demo-job");
        j.buildAndAssertSuccess(p);

        MetricsStore store = MetricsStore.get();
        MetricsQueryService query = new MetricsQueryService(store);
        JSONObject overview = query.overview(new FilterSet(30, "", "", ""));
        assertTrue(overview.getInt("total_builds") >= 1, "the finished build should be recorded");

        // The controller's built-in node must be reported using current Jenkins terminology
        // ("built-in"), never the legacy "master" label, in both the raw agent filter list and
        // the agents() display column.
        JSONArray filterAgents = query.filters().getJSONArray("agents");
        assertTrue(!filterAgents.contains("master"),
                "agent filter list must not contain the legacy 'master' label");
        JSONArray agents = query.agents(new FilterSet(30, "", "", ""));
        assertTrueEquals("built-in", agents.getJSONObject(0).getString("agent"));
    }

    @Test
    void dashboardAssetUrlsIncludeContextPath(JenkinsRule j) throws Exception {
        // Regression test: the dashboard's asset and API URLs must include the context path.
        // An earlier version rendered them as "/plugin/..." instead of "/jenkins/plugin/...",
        // which broke every asset and API call under a non-root context path. JenkinsRule uses
        // "/jenkins", the same as hpi:run.
        // JS disabled: this only inspects the server-rendered HTML.
        String html = dashboardHtml(j);
        assertTrue(html.contains("/jenkins/adjuncts/")
                        && html.contains("PipelineMetricsRootAction/dashboard.js")
                        && html.contains("PipelineMetricsRootAction/dashboard.css"),
                "dashboard script and styles must load as adjuncts under the context path");
        assertTrue(html.contains("data-base=\"/jenkins/pipeline-metrics/api\""),
                "data-base must include the context path");
        assertFalse(html.contains("\"/plugin/") || html.contains("data-base=\"/pipeline-metrics/api\""),
                "no asset/API URL should be missing the context path");
    }

    @Test
    void chartLibraryOnlyLoadsOnTheDashboard(JenkinsRule j) throws Exception {
        // The chart library is only needed by the dashboard. It must not be added to every page in
        // Jenkins, which is what happened with a PageDecorator-based chart plugin.
        assertTrue(dashboardHtml(j).contains("echarts"), "the dashboard should load ECharts");

        JenkinsRule.WebClient webClient = j.createWebClient();
        webClient.setJavaScriptEnabled(false);
        String home = webClient.goTo("").getWebResponse().getContentAsString();
        assertFalse(home.contains("echarts") || home.contains("chart.umd.js"),
                "no chart library should load on the Jenkins home page");
    }

    private static String dashboardHtml(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient webClient = j.createWebClient();
        webClient.setJavaScriptEnabled(false);
        return webClient.goTo("pipeline-metrics", "text/html").getWebResponse().getContentAsString();
    }

    private static void assertTrueEquals(String expected, String actual) {
        assertTrue(expected.equals(actual), "expected " + expected + " but was " + actual);
    }
}
