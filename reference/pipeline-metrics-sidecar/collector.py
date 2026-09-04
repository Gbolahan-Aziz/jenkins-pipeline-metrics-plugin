import os
import logging
import httpx
from db import get_db

logger = logging.getLogger(__name__)

JENKINS_URL = os.environ.get("JENKINS_URL", "http://jenkins:8080")
JENKINS_USER = os.environ.get("JENKINS_USER", "admin")
JENKINS_TOKEN = os.environ.get("JENKINS_TOKEN", "admin")


def _auth():
    return (JENKINS_USER, JENKINS_TOKEN)


def _get(path: str, params: dict = None) -> dict | None:
    url = f"{JENKINS_URL}{path}"
    try:
        r = httpx.get(url, auth=_auth(), params=params, timeout=30)
        if r.status_code == 200:
            return r.json()
        logger.warning(f"GET {url} returned {r.status_code}")
    except Exception as e:
        logger.error(f"GET {url} failed: {e}")
    return None


def _parse_folder(full_name: str) -> tuple[str, str]:
    parts = full_name.rsplit("/", 1)
    if len(parts) == 2:
        return parts[0], parts[1]
    return "", parts[0]


def _normalize_url(url: str) -> str:
    if url.startswith("http"):
        from urllib.parse import urlparse
        parsed = urlparse(url)
        return parsed.path
    return url


def _get_all_jobs(base_path: str = "") -> list[dict]:
    jobs = []
    path = f"{base_path}/api/json" if base_path else "/api/json"
    data = _get(path, {"tree": "jobs[name,url,fullName,_class,jobs[name,url,fullName,_class]]"})
    if not data:
        return jobs

    for job in data.get("jobs", []):
        cls = job.get("_class", "")
        if "Folder" in cls or "OrganizationFolder" in cls or "WorkflowMultiBranchProject" in cls:
            sub_path = f"/job/{job['name']}" if not base_path else f"{base_path}/job/{job['name']}"
            jobs.extend(_get_all_jobs(sub_path))
        else:
            job["url"] = _normalize_url(job.get("url", ""))
            jobs.append(job)
    return jobs


def _get_node_labels(node_name: str) -> str:
    if not node_name or node_name in ("", "master", "built-in"):
        return "built-in"
    data = _get(f"/computer/{node_name}/api/json", {"tree": "assignedLabels[name]"})
    if data:
        labels = [l.get("name", "") for l in data.get("assignedLabels", [])]
        return ",".join(labels)
    return ""


def _extract_trigger_user(actions: list) -> str:
    for action in actions:
        if not action:
            continue
        cls = action.get("_class", "")
        if "UserIdCause" in cls or "hudson.model.Cause$UserIdCause" in cls:
            return action.get("userId", action.get("userName", ""))
        causes = action.get("causes", [])
        for cause in causes:
            cause_cls = cause.get("_class", "")
            if "UserIdCause" in cause_cls:
                return cause.get("userId", cause.get("userName", ""))
            if "SCMTrigger" in cause_cls or "GitHubPush" in cause_cls:
                return "scm"
            if "TimerTrigger" in cause_cls:
                return "timer"
            if "UpstreamCause" in cause_cls:
                return "upstream"
    return "unknown"


def _collect_stages(job_path: str, build_number: int) -> list[dict]:
    path = f"{job_path}{build_number}/wfapi/describe"
    try:
        r = httpx.get(f"{JENKINS_URL}{path}", auth=_auth(), timeout=30)
        if r.status_code != 200:
            return []
        data = r.json()
        stages = []
        for stage in data.get("stages", []):
            stages.append({
                "stage_name": stage.get("name", ""),
                "status": stage.get("status", ""),
                "duration_ms": stage.get("durationMillis", 0),
            })
        return stages
    except Exception:
        return []


def _get_last_synced_build(conn, job_full_name: str) -> int:
    # Only counts builds with a final result — an in-progress build (result IS NULL) stays
    # below this watermark so the next cycle re-fetches it and picks up its final outcome.
    row = conn.execute(
        "SELECT MAX(build_number) as last_build FROM builds WHERE job_full_name = ? AND result IS NOT NULL",
        (job_full_name,)
    ).fetchone()
    return row["last_build"] or 0 if row else 0


def collect_all():
    logger.info("Starting collection cycle")
    jobs = _get_all_jobs()
    logger.info(f"Found {len(jobs)} jobs")

    node_label_cache = {}

    with get_db() as conn:
        for job in jobs:
            full_name = job.get("fullName", job.get("name", ""))
            job_url = job.get("url", "")
            folder, name = _parse_folder(full_name)
            last_synced = _get_last_synced_build(conn, full_name)

            builds_data = _get(
                f"{job_url}api/json",
                {"tree": "builds[number,result,duration,timestamp,builtOn,queueId]{0,100}"}
            )
            if not builds_data:
                continue

            builds = builds_data.get("builds", [])
            new_builds = [b for b in builds if b.get("number", 0) > last_synced]

            for build in new_builds:
                build_number = build.get("number")
                built_on = build.get("builtOn", "") or ""
                result = build.get("result")
                duration_ms = build.get("duration", 0)
                timestamp_ms = build.get("timestamp", 0)

                queue_time_ms = 0
                triggered_by = "unknown"
                build_data = _get(
                    f"{job_url}{build_number}/api/json",
                    {"tree": "actions[_class,causes[_class,userId,userName],queuingDurationMillis]"}
                )
                if build_data:
                    actions = build_data.get("actions", [])
                    for action in actions:
                        if action and "queuingDurationMillis" in action:
                            queue_time_ms = action["queuingDurationMillis"]
                            break
                    triggered_by = _extract_trigger_user(actions)

                if built_on not in node_label_cache:
                    node_label_cache[built_on] = _get_node_labels(built_on)
                node_labels = node_label_cache[built_on]

                try:
                    # Upsert rather than INSERT OR IGNORE: a build already stored from a prior
                    # cycle (while still running) must be overwritten once it has a final result.
                    conn.execute("""
                        INSERT INTO builds
                        (job_full_name, job_folder, job_name, build_number, result,
                         duration_ms, queue_time_ms, timestamp_ms, built_on, node_labels, triggered_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(job_full_name, build_number) DO UPDATE SET
                            result=excluded.result,
                            duration_ms=excluded.duration_ms,
                            queue_time_ms=excluded.queue_time_ms,
                            timestamp_ms=excluded.timestamp_ms,
                            built_on=excluded.built_on,
                            node_labels=excluded.node_labels,
                            triggered_by=excluded.triggered_by
                    """, (full_name, folder, name, build_number, result,
                          duration_ms, queue_time_ms, timestamp_ms, built_on, node_labels, triggered_by))

                    build_id = conn.execute(
                        "SELECT id FROM builds WHERE job_full_name = ? AND build_number = ?",
                        (full_name, build_number)
                    ).fetchone()["id"]

                    # Re-fetch stages every time this build is (re)synced — a build seen while
                    # still running only has partial stage data, so refresh rather than append.
                    conn.execute("DELETE FROM stages WHERE build_id = ?", (build_id,))
                    stages = _collect_stages(job_url, build_number)
                    for stage in stages:
                        conn.execute("""
                            INSERT INTO stages (build_id, stage_name, status, duration_ms)
                            VALUES (?, ?, ?, ?)
                        """, (build_id, stage["stage_name"], stage["status"], stage["duration_ms"]))

                except Exception as e:
                    logger.error(f"Failed to sync build {full_name}#{build_number}: {e}")

    logger.info("Collection cycle complete")
