package io.jenkins.plugins.pipelinemetrics.service;

import hudson.Extension;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/** Runs a one-off historical backfill of all jobs' builds. Single-flight. */
@Extension
public class BackfillService {

    private static final Logger LOGGER = Logger.getLogger(BackfillService.class.getName());

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger jobs = new AtomicInteger();
    private final AtomicInteger builds = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile long startedAt;
    private volatile long finishedAt;

    public static BackfillService get() {
        return Jenkins.get().getExtensionList(BackfillService.class).get(0);
    }

    /** @return true if started, false if a backfill was already running. */
    public boolean start(int limitPerJob) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        jobs.set(0);
        builds.set(0);
        failed.set(0);
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        Thread t = new Thread(() -> {
            try {
                Scans.scan(limitPerJob, jobs, builds, failed);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Backfill failed", e);
            } finally {
                finishedAt = System.currentTimeMillis();
                running.set(false);
            }
        }, "pipeline-metrics-backfill");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    public JSONObject status() {
        JSONObject o = new JSONObject();
        o.put("running", running.get());
        o.put("jobs_processed", jobs.get());
        o.put("builds_processed", builds.get());
        o.put("failed", failed.get());
        o.put("started_at", startedAt);
        o.put("finished_at", finishedAt);
        return o;
    }
}
