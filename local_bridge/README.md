# n8n development-cycle bridge

n8n drives the GW2 story cycle; this local bridge does the work. The bridge runs on Windows next to the
repository, and n8n runs in Docker. The bridge keeps one persisted **cycle record** per story. The record's `phase`
names the next step. A step only advances the phase once it has finished, so an interruption at any
point simply reruns the same step.

```
[plan] -> select -> qa -> implement -> evaluate -> publish -> ci -> finalize -> (next story)
                             ^             |                     |
                             +--- RETRY ---+----- CI FAILED -----+
```

| Step | What it does | Reused runtime code |
| --- | --- | --- |
| `plan` | Codex background work, as the old orchestrator did it: answers one actionable architect request, or else runs the project planner. See *When Codex plans* below. | `run_architect_pass`, `run_planning_pass` |
| `select` | Re-queues stories whose interventions or QA decisions were resolved, logs any backlog inconsistency, and activates the first eligible `## To Do` story. If the queue is empty it stops and names any architect request no model can advance. | `select_next_story`, `set_active_story` |
| `qa` | Reuses a READY/NO_TESTS_NEEDED plan, otherwise runs QA preparation with its correction retries. | `_ensure_preimplementation_qa` |
| `implement` | Runs Claude with the story prompt or the pending retry/CI-fix prompt. QA tests, the QA plan, and lifecycle files are restored afterwards. The step records the paths Claude changed. | `run_claude_attempt` |
| `evaluate` | Runs the Evaluator plus the conditional QA review. A RETRY returns to `implement` with a retry prompt. | `_evaluate_preserving_completed_attempt` |
| `publish` | Commits only the story's changed paths (plus story, backlog, pointer and QA artifacts) and pushes. Records the SHA. | `commit_and_push` |
| `ci` | Waits for GitHub Actions on the **published SHA**, so later commits on top don't matter. Only the application jobs decide; a failing agent-runtime job becomes a `workflow_warnings` entry. A FAILED result returns to `implement` with the failure report. | `wait_for_commit` |
| `finalize` | Marks the story DONE, moves it to `## Done`, clears the pointer, then commits and pushes that. | `finalize_completed_story` |

Budgets come from `agent/runtime/support/config.py`: `MAX_RETRIES_PER_STORY`, `MAX_CI_FIX_ATTEMPTS`
and `MAX_CLAUDE_FAILED_RUNS_PER_STORY`. When a budget runs out, or on an Evaluator NEEDS_USER/BLOCKED,
or on a QA Product Owner decision, the bridge does three things:

1. It writes the usual intervention or decision record.
2. It moves the story to `## Blocked`.
3. It clears the pointer.

The next run then picks other work.

### When Codex plans

The `plan` step runs before the next step whenever one of the old orchestrator's triggers holds:

- an architect request is actionable (always answered first);
- no story is active and the selectable `## To Do` count is at or below
  `PLANNING_TRIGGER_MAX_READY_STORIES` (currently 0);
- the planning inputs changed since the last pass (roadmap, decisions, Product Owner requests,
  instructions, the docs listed in `planning_input_snapshot`), even with stories queued;
- the last pass changed something and reported `independent_work_remaining`.

The same check runs while the active story waits for its Claude budget, so Codex uses that time
instead of idling. A pass on unchanged inputs is never repeated, and a FAILED planning pass is held
until its inputs change.

### Agent-workflow issues stay separate

Problems in the workflow itself (agent runtime, bridge, n8n) never block or change a GW2 story.
They show up as `workflow_warnings` in the step result and as `WORKFLOW WARNING` lines in the daily
log. Every step outcome is also written to `agent/logs/<date>.log` as `Pipeline <step> [<story>]`.

Some failures stop the run without consuming a budget. The phase is kept, and running the pipeline again
retries the same step:

- Claude or Codex ran out of capacity (`model_capacity_exhausted`).
- The CI result could not be established.
- CI failed only in suites the story didn't touch.
- The push was rejected.
- The step raised an exception.

## Running the pipeline

The pipeline starts by itself every hour at :35 from 17:35 to 06:35 (**Every Evening Hour**). It skips
if another run is active. You can also start it any time with **Execute workflow**. The loop works like this:

1. **Read Budget Settings** reads the Claude budget row, then **Run Next Step** calls `POST /cycle/step`.
2. Fast steps (`select`, `publish`, `finalize`) answer immediately. Long steps answer `running`, and
   **Wait for Step** waits for the bridge's callback.
3. **Continue?** loops back until a step reports `proceed: false`. That happens when a story is blocked,
   the planner adds nothing, the queue is empty, or something needs attention. The last **Record Step** output says
   which step stopped the run and why.

A Wait that times out after 30 minutes is harmless. The loop calls `/cycle/step` again, which
re-attaches to the step that is still running. It never starts a second one. If an execution is stopped,
start a new one; it re-attaches the same way. After a story is finalized the run continues with the next
story, planning first if the queue is empty, until nothing is selectable or a step needs attention.

## Claude budget

Claude only starts when both checks pass. It is never stopped mid-run.

