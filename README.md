# Jenkins Pipeline Metrics Plugin

A native Jenkins plugin that collects build and stage metrics in-process and serves
an analytics dashboard inside the Jenkins UI — no external sidecar, no polling.

## Status

Early stage. The plugin is being specced and built from an existing proof-of-concept
"pipeline-metrics" sidecar (a FastAPI + SQLite + Jinja2 service that polled the Jenkins
REST API). This repo is the owned, standalone home for the plugin.

## What it does

- Captures every build as it finishes (via `RunListener`), including folders, multibranch
  projects, pipeline stages, agent, node labels, queue time, and trigger origin.
- Persists metrics in a pluggable storage backend under your control (see
  [Storage backends](#storage-backends) below) — local SQLite by default, or an external
  PostgreSQL/MySQL/MariaDB database for network-backed `$JENKINS_HOME` or multi-controller setups.
- Serves a dashboard as a Jenkins global root action with: overview, trends, pipelines,
  agents, stages, heatmap, and users views — plus CSV export.
- Gated by dedicated `PipelineMetrics/View` and `PipelineMetrics/Configure` permissions.
- Configurable via JCasC and the management UI.
- Migrates data between storage backends (local SQLite ↔ external database) and backfills
  history directly from Jenkins.
- Warns (never auto-switches) when local SQLite looks like it's running on network-backed
  storage, where its write-ahead log is not reliably safe.

## Repository layout

- `reference/pipeline-metrics-sidecar/` — the original Python sidecar, kept as the parity
  reference for the plugin's behaviour and analytics.

## License

MIT — see [LICENSE](LICENSE).

## Build

Requires JDK 17+ and Maven 3.8+.

```bash
mvn -B verify              # compiles, runs tests + SpotBugs, produces target/pipeline-metrics.hpi
mvn -B verify -Pdb-it      # also runs the Postgres/MariaDB dialect-parity suite via Testcontainers
                            # (requires a local Docker daemon; not part of the default build)
mvn -B hpi:run             # runs a local Jenkins with the plugin at http://localhost:8080/jenkins
```

The packaged plugin is `target/pipeline-metrics.hpi`.

## Install

Either:

1. **Plugin Manager** — Manage Jenkins → Plugins → Advanced → Deploy Plugin → upload `pipeline-metrics.hpi`, then restart.
2. **Baked into the image** — copy the `.hpi` into `$JENKINS_HOME/plugins/` (or add it to your image's plugin list) and restart.

The dashboard appears in the left sidebar as **Pipeline Metrics** for users holding the `PipelineMetrics/View` permission. Metrics are captured automatically as builds finish; existing history can be backfilled directly from Jenkins via the API.

## Configuration (JCasC)

```yaml
unclassified:
  pipelineMetrics:
    collectionEnabled: true
    retentionDays: 90
    backfillLimit: 100
    # storageBackend omitted -> local SQLite, the zero-action default (see below)
```

## Storage backends

Pipeline Metrics stores its data through a pluggable backend, selected under **Manage Jenkins ›
System › Pipeline Metrics › Storage backend** (or via JCasC). Every install defaults to local
SQLite with no configuration required — this only needs to change if your deployment topology
doesn't fit that model.

### Local SQLite (default)

An embedded, zero-dependency database at `$JENKINS_HOME/pipeline-metrics/metrics.db`. Works well
for a single controller on local or block-storage-backed `$JENKINS_HOME` (a VM, a Docker
container with a persistent volume, or a Kubernetes/ECS pod on a block-storage PVC/EBS volume).

**Not safe** when `$JENKINS_HOME` sits on network storage (NFS, AWS EFS, CIFS, ...) — a common
choice in Kubernetes/ECS specifically because it lets a pod/task reschedule without waiting on a
block volume to detach and reattach. SQLite's write-ahead log depends on file-locking behavior
many network filesystems don't implement reliably, which can mean write stalls or data corruption
rather than just slower performance. It's also not safe if more than one Jenkins controller
process ever writes to the same data. The plugin detects a likely network filesystem under
`$JENKINS_HOME/pipeline-metrics` and raises an administrative-monitor warning in that case — it
never switches your configuration automatically.

```yaml
unclassified:
  pipelineMetrics:
    storageBackend:
      sqlite: {}
```

### External PostgreSQL

```yaml
unclassified:
  pipelineMetrics:
    storageBackend:
      postgresql:
        host: "pg.internal"
        port: 5432
        database: "jenkins_metrics"
        credentialsId: "pipeline-metrics-db"   # a Jenkins "Username with password" credential
        useSsl: true
        maxPoolSize: 10
```

### External MySQL/MariaDB

Connects with the MariaDB driver (LGPL-2.1), which speaks the MySQL wire protocol against either
a real MySQL server or MariaDB — deliberately not the GPL-licensed MySQL Connector/J, to avoid
bundling a GPL jar inside this plugin's `.hpi`.

```yaml
unclassified:
  pipelineMetrics:
    storageBackend:
      mysql:
        host: "mysql.internal"
        port: 3306
        database: "jenkins_metrics"
        credentialsId: "pipeline-metrics-db"
        useSsl: true
        maxPoolSize: 10
```

Either external backend requires a Jenkins "Username with password" credential (`credentialsId`
above) — the plugin never stores a database username/password directly, only that credential ID.
An external database also removes the multi-controller restriction: it safely arbitrates
concurrent writers, so more than one controller process can point at the same database.

### Switching backends on an existing install

Changing `storageBackend` only redirects *new* writes — it does not copy existing history.
To bring history along: **Manage Jenkins › Pipeline Metrics › Migrate storage…** (or
`POST pipeline-metrics/api/migrateStorage`, `PipelineMetrics/Configure` permission required)
copies everything from the default local SQLite store into whichever backend is currently
configured. It's safe to run more than once (upserts by job + build number).

## Data

All state lives under `$JENKINS_HOME/pipeline-metrics/` for the local SQLite backend, or in
whichever external database you've configured. See [Storage backends](#storage-backends).
