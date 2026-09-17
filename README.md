# Pipeline Metrics Plugin

A Jenkins plugin that records build and stage metrics as builds finish and shows them on a
dashboard inside Jenkins. There is no external service to run and nothing polls the Jenkins API.

## Features

- **Captures every build** when it completes, including builds in folders and multibranch
  projects: result, duration, queue time, agent and node labels, trigger cause, and pipeline
  stages and parallel branches.
- **Backfills existing history**, so the dashboard is not empty on a new install. When the store
  has no builds at startup, a one-time backfill runs automatically, and an administrator can run
  one at any time.
- **Dashboard** at *Pipeline Metrics* in the Jenkins sidebar, built from Jenkins' own components
  and following the light or dark theme:
  - total builds, success and failure rate, average duration and queue time, and the longest build
  - build volume and duration trend charts
  - an activity heatmap by day and hour, and the top triggerers
  - pipelines, agents and stages tables with a row filter
  - filters for top-level folder, agent, user, time range, and daily, weekly or hourly grouping
  - CSV export
- **Storage you choose**: local SQLite by default with no setup, or an external PostgreSQL or
  MySQL/MariaDB database. History can be migrated from local SQLite to the configured database.
- **Retention**: records older than the retention period are removed every 6 hours.
- **Permissions**: `PipelineMetrics/View` to see the dashboard and `PipelineMetrics/Configure`
  to run a backfill, import or migration.
- **Configuration as Code** support for every setting.
- **Network storage warning**: an administrative monitor warns when local SQLite appears to be on
  a network filesystem, where it is not safe. It never changes your configuration.

## Requirements

- Jenkins 2.555.3 or newer
- Java 21 or newer

The JDBC drivers come from the `database-sqlite`, `postgresql-api` and `mariadb-api` plugins,
which Jenkins installs as dependencies. Charts use `echarts-api` and icons use `ionicons-api`.

## Install

1. Build the plugin (see [Build](#build)) or download a released `pipeline-metrics.hpi`.
2. In Jenkins, go to **Manage Jenkins › Plugins › Advanced settings › Deploy Plugin**, upload the
   `.hpi` and restart. You can also copy the file into `$JENKINS_HOME/plugins/` before starting
   Jenkins.

After the restart, **Pipeline Metrics** appears in the sidebar for users with
`PipelineMetrics/View`. New builds are recorded as they finish, and if the store is empty the
automatic backfill fills in recent history.

## Using the dashboard

Users with `PipelineMetrics/Configure` also see these actions in the dashboard header:

| Button | What it does |
| --- | --- |
| **Sync now** | Re-records the latest build of every job. |
| **Backfill** | Records up to `backfillLimit` completed builds per job, newest first, in the background. Safe to run again: builds are updated in place, not duplicated. |
| **Import…** | Imports builds and stages from the SQLite file of the original pipeline-metrics sidecar. The path is read on the controller. |
| **Migrate storage…** | Copies all history from the local SQLite store into the currently configured external database. Safe to run again. |

**Export CSV** is available to everyone who can see the dashboard.

## Configuration

Settings are under **Manage Jenkins › System › Pipeline Metrics**, or in Configuration as Code:

```yaml
unclassified:
  pipelineMetrics:
    collectionEnabled: true   # record builds as they finish
    retentionDays: 90         # 1 to 365
    backfillLimit: 100        # builds per job for a backfill, 0 to 10000
    storageBackend:
      sqlite: {}              # the default, can be omitted
```

## Storage backends

### Local SQLite (default)

An embedded database at `$JENKINS_HOME/pipeline-metrics/metrics.db` that needs no setup. It suits
a single controller whose `$JENKINS_HOME` is on local or block storage, such as a VM disk, a
Docker volume, or a Kubernetes or ECS block volume.

Do not use it when `$JENKINS_HOME` is on network storage such as NFS, EFS or CIFS. SQLite's
write-ahead log relies on file locking that these filesystems do not implement reliably, which can
cause stalled writes or a corrupted database. It is also unsafe if more than one controller writes
to the same file. Use an external database in either case.

### External PostgreSQL

```yaml
unclassified:
  pipelineMetrics:
    storageBackend:
      postgresql:
        host: "pg.example.internal"
        port: 5432
        database: "jenkins_metrics"
        credentialsId: "pipeline-metrics-db"
        useSsl: true      # default true
        maxPoolSize: 10   # 1 to 20, default 5
```

### External MySQL or MariaDB

Uses the MariaDB driver, which works with both MySQL and MariaDB servers.

```yaml
unclassified:
  pipelineMetrics:
    storageBackend:
      mysql:
        host: "mysql.example.internal"
        port: 3306
        database: "jenkins_metrics"
        credentialsId: "pipeline-metrics-db"
        useSsl: true
        maxPoolSize: 10
```

`credentialsId` must point to a Jenkins *Username with password* credential. The plugin stores
only the credential ID, never the username or password. An external database can be shared by
more than one controller.

### Switching backends

Changing `storageBackend` only affects new writes. To bring existing history across, use
**Migrate storage…** on the dashboard, or `POST pipeline-metrics/api/migrateStorage`.

## HTTP API

All endpoints are under `pipeline-metrics/api/`. Read endpoints need `PipelineMetrics/View`;
the POST endpoints need `PipelineMetrics/Configure` and a CSRF crumb.

| Method | Path | Returns |
| --- | --- | --- |
| GET | `filters`, `overview`, `trends`, `pipelines`, `agents`, `stages`, `heatmap`, `users` | Dashboard data as JSON |
| GET | `report.csv` | CSV export |
| GET | `backfillStatus` | Progress of the current or last backfill |
| POST | `collect` | Runs **Sync now** |
| POST | `backfill` | Starts a backfill |
| POST | `import?path=...` | Imports a sidecar SQLite file |
| POST | `migrateStorage` | Copies local SQLite history into the configured backend |

## Build

Requires JDK 21 and Maven 3.9 or newer.

```bash
mvn -B verify
```

Compiles, runs the tests and SpotBugs, and writes `target/pipeline-metrics.hpi`.

```bash
mvn -B verify -Pdb-it
```

Also runs the PostgreSQL and MariaDB tests with Testcontainers. Needs a running Docker daemon.

```bash
mvn -B hpi:run
```

Starts a local Jenkins with the plugin at http://localhost:8080/jenkins/.

## Repository layout

- `src/` holds the plugin.
- `reference/pipeline-metrics-sidecar/` is the original Python sidecar (FastAPI and SQLite) this
  plugin replaces. It is kept as a reference for the analytics and as the source format for
  **Import…**.

## License

MIT, see [LICENSE](LICENSE).
