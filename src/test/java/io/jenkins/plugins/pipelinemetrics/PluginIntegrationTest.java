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
        // Regression test: the dashboard is a raw HTML view that doesn't use Jenkins' <l:layout>,
        // so it has no bound "rootURL" jelly variable. Using it there silently rendered every
        // asset/API URL without the context path (e.g. "/plugin/..." instead of
        // "/jenkins/plugin/..."), 404ing the CSS, JS, and every dashboard API call under any
        // non-root context path. JenkinsRule defaults to the "/jenkins" context path, matching
        // hpi:run's own default.
        // JS disabled: this only inspects the server-rendered HTML, and HtmlUnit's JS engine
        // can't parse the chartjs-api-plugin's own bundled chart.umd.js anyway.
        JenkinsRule.WebClient webClient = j.createWebClient();
        webClient.setJavaScriptEnabled(false);
        String html = webClient.goTo("pipeline-metrics", "text/html")
                .getWebResponse().getContentAsString();
        assertTrue(html.contains("\"/jenkins/plugin/pipeline-metrics/js/app.js\""),
                "app.js must be referenced under the context path");
        assertTrue(html.contains("\"/jenkins/plugin/pipeline-metrics/css/style.css\""),
                "style.css must be referenced under the context path");
        assertTrue(html.contains("data-base=\"/jenkins/pipeline-metrics/api\""),
                "data-base must include the context path");
        assertFalse(html.contains("\"/plugin/") || html.contains("data-base=\"/pipeline-metrics/api\""),
                "no asset/API URL should be missing the context path");
    }

    private static void assertTrueEquals(String expected, String actual) {
        assertTrue(expected.equals(actual), "expected " + expected + " but was " + actual);
    }
}
