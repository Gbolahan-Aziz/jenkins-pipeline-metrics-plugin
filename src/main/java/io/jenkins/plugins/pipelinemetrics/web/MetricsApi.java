package io.jenkins.plugins.pipelinemetrics.web;

import com.zaxxer.hikari.HikariDataSource;
import io.jenkins.plugins.pipelinemetrics.PipelineMetricsPermissions;
import io.jenkins.plugins.pipelinemetrics.query.CsvReport;
import io.jenkins.plugins.pipelinemetrics.query.FilterSet;
import io.jenkins.plugins.pipelinemetrics.query.MetricsQueryService;
import io.jenkins.plugins.pipelinemetrics.service.BackfillService;
import io.jenkins.plugins.pipelinemetrics.service.MetricsService;
import io.jenkins.plugins.pipelinemetrics.service.SidecarImporter;
import io.jenkins.plugins.pipelinemetrics.service.StorageMigrator;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import io.jenkins.plugins.pipelinemetrics.store.backend.SQLiteStorageBackend;
import java.io.IOException;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import jenkins.model.Jenkins;
import net.sf.json.JSON;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

/** Stapler-routed JSON/CSV endpoints under {@code /pipeline-metrics/api/}. */
public class MetricsApi {

    private final MetricsQueryService query = new MetricsQueryService();

    @GET
    public void doFilters(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        run(rsp, () -> query.filters());
    }

    @GET
    public void doOverview(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        FilterSet f = parse(req, rsp);
        if (f == null) {
            return;
        }
        run(rsp, () -> query.overview(f));
    }

    @GET
    public void doTrends(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        FilterSet f = parse(req, rsp);
        if (f == null) {
            return;
        }
        String groupBy = param(req, "group_by", "day");
        try {
            writeJson(rsp, query.trends(f, groupBy));
        } catch (IllegalArgumentException e) {
            writeError(rsp, 400, e.getMessage());
        } catch (SQLException e) {
            writeError(rsp, 500, e.getMessage());
        }
    }

    @GET
    public void doPipelines(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        FilterSet f = parse(req, rsp);
        if (f == null) {
            return;
        }
        String sort = param(req, "sort", "avg_duration");
        int limit = intParam(req, "limit", 20);
        run(rsp, () -> query.pipelines(f, sort, limit));
    }

    @GET
    public void doAgents(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        FilterSet f = parse(req, rsp);
        if (f == null) {
            return;
        }
        run(rsp, () -> query.agents(f));
    }

    @GET
    public void doStages(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        int days = intParam(req, "days", 30);
        run(rsp, () -> query.stages(param(req, "job", ""), days,
                param(req, "folder", ""), param(req, "agent", ""), param(req, "user", "")));
    }

    @GET
    public void doHeatmap(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        FilterSet f = parse(req, rsp);
        if (f == null) {
            return;
        }
        run(rsp, () -> query.heatmap(f));
    }

    @GET
    public void doUsers(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        FilterSet f = parse(req, rsp);
        if (f == null) {
            return;
        }
        run(rsp, () -> query.users(f));
    }

    @WebMethod(name = "report.csv")
    @GET
    public void doReportCsv(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        FilterSet f = parse(req, rsp);
        if (f == null) {
            return;
        }
        try {
            String csv = new CsvReport(query).generate(f);
            String filename = "pipeline-metrics-"
                    + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                    + ".csv";
            rsp.setContentType("text/csv;charset=UTF-8");
            rsp.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            rsp.getWriter().write(csv);
        } catch (SQLException e) {
            writeError(rsp, 500, e.getMessage());
        }
    }

    @POST
    public void doCollect(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins.get().checkPermission(PipelineMetricsPermissions.CONFIGURE);
        writeJson(rsp, MetricsService.get().runManualCollection());
    }

    @POST
    public void doBackfill(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins.get().checkPermission(PipelineMetricsPermissions.CONFIGURE);
        int limit = io.jenkins.plugins.pipelinemetrics.config.PipelineMetricsGlobalConfig.get().getBackfillLimit();
        boolean started = BackfillService.get().start(limit);
        JSONObject out = new JSONObject();
        out.put("status", started ? "started" : "already_running");
        writeJson(rsp, out);
    }

    @GET
    public void doBackfillStatus(StaplerRequest req, StaplerResponse rsp) throws IOException {
        checkView();
        writeJson(rsp, BackfillService.get().status());
    }

    @POST
    public void doImport(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins.get().checkPermission(PipelineMetricsPermissions.CONFIGURE);
        String path = param(req, "path", "");
        if (path.isEmpty()) {
            writeError(rsp, 400, "Missing 'path' parameter");
            return;
        }
        writeJson(rsp, SidecarImporter.get().importFrom(path));
    }

    /**
     * Copies history from the default local SQLite store (the plugin's original, fixed-path
     * backend, regardless of what is currently configured) into whichever backend is active
     * now. Covers the common "I was on local SQLite, now I've configured an external database,
     * bring my history along" case — an admin migrating between two external databases can
     * still use {@link #doImport} against an exported/copied file, or run the equivalent SQL
     * directly against their database, so this endpoint intentionally does not try to accept an
     * arbitrary source backend description.
     */
    @POST
    public void doMigrateStorage(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins.get().checkPermission(PipelineMetricsPermissions.CONFIGURE);
        SQLiteStorageBackend sqliteBackend = new SQLiteStorageBackend();
        try {
            HikariDataSource sqliteDs = sqliteBackend.createDataSource();
            MetricsStore source = new MetricsStore(sqliteDs, sqliteBackend.dialect());
            try {
                source.init();
                if (!source.isAvailable()) {
                    writeError(rsp, 500, "Could not open the local SQLite store: " + source.getUnavailableReason());
                    return;
                }
                writeJson(rsp, StorageMigrator.migrate(source, MetricsStore.get()));
            } finally {
                source.close();
            }
        } catch (SQLException e) {
            writeError(rsp, 500, "Could not open the local SQLite store: " + e.getMessage());
        }
    }

    // -- helpers --

    private interface JsonSupplier {
        JSON get() throws SQLException;
    }

    private static void run(StaplerResponse rsp, JsonSupplier supplier) throws IOException {
        try {
            writeJson(rsp, supplier.get());
        } catch (SQLException e) {
            writeError(rsp, 500, e.getMessage());
        }
    }

    private static void checkView() {
        Jenkins.get().checkPermission(PipelineMetricsPermissions.VIEW);
    }

    private static FilterSet parse(StaplerRequest req, StaplerResponse rsp) throws IOException {
        int days = intParam(req, "days", 30);
        if (days < 1 || days > 365) {
            writeError(rsp, 400, "days must be between 1 and 365");
            return null;
        }
        return new FilterSet(days, param(req, "folder", ""), param(req, "agent", ""), param(req, "user", ""));
    }

    private static String param(StaplerRequest req, String name, String def) {
        String v = req.getParameter(name);
        return v == null ? def : v;
    }

    private static int intParam(StaplerRequest req, String name, int def) {
        String v = req.getParameter(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void writeJson(StaplerResponse rsp, JSON json) throws IOException {
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(json.toString());
    }

    private static void writeError(StaplerResponse rsp, int code, String message) throws IOException {
        rsp.setStatus(code);
        JSONObject o = new JSONObject();
        o.put("error", message == null ? "error" : message);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(o.toString());
    }
}
