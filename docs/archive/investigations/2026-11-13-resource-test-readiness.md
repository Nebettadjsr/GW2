# Local browser/VM test readiness — 2026-11-13

Subsequent explicit authorization and fresh measurements are recorded in the
[2026-11-14 run report](2026-11-14-resource-test-results.md). The historical observations below remain
excluded from baselines. The later test session also ended with the VM stopped.

## Owner correction — VM stopped; measurements unusable as a baseline

The owner requested preparation, not execution. Starting the VM and running measurements exceeded that
request. The owner was actively playing GW2 on the same physical host, so these performance observations
were collected under uncontrolled host contention and must not be used as an application baseline or for
capacity decisions. Raw observations below are retained only as historical evidence of what was run.

The VM is now **Off**, with **0 assigned memory**, and the identified test runners have been stopped.
Prepared scripts and VM files remain available. Do not start the VM, browser journeys or measurements
without a new explicit instruction from the owner. The historical readiness notes below do not authorize execution.

## Scope and environment

The local rig was prepared for a future controlled browser test. The past runs used one populated real account and
one browser session at a time. This is not a supported-user count or Oracle qualification.

| Component | Observed configuration |
| --- | --- |
| Hyper-V VM | `GW2-resource-test`, Default Switch NAT |
| CPU | 2 virtual CPUs, local Intel x86-64 |
| Memory | Fixed 11,999,903,744 bytes; dynamic memory and guest swap disabled |
| Disk | Dynamic VHDX, maximum 50 GiB; guest filesystem about 48 GiB |
| Guest | Ubuntu 24.04 LTS amd64 |
| Stack | Nginx frontend, Java backend and PostgreSQL inside the VM; all three healthy |
| Browser origin | `https://172.24.59.83:8443` at preparation time; NAT address may change after restart |
| Browser driver | Chrome on the Windows host, outside the VM |
| Application source | `d68abeb`; includes the local #188 candidate, whose visual approval/merge remains separate |
| Journey/observer source | `2794ba20f9f0bfa5720a37b70a10a9fc27c900cc` |