- **5h session:** below 90 % used (`CLAUDE_USAGE_LIMIT_PERCENT`).
- **Weekly budget:** weekly usage below the cap in force. The week, which starts at the reset time
  `claude -p /usage` reports, has seven daily shares. Each share unlocks at `unlock_time` on its day and
  stays available until the reset. A share is `allowance_windows / 7` windows, and one full 5h window is
  `100 / windows_per_full_week` % of the weekly limit. With the defaults, Wednesday after 17:30 allows
  5/7 of the week (about 71 %).

If a check fails, `implement` stops with `waiting_for_claude_budget` and keeps its phase. The next hourly
run tries again. A Claude run that fails while `/usage` shows no capacity counts as a capacity pause,
not as a failed run.

Settings live in the n8n data table **GW2 Claude Budget** (one row). Edit it in n8n; the next step uses
the new values.

| Column | Meaning |
| --- | --- |
| `mode` | `auto` uses the daily shares; `manual` uses `manual_cap_percent` instead. |
| `allowance_windows` | 5h windows per week the pipeline may use (default 10, i.e. everything left after your own use). |
| `windows_per_full_week` | How many full 5h windows make up 100 % of the weekly limit (default 10). |
| `unlock_time` | When each day's share unlocks (default `17:30`). |
| `manual_cap_percent` | Weekly-usage cap in `manual` mode. |
| `now_*` | Live status, written every 15 minutes by **GW2 - Claude Budget**. Run that workflow to refresh it. |

Don't run the old orchestrator (`agent/runtime/core/orchestrator.py main`) at the same time.

## Start the bridge

Paste this into PowerShell. It creates the bearer token once and keeps it outside the repository.

```powershell
Set-Location C:\Users\Administrator\IdeaProjects\GW2
$secretDir = Join-Path $env:USERPROFILE '.gw2-claude-bridge'
New-Item -ItemType Directory -Force -Path $secretDir | Out-Null
$tokenFile = Join-Path $secretDir 'token.txt'
if (-not (Test-Path $tokenFile)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [IO.File]::WriteAllText($tokenFile, [Convert]::ToBase64String($bytes), [Text.Encoding]::ASCII)
}
$env:GW2_BRIDGE_TOKEN = (Get-Content -Raw $tokenFile).Trim()
$env:GW2_BRIDGE_HOST = '172.29.240.1'
$env:GW2_BRIDGE_STATE = Join-Path $secretDir 'tasks.json'
$env:GW2_N8N_CALLBACK_ORIGIN = 'http://localhost:5678'
python .\local_bridge\bridge.py
```

Keep the window open. Restart the bridge after changing its code. A restart marks a running step
`interrupted`; the next pipeline run repeats that step. An interrupted Claude run resumes with a
"continue the interrupted attempt" prompt.

`GW2_BRIDGE_HOST` must be the Windows address on the Docker interface. `host.docker.internal`
does not work in this setup. If the address changes, find it with
`Get-NetIPAddress -AddressFamily IPv4` and update the URL in **Run Next Step**. Check reachability from
the container:

```powershell
docker exec n8n node -e "fetch('http://172.29.240.1:8765/health').then(r=>r.text()).then(console.log)"
```

n8n authenticates with the **Header Auth account** credential: name `Authorization`, value
`Bearer <token.txt contents>`.

## HTTP API

All endpoints except `/health` need the bearer token.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Liveness check. |
| `GET /cycle` | The active cycle record (phase, budgets, published SHA, step history) and any running task. |
| `POST /cycle/step` | `{"callback_url": <Wait resume URL>, "budget": <settings row>}`. Starts the next step, or re-attaches to the running one. |
| `GET /tasks/{id}` | One task and its result. |
| `POST /budget` | `{"budget": <settings row>}`. Current usage, the cap in force and whether Claude may start. |
| `POST /tasks` | `{"prompt", "agent": "claude"\|"codex", "callback_url"}`. A read-only agent call used by the **Bridge/Codex Connection Test** workflows. |

Only one task runs at a time. A step task's result carries `step`, `outcome`, `proceed`, `reason` and
the `phase` after the step.

## Recovering by hand

- **See where a story stands:** `GET /cycle`, or read `cycles` in `%USERPROFILE%\.gw2-claude-bridge\tasks.json`.
- **A blocked story was resolved:** requeue it the usual way (resolve its intervention or decision).
  When it is selected again it starts a fresh cycle.
- **Activated or switched a story by hand:** the bridge follows `CURRENT_STORY.md`. A story it has no
  record for starts at `qa`. An existing READY plan makes that step instant.
- **Force a phase:** stop the bridge, edit that story's `phase` in `tasks.json`, then start the bridge.

## Tests

The tests fake agents, Git and CI; they never launch Claude or Codex or touch the remote.

```powershell
python -m unittest local_bridge.test_bridge local_bridge.test_claude_budget -v
```

`workflows/` holds the exported **GW2 - Development Pipeline** and **GW2 - Claude Budget**. Import them with
`n8n import:workflow` inside the container to restore them. A workflow published from the CLI
(`n8n publish:workflow`) only takes effect after `docker restart n8n`. The **GW2 Claude Budget** data table
is not part of these exports. Recreate it in n8n with the columns listed under *Claude budget*.
