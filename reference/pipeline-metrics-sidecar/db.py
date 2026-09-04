import sqlite3
import os
from contextlib import contextmanager

DB_PATH = os.environ.get("METRICS_DB_PATH", "/data/metrics.db")


def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn


@contextmanager
def get_db():
    conn = get_connection()
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db():
    with get_db() as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS builds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_full_name TEXT NOT NULL,
                job_folder TEXT NOT NULL,
                job_name TEXT NOT NULL,
                build_number INTEGER NOT NULL,
                result TEXT,
                duration_ms INTEGER,
                queue_time_ms INTEGER,
                timestamp_ms INTEGER NOT NULL,
                built_on TEXT DEFAULT '',
                node_labels TEXT DEFAULT '',
                triggered_by TEXT DEFAULT '',
                UNIQUE(job_full_name, build_number)
            );

            CREATE TABLE IF NOT EXISTS stages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                build_id INTEGER NOT NULL,
                stage_name TEXT NOT NULL,
                status TEXT,
                duration_ms INTEGER,
                FOREIGN KEY (build_id) REFERENCES builds(id)
            );

            CREATE TABLE IF NOT EXISTS sync_state (
                key TEXT PRIMARY KEY,
                value TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_builds_folder ON builds(job_folder);
            CREATE INDEX IF NOT EXISTS idx_builds_built_on ON builds(built_on);
            CREATE INDEX IF NOT EXISTS idx_builds_timestamp ON builds(timestamp_ms);
            CREATE INDEX IF NOT EXISTS idx_builds_result ON builds(result);
            CREATE INDEX IF NOT EXISTS idx_stages_build_id ON stages(build_id);
        """)
        _migrate(conn)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_builds_triggered_by ON builds(triggered_by)")


def _migrate(conn):
    cols = [r[1] for r in conn.execute("PRAGMA table_info(builds)").fetchall()]
    if "triggered_by" not in cols:
        conn.execute("ALTER TABLE builds ADD COLUMN triggered_by TEXT DEFAULT ''")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_builds_triggered_by ON builds(triggered_by)")
