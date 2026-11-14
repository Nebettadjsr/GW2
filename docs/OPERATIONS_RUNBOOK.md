# Owner activity retrieval runbook

Short operator steps for the bounded application-activity history (STORY-OPS-001/002). The history is
disposable and lossy by design: this is neither a backup nor a recovery procedure. Nothing here claims an Oracle
deployment exists or that monitoring or log forwarding is configured.

## Owner UI retrieval

1. Connect as the configured owner.
2. Open System Status → Application activity.
3. In "Download activity file", pick one UTC day or week from the grouped dropdown (newest first; the latest nonempty day is preselected) and press Download. The browser sends only the server's opaque selection id and saves the server-named single CSV attachment. There are no range or format controls.
4. Entries show their UTC bounds, open/closed, and partial/truncated/pruned limits. The current (open) period and partial periods are downloadable. An expired selection answers 410; refresh the list and choose again, nothing is substituted.
5. Weeks are streamed by the server from the retained daily files. A missing minute means no stored included application request activity, not zero users, zero latency or an outage.
6. Record the application version, download UTC time, the selected period and any partial, truncated or pruned label.

## What is recorded and when

- Included application requests update fixed counters in memory. Each active UTC minute retains the
  five-minute distinct-account estimate, request and error counts, backend elapsed time and latency bins,
  grouped by route category. No API key, account identity or individual click trail is written to these files.
- Closed minutes are flushed once per minute (initial delay 61 seconds); a manifest/download read also
  flushes closed minutes. A minute with no included request creates no row, file or index write. System
  Status reads, owner/admin requests, health checks, assets and task polling do not create activity rows.
- The owner view loads on opening and explicit Refresh only. Database size is queried on demand and cached
  for five minutes. CPU and memory belong to Oracle Monitoring, not these application files.
- Compare active-account estimates, request counts, latency and errors from the same minute. This can
  reveal slow periods at 3, 5 or 10 active accounts; it does not itself measure simultaneous page loads
  or establish user capacity. Missing minutes are no stored activity, not proof of an outage or idle uptime.

Approximate CSV sizes below are arithmetic estimates, not load-test results. Assuming 1–3 active route
groups per minute and roughly 150–200 bytes per row, plus one small CSV header:

| Included activity | Approximate file size |
| --- | --- |
| One active hour in a day | 10–35 KiB |
| A continuously active day | 0.2–0.8 MiB |
| A continuously active week | 1.5–6 MiB |

Idle hours add nothing. User count changes counters, not the number of per-user rows. The actual size
depends on active route groups and numeric widths; current storage limits and coverage flags are defined
in `CURRENT_ARCHITECTURE.md`. A weekly download streams retained days without storing a second copy.

## Direct aggregate-file retrieval

The history is sparse per-UTC-day files `activity-YYYY-MM-DD.csv` in the `gw2-ops` volume mounted at
`/app/data/ops` of the Compose service `backend` (`docker/compose.yaml`, `OPS_HISTORY_DIR`). The set is bounded.

1. Identify the service and volume: `docker compose -f docker/compose.yaml ps backend` and `docker volume ls --filter name=gw2-ops`.
2. Do not stop the application or database for retrieval.
3. Copy only the needed day files (over SSH, run on the host):
   ```
   mkdir -p ./ops-export
   docker compose -f docker/compose.yaml cp backend:/app/data/ops/activity-2026-11-14.csv ./ops-export/
   ```
   From a workstation: `scp user@host:ops-export/*.csv .`
4. Do not print environment variables, cookies, request headers or unrelated application logs.
5. Record filenames, byte sizes and checksums: `ls -l ./ops-export` and `sha256sum ./ops-export/*.csv`.
6. Expect the retained set to stay within its configured bound.
7. A missing, truncated or corrupt file means that history is unavailable or lost by design; do not repair or recover it.

## Oracle metrics

1. Open the configured Oracle Console link ("Open Oracle host metrics"), if the owner view shows one. It comes from the optional `GW2_OPS_ORACLE_CONSOLE_URL` (non-secret HTTPS link only).
2. Verify Oracle's own monitoring and agent/plugin configuration separately; Oracle's CPU and memory metrics require it. References: <https://docs.oracle.com/en-us/iaas/Content/Compute/References/computemetrics.htm> and <https://docs.oracle.com/en-us/iaas/Content/Compute/Tasks/enablingmonitoring.htm>.
3. Compare UTC ranges with the application aggregates.
4. Treat missing provider data as unavailable, not zero.
5. Do not replace missing Oracle data with an application host sampler; the application has none and calls no Oracle API.

## Optional custom-log forwarding

Not configured automatically and not required for application operation. It is not an application backup or
recovery mechanism. Provider retention and quota settings belong to deployment operations. No provider
credential is added to the browser or application for this feature.
