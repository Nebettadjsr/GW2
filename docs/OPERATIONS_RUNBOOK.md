# Owner activity retrieval runbook

Short operator steps for the bounded application-activity history (STORY-OPS-001/002). The history is
disposable and lossy by design: this is neither a backup nor a recovery procedure. Nothing here claims an Oracle
deployment exists or that monitoring or log forwarding is configured.

## Owner UI retrieval

1. Connect as the configured owner.
2. Open System Status → Application activity.
3. Choose Last 6 hours (minute) or Last 7 days (hour), set a bounded UTC range (minute ≤ 360 minutes, hour ≤ 720 hours, never over 30 days).
4. Download CSV or JSON.
   Both formats carry export-level facts: requested UTC range, `historyAvailable`, `retentionTruncated`, `coverage` (`UNAVAILABLE` = history unreadable, not an empty range; `NONE` = readable but no bucket in range; `PARTIAL`; `FULL`), bucket count and missing-bucket gap ranges. JSON has them as fields; CSV has them as `# key=value` lines before the header. No zero buckets are synthesized for gaps.
5. Record the application version, export UTC time and range, resolution, and any partial, gap or truncated states shown.

## Direct aggregate-file retrieval

The history is two files, `minute-history.jsonl` and `hour-history.jsonl`, in the `gw2-ops` volume mounted at
`/app/data/ops` of the Compose service `backend` (`docker/compose.yaml`, `OPS_HISTORY_DIR`). The set is bounded to 32 MiB.

1. Identify the service and volume: `docker compose -f docker/compose.yaml ps backend` and `docker volume ls --filter name=gw2-ops`.
2. Do not stop the application or database for retrieval.
3. Copy only those two files (over SSH, run on the host):
   ```
   mkdir -p ./ops-export
   docker compose -f docker/compose.yaml cp backend:/app/data/ops/minute-history.jsonl ./ops-export/
   docker compose -f docker/compose.yaml cp backend:/app/data/ops/hour-history.jsonl ./ops-export/
   ```
   From a workstation: `scp user@host:ops-export/*.jsonl .`
4. Do not print environment variables, cookies, request headers or unrelated application logs.
5. Record filenames, byte sizes and checksums: `ls -l ./ops-export` and `sha256sum ./ops-export/*.jsonl`.
6. Expect no more than the configured 32 MiB for the whole set.
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
