# Workflow service

This Python service schedules development work, executes one stage at a time, and saves progress.
`control.py` handles scheduling and the step loop; `service.py` executes tasks and persists state;
`cycle.py` chooses stages and applies story transitions. Shared agent, QA, Git and CI code lives
in `agent/runtime/`. These are Python modules, not agents. See the
[architecture overview](../agent/docs/ARCHITECTURE.md#what-the-python-parts-do) for an example.

For the read-only Grafana view, use the [agent workflow Docker setup](../agent/docker/grafana/README.md). Its container and saved dashboard are separate from the GW2 application's deployment; the service runs independently of them.

`GET /cycle` includes the active work, latest terminal step, last affected cycle and a selected execution event. A newer manual step supersedes a previous scheduled stop; the last task's reason remains available after blocking clears the active pointer. `human_requests` lists open `UI-*` and `UD-*` records, repository paths, explicit story references and any read errors. These are read-only observations, not changes to scheduling or recovery. Deploy this service version to expose the added fields; updating Grafana alone does not change the live service.

`activity` contains the latest 200 progress entries, persisted in the same `tasks.json` file. Each has a timestamp, role, kind, task, step, story and text (up to 1,000 characters). Scoped runner observers forward Claude narration and Codex messages/emitted summaries, excluding tool events, raw thinking blocks, code blocks and structured result JSON. Step starts, outcomes and failures add system entries. An observer failure is logged without failing the agent attempt. The original console and operational logs are preserved; no additional model calls are made. Grafana displays the newest 60 entries and independently pins the current stop reason, so retention cannot discard that alert.

`retry_limits` exposes the cycle's configured correction budgets, while `running_task.current_retry` reports an inner validation retry when one is actually entered. Retry markers are observations of existing control flow and do not spend or change its budgets. `sync_review` exposes the current documentation-review attempt state. Grafana combines these with the persisted story counters; capacity waits are kept separate from failures and coding revisions.

## Start the service

After deploying this code to the dedicated agent worktree:

```powershell
.\workflow_service\start-service.cmd
```

Use `--now` to run immediately and then keep the schedule, or `--once` to run immediately and
exit when the run stops. `start-workflow.cmd` remains an alias. With environment settings already
loaded, the Python entry point is `python -m workflow_service.control`.

The schedule starts hourly at `:35`, from `17:35` through `06:35`, using the host's local
timezone. Busy starts are skipped; starts missed while the service is off are not replayed.

The same window (`window_start`/`window_end` in the budget settings, both inclusive) gates **every
Claude start**: first attempts, evaluator retries, CI fixes and the session re-creation inside a
running step. Each checks it together with the weekly and 5h limits before invoking Claude, and a
closed window returns `waiting_for_claude_budget`, which stops the run until the next in-window
slot. A Claude already running is never cut off when the window closes, and Codex steps ignore the
window. `control --ignore-window` lifts it for that process only; nothing persists the override.

Each run continues only while completed tasks return `proceed: true`, up to 200 step attempts.
Waiting polls do not consume attempts. A budget wait, empty queue, decision or error stops the
run until a later start. The `control` record persists the task identity, attempt count, consumed
schedule slot and stop reason. An unfinished run resumes on restart, including outside the start
window. A sync that changes workflow Python code requests a process restart through the launcher.

## Pause, abort and manual mode

State lives in the `control` record, so it survives restarts and appears in `GET /cycle`
(`control.hold`, `control.schedule`, `agent_processes`). Send commands with
`POST /control {"action": ...}` or `workflow_service\ctl.cmd <action>`:

| Action | Effect |
| --- | --- |
| `pause` | Finish the current step, then hold. A held service starts nothing, even after a restart or on a schedule slot. |
| `resume` | Clear the hold; an unfinished run continues. |
| `start` | Clear the hold and begin a run now if none is active. |
| `abort` | Hold, then kill every Claude and Codex process tree the service registered. The step is marked `interrupted` with no retry spent and no history entry; after `resume` it reruns from its checkpoint (an implementation continues its Claude session). |
| `auto` / `manual` | `auto`: the clock starts runs, and the Claude window and budget apply. `manual`: runs start only with `start`, `resume` or `--now`, and Claude is **released**: every Claude start is allowed whatever the window or the weekly/5h budget says, until you switch back to `auto` or pause. Claude's own capacity error still stops a run. The mode is persisted. (Not to be confused with the budget `mode` below.) |

Registered processes are tracked by PID and start time in `<state>.processes.json`, never found by
name, so interactive Claude sessions are untouched. Git operations are not registered, and an
abort cannot interrupt a step that waits on GitHub CI; it holds when that step returns. At startup
the service kills agents an earlier process left registered. `ctl kill-leftovers` does the same
without the service and refuses while it is running (use `abort` then). `--now` clears a persisted
hold.

`workflow_service\start-supervisor.cmd` runs a tiny launcher on port 8766 (same token and host). Its only
endpoint, `POST /launch`, opens `start-service.cmd` in a new console unless the service already
answers; it takes no arguments from the caller. The Grafana *Open workflow window* button uses it.
Put a shortcut to the file in `shell:startup` so it exists after every login.

Ctrl+C stops scheduling and waits for the current task to finish. A force-kill has weaker
recovery: startup marks its task interrupted and retries the saved phase. Model calls, file
changes and Git operations are not one transaction; inspect uncertain side effects after a
forced stop. The state file is protected by an OS-held lock against another service process.

## Configuration

| Setting | Purpose |
| --- | --- |
| `GW2_WORKFLOW_TOKEN` | Required bearer token, at least 32 characters. The launcher reads it from a token file if unset. |
| `GW2_WORKFLOW_HOST` | HTTP bind address; the launcher uses `172.29.240.1`, direct Python startup uses loopback. |
| `GW2_WORKFLOW_STATE` | Explicit path to the persistent `tasks.json` file. |
| `GW2_AGENT_WORKTREE` | Dedicated agent checkout; defaults to the sibling `GW2-agent`. |

New installations use `%USERPROFILE%/.gw2-workflow-service/` for `token.txt` and `tasks.json`.
Existing installations keep their state: if no new state file exists, the service reuses
`%USERPROFILE%/.gw2-claude-bridge/tasks.json`. The launcher similarly falls back to the old token
file. Previous `GW2_BRIDGE_TOKEN/HOST/STATE` variables remain accepted, with the new names taking
precedence. The old saved `runner` checkpoint is read as `control`. Do not create a second state
file to perform the rename.

For a fresh installation, create the token once:

```powershell
$serviceDir = Join-Path $env:USERPROFILE '.gw2-workflow-service'
New-Item -ItemType Directory -Force -Path $serviceDir | Out-Null
$tokenFile = Join-Path $serviceDir 'token.txt'
if (-not (Test-Path -LiteralPath $tokenFile)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [IO.File]::WriteAllText($tokenFile, [Convert]::ToBase64String($bytes), [Text.Encoding]::ASCII)
}
```

The source is ready on this branch; deploying it is separate from merging it. For an installation
still using the earlier driver, follow the [migration procedure](legacy/README.md#migration).
It preserves the current worktree, token and state file.

## Stage rules

| Step | What it does | Reused runtime code |
| --- | --- | --- |
| `sync` | Between stories only, at most every 30 minutes: merges `origin/master` into `agent/dev`. A conflict is aborted and stops the run; a merge git refuses because of uncommitted planning output waits for the next sync. Merged `.py` changes under `workflow_service/` or `agent/runtime/` restart the service. | `sync_with_maintainer_branch` |
| `review` | Once per sync that brought in more than dependency bumps: Codex checks the merged range against the owning documents. A lagging descriptive doc is updated; a contradiction with `DOMAIN_SPEC.md` or `TARGET_ARCHITECTURE.md` becomes a user decision or architect request (and a `KNOWN_PROBLEMS.md` entry); a probable defect is reported. Its findings go to the next planning pass. Anything else it writes rolls it back. | `run_sync_review` |
| `plan` | Codex background work, as the old orchestrator did it: answers one actionable architect request, or else runs the project planner. See *When Codex plans* below. | `run_architect_pass`, `run_planning_pass` |
| `select` | Re-queues stories whose interventions or QA decisions were resolved, logs any backlog inconsistency, and activates the first eligible `## To Do` story. If the queue is empty it stops and names any architect request no model can advance. | `select_next_story`, `set_active_story` |
| `qa` | Reuses a READY/NO_TESTS_NEEDED plan, otherwise runs QA preparation with its correction retries. | `_ensure_preimplementation_qa` |
| `qa_review` | Independently classifies a story-relevant application CI failure: production-code defect, test correction, product question or no actionable correction. Read-only. | `run_conditional_review` |
| `qa_repair` | Applies only QA-approved application test/fixture corrections, saves the refreshed plan and protected snapshots, then returns to evaluation. Production code is protected. Also entered straight from `evaluate` when the coder reported `## Interface Deviations` (names, signatures, imports and setup only, before the Evaluator runs). | `execute_test_repair` |
| `implement` | Runs Claude with the story prompt or the pending retry/CI-fix prompt. QA tests, the QA plan, and lifecycle files are restored afterwards. The step records the paths Claude changed, reverts any test file Claude wrote (reported as an integrity finding) and records its `## Interface Deviations`. | `run_claude_attempt` |
| `evaluate` | Runs the Evaluator plus conditional independent QA review. Production deficiencies return to `implement`; an evidenced QA `REVISE_TESTS` verdict schedules `qa_repair`. | `_evaluate_preserving_completed_attempt` |
| `publish` | Commits only the story's changed paths (plus story, backlog, pointer and QA artifacts) and pushes. Records the SHA. | `commit_and_push` |
| `ci` | Waits for GitHub Actions on the **published SHA**. Existing application-job and workflow-warning rules remain unchanged. Story-relevant failures go to `qa_review` before any coding retry; unrelated failures stop for investigation. | `wait_for_commit` |
| `finalize` | Marks the story DONE, moves it to `## Done`, clears the pointer, commits and pushes that, then fast-forwards `master` to `agent/dev`. If `master` moved meanwhile that push is refused (a `workflow_warnings` entry) and the next sync and story carry both. | `finalize_completed_story`, `publish_to_maintainer_branch` |

Budgets come from `agent/runtime/support/config.py`: `MAX_RETRIES_PER_STORY`, `MAX_CI_FIX_ATTEMPTS`
and `MAX_CLAUDE_FAILED_RUNS_PER_STORY`. When a budget runs out, or on an Evaluator NEEDS_USER/BLOCKED,
or on a QA Product Owner decision, the service does three things:

1. It writes the usual intervention or decision record.
2. It moves the story to `## Blocked`.
3. It clears the pointer.

The next run then picks other work.

QA test corrections have a separate two-round story limit and do not consume
Claude's production-code retry counters. Invalid QA output gets one correction
attempt; technical QA failures block with a failure report. Capacity waits retain
the pending request. A saved QA decision and a validated repair plan are reused
after interruption. Corrected tests always pass through evaluation and a new
publication/CI cycle; an earlier red commit is never accepted by a QA approval.

### When Codex plans

The `plan` step runs before the next step whenever one of the old orchestrator's triggers holds:

- an architect request is actionable (always answered first);
- no story is active and the selectable `## To Do` count is at or below
  `PLANNING_TRIGGER_MAX_READY_STORIES` (currently 0);
- the planning inputs changed since the last pass (roadmap, decisions, Product Owner requests,
  instructions, the docs listed in `planning_input_snapshot`), even with stories queued;
- the last pass changed something and reported `independent_work_remaining`;
- a `review` handed it findings from merged maintainer changes.

The same check runs while the active story waits for its Claude budget, so Codex uses that time
instead of idling. A pass on unchanged inputs is never repeated. A rejected pass is retried once with
its validation problems in the prompt; a second rejection is held until the inputs change.

The planner never edits `BACKLOG.md`. It lists `backlog_actions` (`add_to_do`, `move`,
`reorder_to_do`) in its result, and the harness applies them before validating the pass, deriving
every entry from the story file.

### Agent-workflow issues stay separate

Agent-workflow CI failures are reported separately from application acceptance. Workflow warnings
appear in the step result and as `WORKFLOW WARNING` lines in the daily log. Other service errors
can stop a run for recovery. Every completed step outcome is written to `agent/logs/<date>.log`
as `Pipeline <step> [<story>]`.

Some failures stop the run without consuming a budget. The phase is kept, and running the pipeline again
retries the same step:

- Claude or Codex ran out of capacity (`model_capacity_exhausted`).
- The CI result could not be established.
- CI failed only in suites the story didn't touch.
- The push was rejected.
- The step raised an exception.


## Branches

The pipeline and the maintainer never share a working tree:

| Who | Folder | Branch |
| --- | --- | --- |
| You, manual Codex/Claude sessions, dependabot, PRs | `IdeaProjects\GW2` | `master` |
| The pipeline (planner, QA, Claude, evaluator, harness) | `IdeaProjects\GW2-agent` (a `git worktree`) | `agent/dev` |

- Work on `master` as usual and push it. Between stories the pipeline merges `origin/master` into
  `agent/dev` (`sync`), so your workflow fixes and app changes reach it without touching a story in progress.
- Each finished story is pushed on `agent/dev` (CI runs on every branch) and, after CI passes, fast-forwarded
  onto `master`. Pull `master` in your folder to get it.
- `start-service.cmd` always starts the service from `GW2-agent` (override with `GW2_AGENT_WORKTREE`) and restarts after workflow-code sync.
- Set up once: `git worktree add ..\GW2-agent agent/dev`, then copy the ignored local files the
  pipeline needs into it (`.env`, `agent/runtime/artifacts/`, `crafting_graph_cache.json`), run
  `npm ci` in `frontend/`, and trust the folder for Codex in `%USERPROFILE%\.codex\config.toml`.
- A sync conflict stops the run with `sync_conflict`: merge `origin/master` into `agent/dev` by hand in
  `GW2-agent`, commit, and run the pipeline again.


## Working alongside the service

- Keep manual changes in the maintainer checkout. Do not edit the agent worktree while an agent runs;
  files made dirty there can be attributed to its story.
- Stop the service before manually changing lifecycle records. Keep the pointer, backlog, story Status
  and local cycle record consistent, and back up state before editing it.
- Workflow infrastructure changes are separate from application stories. Only pushed maintainer changes
  reach the next between-story sync.
- Never run two drivers against the same worktree. Automatic execution rejects external HTTP task starts.

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

Budget settings and the latest observation now live under `budget` in the service's existing `tasks.json`,
alongside cycle state. They survive a restart. Old state files gain the default settings without
discarding story progress.

| Setting | Meaning |
| --- | --- |
| `mode` | `auto` uses the daily shares; `manual` uses `manual_cap_percent` instead. |
| `allowance_windows` | 5h windows per week the pipeline may use (default 10, i.e. everything left after your own use). |
| `windows_per_full_week` | How many full 5h windows make up 100 % of the weekly limit (default 10). |
| `unlock_time` | When each day's share unlocks (default `17:30`). |
| `manual_cap_percent` | Weekly-usage cap in `manual` mode. |

The service refreshes usage at startup and at each quarter-hour (`:00`, `:15`, `:30`, `:45`). Both the background refresh and the fresh pre-coding
gate use `claude_budget.status()` and the existing `/usage` reader. Observations do not stop a running
coding attempt, consume story retry counters, or change the story's phase. The cached observation is
for inspection, never a substitute for the fresh coding gate.

Additional rules retained from the calculator:

- Both comparisons are strict: usage equal to the session threshold or weekly cap prevents a start.
- If multiple weekly allowances are reported, the highest used percentage gates Claude.
- Unused shares carry within the current week, not into a new week. The weekly cap is capped at 100%.
- Reset dates/times use the host's local clock. Dated resets already in the past roll forward by whole
  weeks, retaining the fix already on `master`; without a printed reset the existing fallback is Saturday
  at 07:00. `next_unlock` is the next share boundary, not a guarantee that the next attempt will fit.
- Manual mode replaces the weekly cap only. It does not bypass the session threshold or expire
  automatically; it remains selected until settings change. A manual cap of zero blocks starts.
- Invalid settings and unreadable usage are errors, not permission to run. Non-finite numeric settings
  are rejected. The displayed rounded cap does not replace the unrounded value used by the calculator.

For inspection, authenticated `GET /budget` returns `settings`, `checked_at`, `result`, `error`, `stale`,
`refresh_interval_seconds`, and an effective `claude_allowed` flag. The same object is included as
`budget` in `GET /cycle`. These reads do not launch a usage probe. After 15 minutes a reading is marked
stale and the effective flag is false. A failed refresh clears `result` and records its error rather
than continuing to display an old permission.

Authenticated `POST /budget` with `{}` refreshes the stored configuration. Supplying `{"budget": {...}}`
validates and persists a replacement settings object, using defaults for omitted fields, then refreshes.
A settings change invalidates the previous observation until the next refresh.


## HTTP API

All endpoints except `/health` require the bearer token.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Liveness check. |
| `GET /cycle` | Active story phase/history, current task, `control` checkpoint and budget observation. |
| `GET /budget` | Saved budget settings and observation; no fresh usage probe. |
| `GET /tasks/{id}` | One task and its result. |
| `POST /budget` | `{}` refreshes the current settings; `{"budget": {...}}` replaces settings and refreshes. |

The retained compatibility endpoints `POST /cycle/step` and `POST /tasks` return 409 during
automatic execution. They are documented in the [legacy guide](legacy/README.md).

## Recovering by hand

- **See where a story stands:** `GET /cycle`, or read `cycles` in the configured state file.
- **A blocked story was resolved:** requeue it the usual way (resolve its intervention or decision).
  When it is selected again it starts a fresh cycle.
- **Activated or switched a story by hand:** the service follows `CURRENT_STORY.md`. A story it has no
  record for starts at `qa`. An existing READY plan makes that step instant.
- **Force a phase:** stop the service, edit that story's `phase` in `tasks.json`, then start the service.


## Tests and CI

The tests use fake agents and external systems. They do not launch models or push work.

```powershell
python -m pytest agent/runtime/tests workflow_service -q --import-mode=importlib -o consider_namespace_packages=true
```

The `agent-runtime` GitHub Actions job runs this suite with branch coverage. Tests cover stage
transitions, retries, budget rules, saved-state migration, scheduling, recovery and single-owner
execution. Application acceptance remains tied to CI for the story's published commit.
