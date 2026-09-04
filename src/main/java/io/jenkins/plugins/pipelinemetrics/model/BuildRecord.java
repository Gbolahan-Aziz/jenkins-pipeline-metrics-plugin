package io.jenkins.plugins.pipelinemetrics.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One captured Jenkins build. {@code jobFullName} + {@code buildNumber} is the natural key.
 */
public class BuildRecord {

    private String jobFullName = "";
    private String jobFolder = "";
    private String jobName = "";
    private int buildNumber;
    private String result;
    private long durationMs;
    private long queueTimeMs;
    private long timestampMs;
    private String builtOn = "";
    private String nodeLabels = "";
    private String triggeredBy = "";
    private final List<StageRecord> stages = new ArrayList<>();

    public String getJobFullName() {
        return jobFullName;
    }

    public void setJobFullName(String jobFullName) {
        this.jobFullName = jobFullName;
    }

    public String getJobFolder() {
        return jobFolder;
    }

    public void setJobFolder(String jobFolder) {
        this.jobFolder = jobFolder;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public int getBuildNumber() {
        return buildNumber;
    }

    public void setBuildNumber(int buildNumber) {
        this.buildNumber = buildNumber;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public long getQueueTimeMs() {
        return queueTimeMs;
    }

    public void setQueueTimeMs(long queueTimeMs) {
        this.queueTimeMs = queueTimeMs;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public String getBuiltOn() {
        return builtOn;
    }

    public void setBuiltOn(String builtOn) {
        this.builtOn = builtOn;
    }

    public String getNodeLabels() {
        return nodeLabels;
    }

    public void setNodeLabels(String nodeLabels) {
        this.nodeLabels = nodeLabels;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public List<StageRecord> getStages() {
        return stages;
    }
}