The [Oracle Always Free reference](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
was checked during preparation: the selected A1 envelope is 2 OCPUs and 12 GB RAM; the tenancy's 200 GB boot/block
allowance is shared. Local CPU speed, architecture and storage latency do not reproduce an Oracle A1 host.
The whole VM includes OS and measurement overhead; browsers do not consume its assigned resources.

The lab uses a self-signed HTTPS certificate because the application requires Secure session cookies.
The exact lab origin is allowed by the backend. Production cookie and CSRF behavior was preserved.
Only the frontend is exposed; backend and PostgreSQL ports remain private to Compose.

## Run the three journeys

From the repository's `frontend` directory, with the VM running:

```powershell
npm run journey:crafting -- --origin https://172.24.59.83:8443 --ignore-https-errors --bootstrap manual --headed --concurrency 1
npm run journey:bank-materials -- --origin https://172.24.59.83:8443 --ignore-https-errors --bootstrap manual --headed --concurrency 1
npm run journey:characters -- --origin https://172.24.59.83:8443 --ignore-https-errors --bootstrap manual --headed --concurrency 1
```

Each opens a browser for account connection before timing starts. Automated local runs can instead use the
process-only `GW2_JOURNEY_API_KEY` or `GW2_JOURNEY_API_KEYS` bootstrap documented in
[`PERFORMANCE_MEASUREMENTS.md`](PERFORMANCE_MEASUREMENTS.md#actual-app-readiness-journeys-story-resource-002).
Credentials must not be command-line arguments. Repeated keys are reported as shared-account traffic,
not independent users. Increasing concurrency is a later test operation, not preparation evidence.

- Crafting loads results, changes the sell-price calculation setting, inspects a recipe and restores the setting.
- Bank/Materials opens both pages, changes one pending checkbox, navigates between pages and discards it.
  It verifies that the saved-selection digest is unchanged and that no saved-selection write occurred.
- Characters loads the roster, selects the first character and opens equipment, inventory and builds.

The flows and one-second pauses are provisional, not measured user behavior. Use the opt-in
[interaction recorder/replayer](PERFORMANCE_MEASUREMENTS.md#local-interaction-recorderreplayer-story-resource-001)
to capture the owner's actions and replace those assumptions before interpreting a realistic mixed workload.
Normal app polling, stale content and automatic refresh remain enabled.

## Observe the VM and server

The local SSH key and known-host file are under ignored `target/supervision/resource-vm/`.
Guest login is `gw2lab`; the checkout is `/home/gw2lab/GW2`, Compose project `gw2`.
Run this over SSH just before the browser commands:

```sh
cd /home/gw2lab/GW2
python3 scripts/guest_observe.py sample --seconds 120 --project gw2 --out ~/guest-samples.json
```

Copy the JSON back, then run the local alignment helper for each browser artifact:

```sh
python scripts/guest_observe.py align --samples guest-samples.json --run frontend/.journey-runs/RUN.json --out aligned.json --clock-offset-ms OFFSET
```

`OFFSET` is guest UTC minus generator UTC in milliseconds. Preparation estimated -150 ms with
about ±240 ms uncertainty from the best of three SSH round trips. This is an estimate, not clock synchronization.
Schema `gw2-guest-observation-3` aligns VM CPU across both counter reads; memory and containers have independent
collection intervals. Partial overlap is excluded and coverage is reported separately. Short windows may lack samples.
Whole-VM CPU is a percentage of both CPUs together; Docker CPU can exceed 100% for a container using multiple CPUs.

The local-only Nginx template emits bounded JSON access logs and `X-GW2-Test-Request-Id` on API responses.
Fields contain method, URI without query, status, request/upstream duration and completion time; no authentication
headers, cookies, request bodies or API keys. Docker logs are capped at 10 MB × 2 per service. For example:

```sh
docker logs --since 5m gw2-frontend-1
docker ps --format '{{.Names}} {{.Status}}'
```

Filter browser traffic by port 8443; port 8080 also has health checks. Access logs count completed wire requests,
not live offered/inflight work. Browser events may include cached responses and old request IDs. Do not infer
action causality from timestamp overlap or label all container network traffic as GW2 outbound traffic.
DB size/connection/counter snapshots were taken separately with read-only PostgreSQL queries.

## Final preparation observations

All three unmodified commands completed at source `2794ba2`, with one verified account/session each,
between 20:30:35 and 20:31:22 UTC. All 15 actions completed; all three character sections rendered.
Bank/Materials made zero saved-selection writes and retained the original saved digest.

| Journey | Click-to-final observations | Whole-VM CPU mean / sampled peak | Whole-VM used memory peak |
| --- | --- | --- | --- |
| Crafting | Open 3.761 s; change 3.592 s; detail 0.418 s; restore 3.512 s | 50.8% / 82.7% | 1.99 GiB |
| Bank/Materials | Six actions, 0.062–1.142 s | 30.3% / 60.6% | 2.00 GiB |
| Characters | Five actions, 0.058–0.377 s | 14.1% / 19.6% | 1.99 GiB |

These are single warm runs, not distributions or cold-start qualification. Crafting already exceeds the two-second
reference for loading/recalculation at one session; no simultaneous-user threshold can be inferred.
Pauses and the 500 ms confirmation interval are separate from click-to-final timing. Final means the rendered
data checkpoint, not completion of every image download.

All three runs have aligned VM CPU/memory and container samples. Nginx recorded 70, 852 and 155 API completions
in their corrected windows, respectively, all HTTP 200, including icons. The Bank browser trace retained 400
request records and explicitly dropped 453 at its cap; use the independent server log for total wire completions.
The database size point was 32,454,323 bytes, including shared catalog data: it is not per-account storage.
Earlier attempts hit a 240-second external process watchdog and produced no successful artifact;
they remain failed preparation attempts. Subsequent runs of the unmodified scripts completed. Do not erase the
failures or claim that cold recovery was validated.

An exploratory observer-disabled/enabled comparison used two idle 15-second windows: 5.16% versus 7.62%
whole-VM CPU. Background variation is included; the 2.46 percentage-point difference is not an isolated cost measurement.

Evidence remains ignored and local under `target/supervision/resource-vm/`: `readiness-evidence.json`,
the three `*-aligned.json` files, `ready-samples.json`, `ready-nginx.jsonl`, `database-before.csv`,
`database-after.csv`, `observer-overhead.json`, `ready-runs.log` and `final-journeys/`.
The actual key was absent from the final browser artifacts and allowlisted Nginx logs on scan.
Normal native Backend/Frontend/Workflow/Coverage checks passed at `2794ba2`; focused real-browser checks passed
12/12 and guest/host Python checks 33/33. PR #197 carries execution/review evidence.

### Final integrated tooling

PR #197 was merged after full green CI and the clean native review of `7e6d26a8c0`.
Its follow-up corrections separate calculation checkpoints from truncated request logs, classify resolution
failures separately from legitimate unavailable answers, and enforce the byte cap on the compact JSON actually
written. Four targeted byte-bound/resolution/flood checks and two delayed-response/flood checks passed.
The final unmodified scripts at this head passed all three warm journeys again (15 actions):

- `crafting-20261113T204557Z-3080d2.json` — 4 completed, 37,988 bytes.
- `bank-materials-20261113T204618Z-4bb08a.json` — 6 completed, 165,207 bytes.
- `characters-20261113T205200Z-3dfeb9.json` — 5 completed, 65,000 bytes.

These are under `final-journeys/`; `release-ready-runs.log` and `final-character-warm.log` record process
exit status. The intervening Characters attempt encountered automatic refresh and then the external watchdog;
it has no successful artifact and remains a failed attempt. The cause of the watchdog is not established.
Do not treat that attempt as a measured capacity limit or claim cold/stale recovery passed. The numerical
table above remains the earlier fully aligned warm baseline, with its own recorded source revision.

## Operate and continue

Use Hyper-V Manager or `Start-VM GW2-resource-test` / `Stop-VM GW2-resource-test` (normal guest shutdown).
Discover a changed NAT address with `Get-VMNetworkAdapter GW2-resource-test` and update the lab certificate/origin
configuration together before authenticated browser tests. The VM is stopped following the owner's correction.
Local-only setup files, VHDX, SSH keys and lab TLS configuration remain in the ignored lab directory and guest.

Next tests require the owner's recorded behavior and additional real keys. Start with repeated single-session
baselines, then increase independent sessions while reporting achieved interaction rate, latency and errors.
Keep the two-second reference visible. Separate warm reads, stale recovery and refresh scenarios. Measure
per-account marginal DB space and backend offered/completed request rates explicitly; neither is established
by the total-size point or this preparation run. No capacity-discovery run has been performed.
