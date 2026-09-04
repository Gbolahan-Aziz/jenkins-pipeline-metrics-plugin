package io.jenkins.plugins.pipelinemetrics.model;

/** One pipeline stage of a build. */
public class StageRecord {

    private String stageName = "";
    private String status;
    private long durationMs;
    private int seq;

    public StageRecord() {
    }

    public StageRecord(String stageName, String status, long durationMs, int seq) {
        this.stageName = stageName;
        this.status = status;
        this.durationMs = durationMs;
        this.seq = seq;
    }

    public String getStageName() {
        return stageName;
    }

    public void setStageName(String stageName) {
        this.stageName = stageName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }
}
