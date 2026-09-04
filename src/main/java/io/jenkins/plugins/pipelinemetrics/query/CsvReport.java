package io.jenkins.plugins.pipelinemetrics.query;

import java.io.StringWriter;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** Builds the CSV report from the same query methods used by the dashboard. */
public class CsvReport {

    private final MetricsQueryService query;

    public CsvReport(MetricsQueryService query) {
        this.query = query;
    }

    public String generate(FilterSet f) throws SQLException {
        JSONObject overview = query.overview(f);
        JSONArray pipelines = query.pipelines(f, "avg_duration", 1000);
        JSONArray agents = query.agents(f);
        JSONArray stages = query.stages("", f.days, f.folder, f.agent, f.user);

        StringWriter sw = new StringWriter();
        String generated = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));

        line(sw, "Pipeline Metrics Report");
        line(sw, "Generated", generated);
        line(sw, "Period", "last " + f.days + " days");
        line(sw, "Environment filter", f.folder.isEmpty() ? "all" : f.folder);
        line(sw, "Agent filter", f.agent.isEmpty() ? "all" : f.agent);
        line(sw, "User filter", f.user.isEmpty() ? "all" : f.user);
        line(sw, "");

        line(sw, "Overview");
        line(sw, "Total Builds", "Success", "Failed", "Unstable", "Aborted",
                "Avg Duration (s)", "Avg Queue (s)", "Max Duration (s)");
        line(sw,
                str(overview.optInt("total_builds", 0)),
                str(overview.optInt("successful", 0)),
                str(overview.optInt("failed", 0)),
                str(overview.optInt("unstable", 0)),
                str(overview.optInt("aborted", 0)),
                secs(overview.optDouble("avg_duration_ms", 0)),
                secs(overview.optDouble("avg_queue_time_ms", 0)),
                secs(overview.optDouble("max_duration_ms", 0)));
        line(sw, "");

        line(sw, "Pipelines");
        line(sw, "Job", "Environment", "Builds", "Avg Duration (s)", "Max Duration (s)",
                "Avg Queue (s)", "Failure Rate %");
        for (Object o : pipelines) {
            JSONObject p = (JSONObject) o;
            line(sw, p.optString("job_name"),
                    p.optString("job_folder", "").isEmpty() ? "root" : p.optString("job_folder"),
                    str(p.optInt("total_builds", 0)),
                    secs(p.optDouble("avg_duration_ms", 0)),
                    secs(p.optDouble("max_duration_ms", 0)),
                    secs(p.optDouble("avg_queue_ms", 0)),
                    str(p.optDouble("failure_rate", 0)));
        }
        line(sw, "");

        line(sw, "Agents");
        line(sw, "Agent", "Labels", "Builds", "Avg Duration (s)", "Failure Rate %", "Busy Time (s)");
        for (Object o : agents) {
            JSONObject a = (JSONObject) o;
            line(sw, a.optString("agent"), a.optString("node_labels", ""),
                    str(a.optInt("total_builds", 0)), secs(a.optDouble("avg_duration_ms", 0)),
                    str(a.optDouble("failure_rate", 0)), secs(a.optDouble("total_busy_ms", 0)));
        }
        line(sw, "");

        line(sw, "Stages");
        line(sw, "Stage", "Runs", "Avg Duration (s)", "Max Duration (s)", "Failure Rate");
        for (Object o : stages) {
            JSONObject s = (JSONObject) o;
            line(sw, s.optString("stage_name"), str(s.optInt("run_count", 0)),
                    secs(s.optDouble("avg_duration_ms", 0)), secs(s.optDouble("max_duration_ms", 0)),
                    str(s.optDouble("failure_rate", 0)));
        }
        return sw.toString();
    }

    private static void line(StringWriter sw, String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i]));
        }
        sw.write(sb.append('\n').toString());
    }

    private static String escape(String v) {
        if (v == null) {
            return "";
        }
        String safe = v;
        if (!safe.isEmpty() && isFormulaTrigger(safe.charAt(0))) {
            // Mitigates CSV/formula injection (OWASP): a leading '=', '+', '-', '@', tab, or CR
            // is interpreted as a formula by Excel/Sheets/LibreOffice when the file is opened.
            // Job names, agent names, node labels, and usernames are all attacker-influenceable
            // (any user who can create a job, configure an agent, or trigger a build), so force
            // literal-text interpretation with a leading apostrophe.
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    private static boolean isFormulaTrigger(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    private static String str(int v) {
        return Integer.toString(v);
    }

    private static String str(double v) {
        return Double.toString(Math.round(v * 10.0) / 10.0);
    }

    private static String secs(double ms) {
        return Double.toString(Math.round(ms / 100.0) / 10.0);
    }
}
