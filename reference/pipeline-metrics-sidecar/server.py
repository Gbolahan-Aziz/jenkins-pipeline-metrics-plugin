import csv
import io
import os
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from fastapi import FastAPI, Request, Query
from fastapi.responses import HTMLResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from apscheduler.schedulers.background import BackgroundScheduler
from db import init_db, get_db
from collector import collect_all

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger(__name__)

COLLECT_INTERVAL = int(os.environ.get("COLLECT_INTERVAL_MINUTES", "5"))

scheduler = BackgroundScheduler()


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    scheduler.add_job(collect_all, "interval", minutes=COLLECT_INTERVAL, id="collector", replace_existing=True)
    scheduler.add_job(collect_all, id="initial_collect")
    scheduler.start()
    logger.info(f"Scheduler started — collecting every {COLLECT_INTERVAL} minutes")
    yield
    scheduler.shutdown()


app = FastAPI(title="Pipeline Metrics", lifespan=lifespan)
app.mount("/static", StaticFiles(directory="static"), name="static")
templates = Jinja2Templates(directory="templates")


@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    return templates.TemplateResponse("dashboard.html", {"request": request})


@app.get("/api/filters")
async def get_filters():
    with get_db() as conn:
        folders = [r["job_folder"] for r in conn.execute(
            "SELECT DISTINCT job_folder FROM builds ORDER BY job_folder"
        ).fetchall()]
        agents = [r["built_on"] for r in conn.execute(
            "SELECT DISTINCT built_on FROM builds ORDER BY built_on"
        ).fetchall()]
        jobs = [r["job_full_name"] for r in conn.execute(
            "SELECT DISTINCT job_full_name FROM builds ORDER BY job_full_name"
        ).fetchall()]
        users = [r["triggered_by"] for r in conn.execute(
            "SELECT DISTINCT triggered_by FROM builds WHERE triggered_by != '' ORDER BY triggered_by"
        ).fetchall()]
    return {"folders": folders, "agents": agents, "jobs": jobs, "users": users}


@app.get("/api/overview")
async def get_overview(
    days: int = Query(30),
    folder: str = Query(""),
    agent: str = Query(""),
    user: str = Query(""),
):
    overview_sql = """
        SELECT
            COUNT(*) as total_builds,
            SUM(CASE WHEN result = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
            SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) as failed,
            SUM(CASE WHEN result = 'UNSTABLE' THEN 1 ELSE 0 END) as unstable,
            SUM(CASE WHEN result = 'ABORTED' THEN 1 ELSE 0 END) as aborted,
            AVG(duration_ms) as avg_duration_ms,
            AVG(queue_time_ms) as avg_queue_time_ms,
            MAX(duration_ms) as max_duration_ms
        FROM builds
        {where}
    """
    with get_db() as conn:
        where, params = _build_where(days, folder, agent, user)
        row = conn.execute(overview_sql.format(where=where), params).fetchone()
        result = dict(row) if row else {}

        prev_where, prev_params = _build_where(days, folder, agent, user, offset_days=days)
        prev_row = conn.execute(overview_sql.format(where=prev_where), prev_params).fetchone()
        if prev_row:
            result["prev_total_builds"] = prev_row["total_builds"]
            result["prev_successful"] = prev_row["successful"]
            result["prev_failed"] = prev_row["failed"]
            result["prev_avg_duration_ms"] = prev_row["avg_duration_ms"]
        return result


@app.get("/api/trends")
async def get_trends(
    days: int = Query(30),
    folder: str = Query(""),
    agent: str = Query(""),
    user: str = Query(""),
    group_by: str = Query("day"),
):
    with get_db() as conn:
        where, params = _build_where(days, folder, agent, user)

        if group_by == "hour":
            date_fmt = "%Y-%m-%d %H:00"
        elif group_by == "week":
            date_fmt = "%Y-W%W"
        else:
            date_fmt = "%Y-%m-%d"

        rows = conn.execute(f"""
            SELECT
                strftime('{date_fmt}', timestamp_ms / 1000, 'unixepoch') as period,
                COUNT(*) as total,
                SUM(CASE WHEN result = 'SUCCESS' THEN 1 ELSE 0 END) as success,
                SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) as failures,
                AVG(duration_ms) as avg_duration_ms,
                AVG(queue_time_ms) as avg_queue_ms
            FROM builds
            {where}
            GROUP BY period
            ORDER BY period
        """, params).fetchall()
        return [dict(r) for r in rows]


