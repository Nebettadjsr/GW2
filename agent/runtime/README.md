# Agent runtime

Run from the repository root:

```powershell
python -m agent.runtime.core.orchestrator
python -m unittest discover -s agent/runtime/tests -t . -p 'test_*.py'
```

The orchestrator command starts the live workflow. Tests use fixtures and mocks;
run tests separately from live orchestration. Individual suites can be run as
`python -m unittest agent.runtime.tests.test_orchestrator -v`.

| Folder | Responsibility |
| --- | --- |
| `core/` | Workflow orchestration, planning, story selection/state and archiving. |
| `runners/` | Claude and planner process execution, output and capacity handling. |
| `evaluation/` | Dispatch/evaluation prompts and the Hermes/Ollama client. |
| `human/` | Product Owner requests, user decisions and intervention records. |
| `support/` | Shared configuration, paths and file helpers. |
| `tests/` | Runtime regression tests and temporary fixtures. |
| `artifacts/` | Generated results and the next execution prompt. |

Python modules and tests are maintained source. Use `agent.runtime.*` package
imports; do not add module directories to `sys.path` or duplicate modules at
the runtime root. All repository and artifact paths belong in
`support/config.py` and are resolved independently of the working directory.

`artifacts/` contains `CLAUDE_RESULT.md`, `DISPATCH_RESULT.json`,
`EVALUATOR_RESULT.json`, `PLANNING_RESULT.json`, `SELECTOR_RESULT.json`, and
`NEXT_PROMPT.md`. Claude owns its implementation result; dispatch/evaluation,
planning, selection and orchestration own their respective generated outputs.
These files are local runtime state, ignored by Git, and are not authoritative
requirements or reusable test fixtures. The tracked `.gitkeep` preserves the
directory in a fresh checkout. Python bytecode caches are also ignored.

Authoritative stories, the current-story pointer, backlog, planning documents,
PO requests, user decisions and interventions remain in their existing locations
outside this package. Their ownership rules remain in `CLAUDE.md` and
`agent/PLANNER_INSTRUCTIONS.md`.

`agent/logs/` (a sibling of `agent/stories/`, defined as `LOGS_DIR` in
`support/config.py`) is also outside this package, for the same reason
`artifacts/` stays inside it: `artifacts/` is disposable/regenerated and
gitignored, while `agent/logs/` is a committed, append-only historical
record and therefore does not belong in a folder whose whole convention is
"generated, not source of truth, safe to delete." See "Daily operational
log" below.

Place new runtime files by responsibility, not in the root. Keep shared helpers
small, tests separate from production code, and generated outputs separate from
source. Avoid duplicate state, compatibility copies and redundant abstractions.

## Aider RepoMap (experimental)

`support/repo_map.py` optionally calls the [Aider](https://aider.chat) CLI to
generate a short repository-structure map, injected as orientation-only
context into Claude's implementation prompt only (`build_claude_prompt()` in
`evaluation/dispatcher.py`). It exists as an experiment to see whether giving
Claude a cheap structural overview up front reduces the exploration/context
tokens it otherwise spends re-discovering the repository layout on its own.

What it is **not**: Aider is never a coding agent here, never involved in
dispatch, evaluation, planning, or story selection, and never a replacement
for reading full source files. The map is explicitly labeled in the prompt as
orientation-only, non-authoritative, and possibly stale/incomplete -- Claude
is told to read a file in full whenever it actually needs its contents.

**Enable/disable:** controlled by `support/config.py`'s `REPO_MAP_ENABLED`,
which defaults to `True` only when the Aider CLI is found on `PATH` (a
checkout without Aider installed behaves exactly as before this feature
existed). Override for a single run without editing any file:

```powershell
$env:AGENT_REPO_MAP_ENABLED = "0"   # force off
$env:AGENT_REPO_MAP_ENABLED = "1"   # force on (requires Aider installed)
```

Any failure (Aider not installed, non-zero exit, timeout, or an unexpected
error) makes `generate_repo_map()` return an empty map; it never raises and
never blocks a Claude run.

**Token budget:** `support/config.py`'s `REPO_MAP_TOKEN_BUDGET`, recommended
initial value **1200** tokens (Aider's own `--map-tokens` setting).

**Measuring the effect (A/B comparison):** `core/orchestrator.py` prints a
`RepoMap: ...` line (enabled/disabled, budget, generated size, generation
time) and a `Claude run measurement: ...` line (repo_map on/off, run
duration, Claude session usage before/after, via the existing
`get_claude_session_usage_percent()` mechanism) around every Claude
invocation, regardless of whether RepoMap is enabled. To compare two runs of
the same story:

```powershell
$env:AGENT_REPO_MAP_ENABLED = "0"
python -m agent.runtime.core.orchestrator   # run A: baseline, no RepoMap

$env:AGENT_REPO_MAP_ENABLED = "1"
python -m agent.runtime.core.orchestrator   # run B: with RepoMap (needs Aider installed)
```

Compare the two runs' `usage_before`/`usage_after` and `duration` log lines
for the same story to see whether RepoMap measurably reduced Claude's
context/token usage.

## Independent Claude/Codex capacity scheduling

`support/capacity.py`'s `CapacityProbe` is a generic, reusable local-cooldown
gate: `available()` calls a model-specific check function at most once per
`MODEL_CAPACITY_RECHECK_SECONDS` (`support/config.py`), caching the result in
between so neither model is probed more than necessary. `core/orchestrator.py`'s
`CapacityScheduler` holds one `CapacityProbe` per model -- Claude's checks
`get_claude_session_usage_percent()` (`runners/claude_runner.py`), Codex's
calls `codex_available()` (`runners/codex_capacity.py`, a genuinely
token-free `app-server` JSON-RPC quota read) -- and applies the scheduling
priority order: resume an unfinished active story first, then execute other
selectable To Do work, then use Codex to replenish the queue while Claude is
busy or the queue is empty, then fall back to local waiting (never a busy
loop) if neither model can currently make progress.

Codex is a planner only: `core/project_planner.py`'s `_run_guarded_planner()`
snapshots protected state (the active story file, `CURRENT_STORY.md`, the
BACKLOG `## Active` section) before every planning pass and verifies it
byte-for-byte afterwards, rolling back and raising if anything protected
changed. This is what guarantees Codex never touches the active story and
never becomes a second writer of orchestration state -- there is exactly one
process (the orchestrator) writing workflow state at a time, by construction,
not by locking.

## Daily operational log

`agent/logs/` holds one append-only file per calendar day, named by date
(`YYYY-MM-DD.log`, e.g. `agent/logs/2026-09-20.log`), written by
`support/daily_log.py`'s `log_line()` and located via `LOGS_DIR` in
`support/config.py`. A file is created automatically the first time a line
is logged after the date rolls over; an existing day's file is only ever
appended to, never truncated or overwritten.

Every orchestrator cycle (`core/orchestrator.py`'s `main()`) logs two lines
before acting: the raw Claude/Codex availability check (`Availability
check: ...`), and the scheduling decision made from it and why (`Decision:
...`). This makes the check -> decide -> (optionally prepare a RepoMap) ->
act ordering described above independently auditable after the fact, not
just verifiable by reading the code.

Unlike `artifacts/`, `agent/logs/` is **committed to Git, not ignored** --
it is meant to be a permanent historical record, not disposable runtime
output. `.gitignore`'s blanket `*.log` rule is deliberately carved out for
it with a negation pattern (`!agent/logs/*.log`).
