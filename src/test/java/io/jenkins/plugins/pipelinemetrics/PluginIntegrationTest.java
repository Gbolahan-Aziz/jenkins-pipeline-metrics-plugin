package io.jenkins.plugins.pipelinemetrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.RootAction;
import io.jenkins.plugins.pipelinemetrics.query.FilterSet;
import io.jenkins.plugins.pipelinemetrics.query.MetricsQueryService;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.web.PipelineMetricsRootAction;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class PluginIntegrationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    {
        // Exercises the dashboard under a non-root context path, matching hpi:run's own default
        // ("/jenkins") and plenty of real deployments. The default "" test context path can't
        // catch a page that silently drops the context path from its asset/API URLs.
        j.contextPath = "/jenkins";
    }

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
        MetricsQueryService query = new MetricsQueryService(store);
        JSONObject overview = query.overview(new FilterSet(30, "", "", ""));
        assertTrue("the finished build should be recorded", overview.getInt("total_builds") >= 1);

        // The controller's built-in node must be reported using current Jenkins terminology
        // ("built-in"), never the legacy "master" label, in both the raw agent filter list and
        // the agents() display column.
        JSONArray filterAgents = query.filters().getJSONArray("agents");
        assertTrue("agent filter list must not contain the legacy 'master' label",
                !filterAgents.contains("master"));
        JSONArray agents = query.agents(new FilterSet(30, "", "", ""));
        assertTrueEquals("built-in", agents.getJSONObject(0).getString("agent"));
    }

    @Test
    public void dashboardAssetUrlsIncludeContextPath() throws Exception {
        // Regression test: the dashboard is a raw HTML view that doesn't use Jenkins' <l:layout>,
        // so it has no bound "rootURL" jelly variable. Using it there silently rendered every
        // asset/API URL without the context path (e.g. "/plugin/..." instead of
        // "/jenkins/plugin/..."), 404ing the CSS, JS, and every dashboard API call under any
        // non-root context path.
        // JS disabled: this only inspects the server-rendered HTML, and HtmlUnit's JS engine
        // can't parse the chartjs-api-plugin's own bundled chart.umd.js anyway.
        JenkinsRule.WebClient webClient = j.createWebClient();
        webClient.setJavaScriptEnabled(false);
        String html = webClient.goTo("pipeline-metrics", "text/html")
                .getWebResponse().getContentAsString();
        assertTrue("app.js must be referenced under the context path",
                html.contains("\"/jenkins/plugin/pipeline-metrics/js/app.js\""));
        assertTrue("style.css must be referenced under the context path",
                html.contains("\"/jenkins/plugin/pipeline-metrics/css/style.css\""));
        assertTrue("data-base must include the context path",
                html.contains("data-base=\"/jenkins/pipeline-metrics/api\""));
        assertFalse("no asset/API URL should be missing the context path",
                html.contains("\"/plugin/") || html.contains("data-base=\"/pipeline-metrics/api\""));
    }

    private static void assertTrueEquals(String expected, String actual) {
        assertTrue("expected " + expected + " but was " + actual, expected.equals(actual));
    }
}
