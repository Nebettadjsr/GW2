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
