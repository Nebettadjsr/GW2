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
| `plan` | Only when no story is active and `## To Do` has nothing selectable. It answers one actionable architect request, or else runs the project planner. It does not re-plan while the planning inputs are unchanged since the last pass. | `run_architect_pass`, `run_planning_pass` |
| `select` | Activates the first eligible `## To Do` story. Stops if the queue is empty. | `select_next_story`, `set_active_story` |
| `qa` | Reuses a READY/NO_TESTS_NEEDED plan, otherwise runs QA preparation with its correction retries. | `_ensure_preimplementation_qa` |
| `implement` | Runs Claude with the story prompt or the pending retry/CI-fix prompt. QA tests, the QA plan, and lifecycle files are restored afterwards. The step records the paths Claude changed. | `run_claude_attempt` |
| `evaluate` | Runs the Evaluator plus the conditional QA review. A RETRY returns to `implement` with a retry prompt. | `_evaluate_preserving_completed_attempt` |
| `publish` | Commits only the story's changed paths (plus story, backlog, pointer and QA artifacts) and pushes. Records the SHA. | `commit_and_push` |
| `ci` | Waits for GitHub Actions on the **published SHA**, so later commits on top don't matter. A FAILED result returns to `implement` with the failure report. | `wait_for_commit` |
| `finalize` | Marks the story DONE, moves it to `## Done`, clears the pointer, then commits and pushes that. | `finalize_completed_story` |

Budgets come from `agent/runtime/support/config.py`: `MAX_RETRIES_PER_STORY`, `MAX_CI_FIX_ATTEMPTS`
and `MAX_CLAUDE_FAILED_RUNS_PER_STORY`. When a budget runs out, or on an Evaluator NEEDS_USER/BLOCKED,
or on a QA Product Owner decision, the bridge does three things:

1. It writes the usual intervention or decision record.
2. It moves the story to `## Blocked`.
3. It clears the pointer.

The next run then picks other work.

Some failures stop the run without consuming a budget. The phase is kept, and running the pipeline again
retries the same step:

- Claude ran out of capacity.
- The CI result could not be established.
- CI failed only in suites the story didn't touch.
- The push was rejected.
- The step raised an exception.

## Running the pipeline

Open **GW2 - Development Pipeline** in n8n and click **Execute workflow**. The loop works like this:

1. **Run Next Step** calls `POST /cycle/step`.
2. Fast steps (`select`, `publish`, `finalize`) answer immediately. Long steps answer `running`, and
   **Wait for Step** waits for the bridge's callback.
3. **Continue?** loops back until a step reports `proceed: false`. That happens when a story is blocked,
   the planner adds nothing, the queue is empty, or something needs attention. The last **Record Step** output says
   which step stopped the run and why.

A Wait that times out after 30 minutes is harmless. The loop calls `/cycle/step` again, which
re-attaches to the step that is still running. It never starts a second one. If an execution is stopped,
start a new one; it re-attaches the same way. After a story is finalized the run continues with the next
story, planning first if the queue is empty, until nothing is selectable or a step needs attention.

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
| `POST /cycle/step` | `{"callback_url": <Wait resume URL>}`. Starts the next step, or re-attaches to the running one. |
| `GET /tasks/{id}` | One task and its result. |
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
python -m unittest local_bridge.test_bridge -v
```

`workflows/GW2-Development-Pipeline.json` is the exported pipeline. Import it with
`n8n import:workflow` inside the container to restore it.
