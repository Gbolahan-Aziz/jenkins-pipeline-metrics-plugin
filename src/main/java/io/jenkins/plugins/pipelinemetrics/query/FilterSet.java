package io.jenkins.plugins.pipelinemetrics.query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Query filter dimensions shared by every analytics endpoint. */
public class FilterSet {

    public final int days;
    public final String folder;
    public final String agent;
    public final String user;
    private final LongSupplier clock;

    public FilterSet(int days, String folder, String agent, String user) {
        this(days, folder, agent, user, System::currentTimeMillis);
    }

    /** Test-only constructor allowing a deterministic clock for "now". */
    FilterSet(int days, String folder, String agent, String user, LongSupplier clock) {
        this.days = days;
        this.folder = folder == null ? "" : folder;
        this.agent = agent == null ? "" : agent;
        this.user = user == null ? "" : user;
        this.clock = clock;
    }

    /** Build a WHERE clause over the {@code builds} table. */
    public Where where() {
        return where(0);
    }

    /** Build a WHERE clause offset by a preceding window of {@code offsetDays} for deltas. */
    public Where where(int offsetDays) {
        List<String> parts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        long now = clock.getAsLong();
        if (offsetDays > 0) {
            parts.add("timestamp_ms >= ?");
            params.add(now - Duration.ofDays((long) days + offsetDays).toMillis());
            parts.add("timestamp_ms < ?");
            params.add(now - Duration.ofDays(offsetDays).toMillis());
        } else {
            parts.add("timestamp_ms >= ?");
            params.add(now - Duration.ofDays(days).toMillis());
        }
        if (!folder.isEmpty()) {
            parts.add("job_folder = ?");
            params.add(folder);
        }
        if (!agent.isEmpty()) {
            if ("built-in".equals(agent) || "master".equals(agent)) {
                parts.add("(built_on = '' OR built_on = 'master' OR built_on = 'built-in')");
            } else {
                parts.add("built_on = ?");
                params.add(agent);
            }
        }
        if (!user.isEmpty()) {
            parts.add("triggered_by = ?");
            params.add(user);
        }
        return new Where("WHERE " + String.join(" AND ", parts), params);
    }

    /** A rendered WHERE clause plus its ordered bind parameters. */
    public static class Where {
        public final String sql;
        public final List<Object> params;

        public Where(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }
}
