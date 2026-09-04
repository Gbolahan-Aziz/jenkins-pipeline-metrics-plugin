package io.jenkins.plugins.pipelinemetrics.service;

import hudson.Extension;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/** On-demand ("manual") collection: refreshes the most recent build of every job. */
@Extension
public class MetricsService {

    private final AtomicBoolean running = new AtomicBoolean(false);

    public static MetricsService get() {
        return Jenkins.get().getExtensionList(MetricsService.class).get(0);
    }

    /**
     * Synchronously re-scan each job's latest build. Returns a result JSON with a status of
     * {@code ok}, {@code busy}, or {@code error} and the number of builds collected.
     */
    public JSONObject runManualCollection() {
        JSONObject out = new JSONObject();
        if (!running.compareAndSet(false, true)) {
            out.put("status", "busy");
            out.put("message", "A collection is already running");
            return out;
        }
        AtomicInteger jobs = new AtomicInteger();
        AtomicInteger builds = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            Scans.scan(1, jobs, builds, failed);
            out.put("status", "ok");
            out.put("collected", builds.get());
            out.put("jobs", jobs.get());
            out.put("failed", failed.get());
        } catch (RuntimeException e) {
            out.put("status", "error");
            out.put("message", String.valueOf(e.getMessage()));
        } finally {
            running.set(false);
        }
        return out;
    }
}