@app.get("/api/pipelines")
async def get_pipelines(
    days: int = Query(30),
    folder: str = Query(""),
    agent: str = Query(""),
    user: str = Query(""),
    sort: str = Query("avg_duration"),
    limit: int = Query(20),
):
    with get_db() as conn:
        where, params = _build_where(days, folder, agent, user)

        valid_sorts = {
            "avg_duration": "avg_duration_ms DESC",
            "failure_rate": "failure_rate DESC",
            "total_builds": "total_builds DESC",
            "max_duration": "max_duration_ms DESC",
            "avg_queue": "avg_queue_ms DESC",
        }
        order = valid_sorts.get(sort, "avg_duration_ms DESC")

        rows = conn.execute(f"""
            SELECT
                job_full_name,
                job_folder,
                job_name,
                COUNT(*) as total_builds,
                AVG(duration_ms) as avg_duration_ms,
                MAX(duration_ms) as max_duration_ms,
                AVG(queue_time_ms) as avg_queue_ms,
                ROUND(100.0 * SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) / COUNT(*), 1) as failure_rate,
                SUM(CASE WHEN result = 'SUCCESS' THEN 1 ELSE 0 END) as success_count,
                SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) as failure_count
            FROM builds
            {where}
            GROUP BY job_full_name
            ORDER BY {order}
            LIMIT ?
        """, params + [limit]).fetchall()
        return [dict(r) for r in rows]


@app.get("/api/agents")
async def get_agents(
    days: int = Query(30),
    folder: str = Query(""),
    user: str = Query(""),
):
    with get_db() as conn:
        where, params = _build_where(days, folder, "", user)
        rows = conn.execute(f"""
            SELECT
                CASE WHEN built_on = '' THEN 'built-in' ELSE built_on END as agent,
                node_labels,
                COUNT(*) as total_builds,
                AVG(duration_ms) as avg_duration_ms,
                ROUND(100.0 * SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) / COUNT(*), 1) as failure_rate,
                SUM(duration_ms) as total_busy_ms
            FROM builds
            {where}
            GROUP BY built_on
            ORDER BY total_builds DESC
        """, params).fetchall()
        return [dict(r) for r in rows]


@app.get("/api/stages")
async def get_stages(
    job: str = Query(""),
    days: int = Query(30),
    user: str = Query(""),
):
    with get_db() as conn:
        params = []
        where_parts = [f"b.timestamp_ms >= (strftime('%s', 'now', '-{days} days') * 1000)"]
        if job:
            where_parts.append("b.job_full_name = ?")
            params.append(job)
        if user:
            where_parts.append("b.triggered_by = ?")
            params.append(user)

        where = "WHERE " + " AND ".join(where_parts)

        rows = conn.execute(f"""
            SELECT
                s.stage_name,
                COUNT(*) as run_count,
                AVG(s.duration_ms) as avg_duration_ms,
                MAX(s.duration_ms) as max_duration_ms,
                ROUND(100.0 * SUM(CASE WHEN s.status IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END) / COUNT(*), 1) as failure_rate
            FROM stages s
            JOIN builds b ON s.build_id = b.id
            {where}
            GROUP BY s.stage_name
            ORDER BY avg_duration_ms DESC
        """, params).fetchall()
        return [dict(r) for r in rows]


