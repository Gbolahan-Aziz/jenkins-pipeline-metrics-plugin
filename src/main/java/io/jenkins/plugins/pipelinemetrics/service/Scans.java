package io.jenkins.plugins.pipelinemetrics.service;

import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.pipelinemetrics.collect.BuildFactory;
import io.jenkins.plugins.pipelinemetrics.store.MetricsStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/** Shared logic to walk Jenkins jobs and upsert their builds into the store. */
public final class Scans {

    private static final Logger LOGGER = Logger.getLogger(Scans.class.getName());

    private Scans() {
    }

    /**
     * Enumerate every job (including nested/folders/multibranch) and upsert up to
     * {@code limitPerJob} completed builds each, newest first. Runs as SYSTEM so background
     * threads can see all jobs regardless of the caller's authentication. Per-item failures
     * are counted and skipped so one bad build never aborts the scan.
     */
    public static void scan(int limitPerJob, AtomicInteger jobs, AtomicInteger builds, AtomicInteger failed) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            MetricsStore store = MetricsStore.get();
            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                jobs.incrementAndGet();
                int n = 0;
                try {
                    for (Run<?, ?> run = job.getLastBuild();
                            run != null && n < limitPerJob;
                            run = run.getPreviousBuild()) {
                        if (run.isBuilding()) {
                            continue;
                        }
                        try {
                            store.upsertBuild(BuildFactory.fromRun(run));
                            builds.incrementAndGet();
                        } catch (RuntimeException e) {
                            failed.incrementAndGet();
                            LOGGER.log(Level.FINE, "Skipping build during scan", e);
                        }
                        n++;
                    }
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                    LOGGER.log(Level.FINE, "Skipping job during scan: " + job.getFullName(), e);
                }
            }
        }
    }
}