@app.get("/api/heatmap")
async def get_heatmap(
    days: int = Query(30),
    folder: str = Query(""),
    agent: str = Query(""),
    user: str = Query(""),
):
    with get_db() as conn:
        where, params = _build_where(days, folder, agent, user)
        rows = conn.execute(f"""
            SELECT
                CAST(strftime('%w', timestamp_ms / 1000, 'unixepoch') AS INTEGER) as day_of_week,
                CAST(strftime('%H', timestamp_ms / 1000, 'unixepoch') AS INTEGER) as hour,
                COUNT(*) as count,
                SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) as failures
            FROM builds
            {where}
            GROUP BY day_of_week, hour
            ORDER BY day_of_week, hour
        """, params).fetchall()
        return [dict(r) for r in rows]


@app.get("/api/users")
async def get_users(
    days: int = Query(30),
    folder: str = Query(""),
):
    with get_db() as conn:
        where, params = _build_where(days, folder, "", "")
        rows = conn.execute(f"""
            SELECT
                CASE WHEN triggered_by = '' THEN 'unknown' ELSE triggered_by END as user,
                COUNT(*) as total_builds,
                SUM(CASE WHEN result = 'SUCCESS' THEN 1 ELSE 0 END) as success_count,
                SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) as failure_count,
                ROUND(100.0 * SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) / COUNT(*), 1) as failure_rate,
                AVG(duration_ms) as avg_duration_ms
            FROM builds
            {where}
            GROUP BY triggered_by
            ORDER BY total_builds DESC
        """, params).fetchall()
        return [dict(r) for r in rows]


@app.get("/api/report.csv")
async def report_csv(
    days: int = Query(30),
    folder: str = Query(""),
    agent: str = Query(""),
    user: str = Query(""),
):
    with get_db() as conn:
        where, params = _build_where(days, folder, agent, user)

        overview = conn.execute(f"""
            SELECT
                COUNT(*) as total_builds,
                SUM(CASE WHEN result = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
                SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) as failed,
                SUM(CASE WHEN result = 'UNSTABLE' THEN 1 ELSE 0 END) as unstable,
                SUM(CASE WHEN result = 'ABORTED' THEN 1 ELSE 0 END) as aborted,
                AVG(duration_ms) as avg_duration_ms,
                AVG(queue_time_ms) as avg_queue_time_ms,
                MAX(duration_ms) as max_duration_ms
            FROM builds
            {where}
        """, params).fetchone()

        pipelines = conn.execute(f"""
            SELECT
                job_full_name, job_folder, job_name,
                COUNT(*) as total_builds,
                AVG(duration_ms) as avg_duration_ms,
                MAX(duration_ms) as max_duration_ms,
                AVG(queue_time_ms) as avg_queue_ms,
                ROUND(100.0 * SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) / COUNT(*), 1) as failure_rate
            FROM builds
            {where}
            GROUP BY job_full_name
            ORDER BY avg_duration_ms DESC
        """, params).fetchall()

        agent_where, agent_params = _build_where(days, folder, "", user)
        agents = conn.execute(f"""
            SELECT
                CASE WHEN built_on = '' THEN 'built-in' ELSE built_on END as agent,
                node_labels,
                COUNT(*) as total_builds,
                AVG(duration_ms) as avg_duration_ms,
                ROUND(100.0 * SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) / COUNT(*), 1) as failure_rate,
                SUM(duration_ms) as total_busy_ms
            FROM builds
            {agent_where}
            GROUP BY built_on
            ORDER BY total_builds DESC
        """, agent_params).fetchall()

        stage_parts = [f"b.timestamp_ms >= (strftime('%s', 'now', '-{days} days') * 1000)"]
        stage_params = []
        if folder:
            stage_parts.append("b.job_folder = ?")
            stage_params.append(folder)
        if user:
            stage_parts.append("b.triggered_by = ?")
            stage_params.append(user)
        stage_where = "WHERE " + " AND ".join(stage_parts)
        stages = conn.execute(f"""
            SELECT
                s.stage_name,
                COUNT(*) as run_count,
                AVG(s.duration_ms) as avg_duration_ms,
                MAX(s.duration_ms) as max_duration_ms,
                ROUND(100.0 * SUM(CASE WHEN s.status IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END) / COUNT(*), 1) as failure_rate
            FROM stages s
            JOIN builds b ON s.build_id = b.id
            {stage_where}
            GROUP BY s.stage_name
            ORDER BY avg_duration_ms DESC
        """, stage_params).fetchall()

    def secs(ms):
        return round((ms or 0) / 1000, 1)

    buf = io.StringIO()
    w = csv.writer(buf)
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    w.writerow(["Pipeline Metrics Report"])
    w.writerow(["Generated", generated])
    w.writerow(["Period", f"last {days} days"])
    w.writerow(["Environment filter", folder or "all"])
    w.writerow(["Agent filter", agent or "all"])
    w.writerow(["User filter", user or "all"])

    w.writerow([])
    w.writerow(["Overview"])
    w.writerow(["Total Builds", "Success", "Failed", "Unstable", "Aborted",
                "Avg Duration (s)", "Avg Queue (s)", "Max Duration (s)"])
    w.writerow([
        overview["total_builds"] or 0, overview["successful"] or 0, overview["failed"] or 0,
        overview["unstable"] or 0, overview["aborted"] or 0,
        secs(overview["avg_duration_ms"]), secs(overview["avg_queue_time_ms"]), secs(overview["max_duration_ms"]),
    ])

    w.writerow([])
    w.writerow(["Pipelines"])
    w.writerow(["Job", "Environment", "Builds", "Avg Duration (s)", "Max Duration (s)", "Avg Queue (s)", "Failure Rate %"])
    for p in pipelines:
        w.writerow([p["job_name"], p["job_folder"] or "root", p["total_builds"],
                    secs(p["avg_duration_ms"]), secs(p["max_duration_ms"]), secs(p["avg_queue_ms"]), p["failure_rate"]])

    w.writerow([])
    w.writerow(["Agents"])
    w.writerow(["Agent", "Labels", "Builds", "Avg Duration (s)", "Failure Rate %", "Busy Time (s)"])
    for a in agents:
        w.writerow([a["agent"], a["node_labels"] or "", a["total_builds"],
                    secs(a["avg_duration_ms"]), a["failure_rate"], secs(a["total_busy_ms"])])

    w.writerow([])
    w.writerow(["Stages"])
    w.writerow(["Stage", "Runs", "Avg Duration (s)", "Max Duration (s)", "Failure Rate %"])
    for s in stages:
        w.writerow([s["stage_name"], s["run_count"], secs(s["avg_duration_ms"]), secs(s["max_duration_ms"]), s["failure_rate"]])

    buf.seek(0)
    filename = f"pipeline-metrics-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M')}.csv"
    return StreamingResponse(
        buf, media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@app.post("/api/collect")
async def trigger_collect():
    scheduler.add_job(collect_all, id="manual_collect", replace_existing=True)
    return {"status": "collection triggered"}


def _build_where(days: int, folder: str, agent: str, user: str = "", offset_days: int = 0) -> tuple[str, list]:
    if offset_days:
        # The `days`-long window immediately before the current one — used for period-over-period deltas.
        parts = [
            f"timestamp_ms >= (strftime('%s', 'now', '-{days + offset_days} days') * 1000)",
            f"timestamp_ms <  (strftime('%s', 'now', '-{offset_days} days') * 1000)",
        ]
    else:
        parts = [f"timestamp_ms >= (strftime('%s', 'now', '-{days} days') * 1000)"]
    params = []
    if folder:
        parts.append("job_folder = ?")
        params.append(folder)
    if agent:
        if agent == "built-in":
            parts.append("(built_on = '' OR built_on = 'master' OR built_on = 'built-in')")
        else:
            parts.append("built_on = ?")
            params.append(agent)
    if user:
        parts.append("triggered_by = ?")
        params.append(user)
    return "WHERE " + " AND ".join(parts), params
