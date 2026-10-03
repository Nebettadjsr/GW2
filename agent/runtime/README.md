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
| `core/` | Workflow orchestration, planning, architecture, story selection/state and archiving. |
| `runners/` | Claude and Codex process execution, output and capacity handling. |
| `evaluation/` | Claude prompt construction, and post-implementation evaluation (Codex, read-only). |
| `qa/` | Persistent pre-implementation QA planning, test ownership and conditional read-only review. |
| `human/` | Product Owner requests, architect requests, user decisions and intervention records. |
| `support/` | Shared configuration, paths, file helpers, and the Git/GitHub CI verification integration. |
| `tests/` | Runtime regression tests and temporary fixtures. |
| `artifacts/` | Generated results and the next execution prompt. |

Python modules and tests are maintained source. Use `agent.runtime.*` package
imports; do not add module directories to `sys.path` or duplicate modules at
the runtime root. All repository and artifact paths belong in
`support/config.py` and are resolved independently of the working directory.

`artifacts/` contains `CLAUDE_RESULT.md`, `EVALUATOR_RESULT.json`, `QA_RESULT.json`,
`PLANNING_RESULT.json`, `ARCHITECT_RESULT.json`,
`SELECTOR_RESULT.json`, `ATTEMPT_STATE.json`, `QA_STATE.json`, and `NEXT_PROMPT.md`. Claude owns its implementation
result; evaluation, planning, architecture, selection and
orchestration own their respective generated outputs.
These files are local runtime state, ignored by Git, and are not authoritative
requirements or reusable test fixtures. The tracked `.gitkeep` preserves the
directory in a fresh checkout. Python bytecode caches are also ignored.

Validated story-specific QA plans are persistent source artifacts in
`agent/qa-plans/QA-STORY-*.json`, with the format described in
`agent/qa-plans/README.md`. Pre-implementation acceptance tests are committed
with their story change. An ignored copy under `artifacts/qa-tests/` lets the
harness restore those files if a coding attempt changes or deletes them.

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

## Pre-implementation QA (Codex GPT-6 Luna, medium)

Behavior stories run in this order: Planner, Architect when an actionable
architecture request exists, QA, Coding Agent, Evaluator. Planner and Architect
scheduling and ownership are unchanged. QA runs before a new implementation
attempt; it maps requirements to tests and invariants, checks existing tests and
relevant coverage, writes acceptance/regression tests where practical, and runs
them before implementation. Documentation-only work may have a justified
`NO_TESTS_NEEDED` plan. A requirement ambiguity needing Product Owner input
creates a standard `UD-*` decision and blocks only that story.

QA uses `--model gpt-6-luna` and a `model_reasoning_effort="medium"` Codex
override on every QA call, including conditional post-implementation review.
Planner, Architect and Evaluator do not add model/effort flags and continue to
inherit the Codex CLI configuration. The local Codex configuration inspected
for this implementation was GPT-6 Luna with medium effort; a historical runtime
measurement records a Planner run on GPT-6 Astra, a session-level setting not
pinned by this repository.

QA writes new tests or permanent fixtures only in approved test roots. Python
compares the complete tracked and visible-untracked file snapshot around each
QA run, restores forbidden writes, and rejects the plan if QA changes anything
outside its test boundary. Plans store acceptance checks, invariants, test
levels, existing tests reviewed, test files/specifications, pre-code results,
coverage notes, external source facts, clarifications and conditional-review
requirements. The harness persists a validated plan only after QA finishes.

The coding prompt includes the plan. QA-owned test bytes and the plan are
snapshotted before every coding attempt; edits are restored and reported to
the evaluator, which can trigger independent QA review. The evaluator checks
whether the plan was implemented, not merely whether tests pass. Evaluator
rejection, a stated plan deviation, a test-integrity finding, insufficient
verification, or a QA plan marked critical triggers a read-only QA review.

QA preparation failures retry at most `MAX_QA_ATTEMPTS_PER_STORY` times before
creating a user intervention. Model capacity exhaustion is a scheduling wait,
not a failed attempt. A completed QA plan survives interruption, so the next
run skips QA; generated tests from an interrupted QA run are recorded and reused.
For legacy attempts already in evaluation/publication when this gate was
introduced, the harness writes a compatibility plan and resumes the saved
phase without rerunning coding.

## Evaluation (Codex, read-only)

`evaluation/evaluator.py` judges one finished attempt. It was a local
`hermes3:8b` through Ollama, judging the story text against Claude's own
written report; it is now Codex, in a read-only sandbox, with the working tree
in front of it.

Two things were wrong with the old arrangement. The model was too weak for the
judgment — it produced empty RETRY verdicts that `normalize_evaluation()` had
to rescue deterministically. And its only evidence was the implementer's
account of its own work: it could confirm that a report claimed every criterion
was met, never whether the repository agreed.

So the contract now asks **two** questions, and the second is the one that
needed a stronger evaluator:

1. were this story's own acceptance criteria and Definition of Done satisfied;
2. does the change that was actually made achieve what the story set out to
   achieve.

A change can tick every criterion literally and still fail the story's purpose
— the behaviour holds on one call path but not where a user reaches it, it is
implemented but never wired up, the new test would pass with the feature
removed, a general rule was special-cased to the examples the story named. The
prompt names those patterns, and a shortfall goes into its own `unmet_intent`
field. `build_retry_prompt()` gives it a dedicated section so the next attempt
fixes the substance instead of restating the nearest criterion.

The verdict is a JSON object in Codex's final message, not a file: a read-only
role cannot write one. `parse_evaluator_verdict()` extracts it and raises on
anything unusable — acting on an inferred verdict is worse than retrying. The
model classifies; Python records. `normalize_evaluation()` still has the last
word, and concrete evidence still beats the decision label: a COMPLETE that
also reports unmet work (including an unachieved purpose) becomes a RETRY, and
a self-contradicting verdict with nothing actionable becomes NEEDS_USER rather
than a silent pass.

**The read-only sandbox is the guarantee, not the prompt.** Evaluation runs
while the story's implementation is still uncommitted in the working tree, so
unlike the planner and the architect there is no safe "restore what it
touched" for this role — it has to be structurally unable to touch anything.
It also gets no file-editing guidance, since it has nothing to apply it to.

**Cost and capacity.** An evaluation is now a real Codex run on the shared
Codex budget, so the attempt counts are small:
`EVALUATION_ATTEMPTS * MAX_EVALUATION_BATCHES` = 4 runs before the cycle fails
and a human is involved. Usage exhaustion is **not** one of those attempts —
`ModelCapacityUnavailable` is re-raised past the retry handler and waited out
by `CapacityScheduler.wait_for_codex()`, because the work is already finished
and waiting is free. That wait deliberately does no other Codex work (the
planner and architect share the exhausted budget) and lets no other story
start (see the next section).

## Resuming an interrupted attempt (`ATTEMPT_STATE.json`)

A story's own `## Status` is written by Claude. It states what Claude believes
about the implementation, and it can never state whether the harness has
finished its own remaining steps: evaluation, the commit, the push and the CI
verdict. Reading `Status: DONE` as "story complete" equated those two
different facts, so any interruption between them — an evaluator outage, a
Ctrl+C, a crash — was indistinguishable from a finished story: the next run
skipped the story, selected a fresh one, and left the completed work
unevaluated and unpushed.

`ATTEMPT_STATE.json` records the harness's own position instead, and exists
only while a step is outstanding:

| Phase | Meaning | What a resumed run does |
| --- | --- | --- |
| `AWAITING_EVALUATION` | Claude's attempt finished; no evaluator verdict yet. | Evaluates the story and result already on disk. Claude is not re-invoked and its capacity is not waited on. |
| `AWAITING_CI` | The evaluator accepted the work; publication and the CI verdict are outstanding. | Commits, pushes and waits for CI. The evaluator's verdict is not bought a second time. |
| `AWAITING_CI_FIX` | CI failed and the bounded repair prompt is saved. | Resumes the saved repair prompt without repeating QA or prematurely publishing partial work. |

Every terminal outcome (COMPLETE, BLOCKED, NEEDS_USER) clears the file, and so
does starting a new Claude attempt — what is on disk is about to change, so no
later run may resume the previous attempt. `MAX_RETRIES_PER_STORY` and
`MAX_CI_FIX_ATTEMPTS` counters travel with the record, so a restart cannot hand
the same story a fresh allowance and loop past its escalation.

Any `DONE` story without an attempt-state record is considered complete only
if `EVALUATOR_RESULT.json` contains a `COMPLETE` verdict bound to that story ID
and the exact story/result SHA-256 values. A published commit or passing CI
cannot stand in for a missing or stale evaluator verdict. Such a verdict
resumes evaluation; this covers a crash between the coding agent finishing and
the harness recording `AWAITING_EVALUATION`.

Because `artifacts/` is disposable, a second and independent check covers a
`DONE` story whose record was wiped with it: the repository is asked whether a
commit named `implemented <STORY-ID>` exists **and** whether work is still
outstanding (uncommitted changes outside `agent/logs/`, or commits not on
`origin`). Both halves are required — "no commit" alone is also true of a
story legitimately completed with the gate off, and the tree is dirty within
seconds of every commit because `agent/logs/<date>.log` is tracked and
appended to continuously. It reports nothing git cannot answer, because the
caller resumes the story on it. A story that was published once and then
re-opened by a CI fix is outside its reach by construction; that case is what
the state file covers.

## Commit, push, and the GitHub CI verification gate

A story used to be finished when the evaluator said so. The evaluator judges the
story against its own Definition of Done from what Claude reports; it cannot know
whether the rest of the repository still passes, and proving that inside a model
session means paying for a full regression run on every attempt. So the
authoritative regression verdict moved to GitHub Actions
(`.github/workflows/ci.yml`, defined in `docs/TEST_STRATEGY.md` §36) and this
package waits for it.

What happens the moment the evaluator returns COMPLETE, inside
`execute_active_story()`:

1. `support/git_sync.py` stages everything outstanding, commits it as
   `implemented <STORY-ID>: <title>` and pushes the current branch to `origin`.
   This is the only place either model's work is committed, so one commit is one
   verified story rather than one per file touched, and `.gitignore` alone decides
   what can be swept in (`.env` and `artifacts/` therefore cannot).
2. `support/github_ci.py` polls that commit's workflow runs until they are
   decided, then reports one of three outcomes.
3. **PASSED** completes the story. **FAILED** returns a bounded failure report to
   Claude as another attempt on the same story, with the story set back to
   UNFINISHED so nothing claims to be done that CI has contradicted. Anything
   **UNVERIFIED** — no run appeared, a cancelled run, an unreadable API, a
   rejected push — creates a user intervention: an unproven pipeline is never
   completed as if it were green.

`MAX_CI_FIX_ATTEMPTS` (default 2) bounds the fix loop. It is deliberately a
separate budget from `MAX_RETRIES_PER_STORY`: an evaluator retry and a red
pipeline are different failures, and exhausting either escalates through the
existing user-intervention path rather than looping.

**Cost control is the point of the reporting path.** Only failing jobs are read,
and only their failure annotations — the compact lines
`.github/scripts/summarize_test_failures.py` (and Vitest's own `github-actions`
reporter) publish. A green run costs nothing in Claude's prompt; a red one costs
at most `CI_MAX_REPORTED_FAILURES` entries and `CI_FAILURE_REPORT_MAX_CHARS`
characters. CI logs are never downloaded, and successful output is never fetched.

**Waiting is bounded and quiet.** One poll per `CI_POLL_SECONDS`, a
`CI_RUN_START_TIMEOUT_SECONDS` deadline for a run to appear at all, a
`CI_WAIT_TIMEOUT_SECONDS` ceiling overall, rate-limit backoff instead of hammering
the API, and a terminal-only heartbeat throttled to
`CAPACITY_STATUS_INTERVAL_SECONDS`. The log records transitions — published,
verdict — not the waiting.

**Configuration.** No secret is required: a public repository's run conclusion is
public, and a token in `AGENT_GITHUB_TOKEN`/`GITHUB_TOKEN`/`GH_TOKEN` is optional,
used only to raise the API rate limit, and never logged or written anywhere.
`AGENT_CI_VERIFICATION=0` turns the gate off for a run; unset means "on when this
checkout actually has a GitHub `origin` remote and the workflow file", so a clone
without one behaves exactly as it did before the gate existed (the story completes
on the evaluator's verdict, with a log line saying the gate was skipped).

`tests/__init__.py` reports the gate unavailable to every test and makes
`git_sync.commit_and_push`, `git_sync.push_branch` and `github_ci.fetch_json`
raise. Those paths resolve their own configuration, so a fixture patching
`orchestrator.REPO_ROOT` does not redirect them — without the guard a test that
reached the completion path would commit and push this repository for real.
`tests/test_ci_verification.py` exercises the real implementations against a
throwaway repository with a local bare remote and a scripted API.

## Aider RepoMap (experimental)

`support/repo_map.py` optionally calls the [Aider](https://aider.chat) CLI to
generate a short repository-structure map, injected as orientation-only
context into Claude's implementation prompt only (`build_claude_prompt()` in
`evaluation/claude_prompt.py`). It exists as an experiment to see whether giving
Claude a cheap structural overview up front reduces the exploration/context
tokens it otherwise spends re-discovering the repository layout on its own.

What it is **not**: Aider is never a coding agent here, never involved in
evaluation, planning, or story selection, and never a replacement
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
duration, Claude session and weekly usage before/after, via
`get_claude_usage()`) around every Claude
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

## Pinned Claude model

`runners/claude_runner.py` launches Claude Code with an explicit
`--model` flag whose value is `CLAUDE_MODEL` in `support/config.py`
(currently `claude-opus-5`). Both story execution and Claude planning
runs go through `run_claude()`, so both use that one value -- change it
in that single place to move the orchestrator to a different model.

The pin exists so unattended runs do not silently follow whatever the
interactive CLI default (`~/.claude/settings.json`, `/model`) happens to
be set to. The usage probe (`claude -p /usage`) is deliberately left
unpinned: it runs a slash command and never performs inference.

## Independent Claude/Codex capacity scheduling

`support/capacity.py`'s `CapacityProbe` is a generic, reusable local-cooldown
gate: `available()` calls a model-specific check function at most once per
`MODEL_CAPACITY_RECHECK_SECONDS` (`support/config.py`), caching the result in
between so neither model is probed more than necessary. `core/orchestrator.py`'s
`CapacityScheduler` holds one `CapacityProbe` per model -- Claude's checks
both the five-hour session allowance (usage below 90%) and weekly allowance
(remaining above 50%) from one `/usage` reading in `runners/claude_runner.py`; Codex's
calls `codex_available()` (`runners/codex_capacity.py`, a genuinely
token-free `app-server` JSON-RPC quota read) -- and applies the scheduling
priority order: resume an unfinished active story first, then execute other
selectable To Do work, then use Codex to replenish the queue while Claude is
busy or the queue is empty, then fall back to local waiting (never a busy
loop) if neither model can currently make progress.

Once started, the loop keeps going while any permissible work exists. The
properties that guarantee it, each covered by `tests/test_orchestration_flow.py`:

- **Capacity exhaustion is a scheduling event, never a work result.** A Claude
  run that exits non-zero is classified from its own output tail plus the
  token-free usage probe (`runners/claude_runner.py`'s `run_claude_attempt()`
  and `output_indicates_capacity_exhaustion()`). A capacity interruption keeps
  the story active and `UNFINISHED`, costs nothing from the story's evaluator
  retry budget, and resumes the same work order. A non-zero exit with no
  capacity signal is a *failed run*: retrying it hourly forever could never fix
  it, so after `MAX_CLAUDE_FAILED_RUNS_PER_STORY` consecutive such runs the
  story is escalated to a user intervention and the orchestrator moves on.
- **A failed planning pass never stops execution.** Codex reporting `FAILED`,
  a rolled-back guarded pass, or an unreadable planning input puts the planner
  on a persistent hold for those inputs and is logged; Claude keeps draining
  already-planned work. Changed planning inputs or an explicit cache reset
  permit a retry; a capacity cooldown alone does not.
- **Neither model blocks the other.** Codex exhausted -> Claude still executes
  the queue to zero. Claude exhausted -> Codex still performs useful
  current-scope planning (deliberately past the To Do <= 2 watermark, until a
  pass reports nothing further worth creating). Both exhausted -> the
  orchestrator waits locally and resumes the correct flow by itself.
- **Newly planned work needs no restart**; the next cycle re-reads state and
  selects it.
- **Real gates still block.** An unresolved user decision or an open user
  intervention is waited on locally, never bypassed. Both waits return as soon
  as *any* blocking item resolves, because one resolution can be enough to
  unblock planning or requeue a story, and eligibility is then re-derived from
  scratch.
- **Stale state does not strand execution.** An unusable
  `agent/CURRENT_STORY.md` counts as "no active story" so deterministic
  selection can proceed and overwrite it.
- **One recoverable cycle failure is not fatal.** An unexpected exception (a
  failed evaluation, a transient file error) is retried; evaluation itself is
  retried first, so a finished Claude run is never thrown away and Claude is
  never re-invoked to work around an evaluator failure. After
  `MAX_CONSECUTIVE_CYCLE_ERRORS` consecutive failures the orchestrator stops
  explicitly rather than looping.
- **Evaluation retrying is bounded too.** `MAX_EVALUATION_BATCHES` batches of
  `EVALUATION_ATTEMPTS` ride out a transient outage; a permanent one then
  propagates to the handler above instead of holding the orchestrator in a
  silent loop with a stocked queue. Giving up costs nothing, because
  `ATTEMPT_STATE.json` makes each retried cycle resume at evaluation rather
  than at another Claude run.

## Codex Architect

Codex runs in two logically separate roles, routed by `AGENTS.md`: PROJECT
PLANNING MODE (`core/project_planner.py`) and ARCHITECTURE MODE
(`core/architect.py`). They share one capacity budget and one writer, never a
responsibility.

**Invocation is demand-driven.** `human/architect_requests.py` reads the
`agent/architect-requests/` inbox and answers one scheduling question: which
requests, if any, the architect should be dispatched for. A request is
actionable when it is `OPEN`, or when it is `NEEDS_USER` and every
`agent/user-decisions/UD-*.md` it names is `RESOLVED`. An empty inbox means the
architect never runs -- there is no periodic architecture review.

A human re-queues a request by setting its `## Status` back to `OPEN`
(`TODO`/`NEW`/`PENDING`/`REOPENED` are read as `OPEN` too, because that is what
"not done yet" looks like in every story file). Any other value is never
dispatched on a guess, but `undispatchable_requests()` reports it, and
`_report_undispatchable_architect_requests()` logs each distinct problem once
and names the file in the stop reason. That path exists because the opposite --
a silently skipped request while the planner keeps reporting itself blocked by
that same question -- happened, and is indistinguishable from the architect
ignoring the question.

The
orchestrator attempts it before planning (step 2a in `_run_cycle`), because an
answered question is what unblocks the planner, and dispatches exactly one
request per invocation.

**The flow has no manual step in it:**

```text
Planner hits a question it may not decide
        -> creates agent/architect-requests/AR-NNN-*.md (OPEN)
        -> Architect answers it
              -> RESOLVED   -> planning continues automatically
              -> NEEDS_USER -> OPEN UD-* named in the request
                    -> human resolves the UD
                    -> Architect resumes the same request automatically
                    -> RESOLVED -> planning continues automatically
```

`planning_fingerprint()` includes the inbox, and a finished architect pass
clears the scheduler's "nothing useful to plan" verdict, so a resolved question
always leads to a fresh planning pass rather than a suppressed one. A RESOLVED
request is never dispatched again.

**Role separation is enforced, not requested.** `_run_guarded_architect()`
snapshots and verifies the planner/harness state ARCHITECTURE MODE must not
touch (`CURRENT_STORY.md`, `PROJECT_STATE.md`, BACKLOG, every story, the
Product Owner inbox, every *other* architect request, and the role contracts
themselves) and rolls the pass back if any of it changed. `_run_guarded_planner()`
does the same for the inbox and for `docs/architecture/decisions/ADR-*.md`, so
the planner can add a question but never answer, edit or resolve one.
`validate_architect_result()` then checks the reported result against the files:
the lifecycle transition, the escalation (an OPEN decision the request itself
names), that no existing User Decision was resolved, and that every changed
Markdown file was reported and every reported file really changed. A rejected
pass has its request file restored, so the inbox never holds an unvalidated
transition.

**Capacity exhaustion is never an architecture failure**: the request keeps its
status and is dispatched again after the local cooldown, exactly like a
deferred planning pass.

`validate_planning_result()` additionally rejects a planner that creates a
duplicate of an unresolved question (normalized question text), reuses an
`AR-*` ID, fills in its own `Architect Decision`, creates a request without
reporting it in `architect_requests_created`, or writes an ADR.

Codex as planner: `core/project_planner.py`'s `_run_guarded_planner()`
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

Every orchestrator cycle (`core/orchestrator.py`'s `main()`) still checks
both models and decides what to do next before acting, and logs two lines
when it does: the raw Claude/Codex availability check (`Availability
check: ...`), and the scheduling decision made from it and why (`Decision:
...`). This makes the check -> decide -> (optionally prepare a RepoMap) ->
act ordering described above independently auditable after the fact, not
just verifiable by reading the code.

**Transitions only.** A cycle whose decisions are identical to the previous
cycle's is not logged again (`DecisionLog`). The orchestrator can repeat the
same holding pattern once a minute for hours while it waits for capacity or
for a human, and re-recording it would bury the entries that matter. What is
always logged: capacity exhaustion first detected, capacity available again,
orchestration resumed, planning failures, a story interrupted by exhaustion,
an escalation, and entering/leaving a user-decision or intervention wait.

**Waiting output is terminal-only.** Heartbeat lines -- `Claude capacity
unavailable - waiting...`, `Current usage: ...`, `Next Claude capacity check
in N min`, `Still unresolved: ...` -- go through `support/daily_log.py`'s
`print_status()`, which writes to the stream `ConsoleTee` wraps and therefore
never reaches `agent/logs/`. They are throttled to one burst per
`CAPACITY_STATUS_INTERVAL_SECONDS` (`StatusHeartbeat`). An overnight wait
leaves a readable terminal and a log containing only what happened.

**Re-checks cost no tokens.** Claude's probe runs `claude -p /usage` (a slash
command, no inference); Codex's reads `account/rateLimits/read` over
`app-server`. Both are gated by `CapacityProbe`'s local cooldown, so an
exhausted model is never re-invoked while waiting -- only re-probed, at most
once per `MODEL_CAPACITY_RECHECK_SECONDS`.

**Full console transcript.** `start_console_logging()` (called from the
orchestrator's `__main__` entry point) wraps `sys.stdout`/`sys.stderr` in
`ConsoleTee`, so everything printed to the terminal is appended to the same
daily file -- the orchestrator's own progress output, the Codex planner's
streamed events, and Claude's implementation output. The terminal still
shows exactly what it always did; the log is an addition, not a
replacement. This is what makes an unattended overnight run readable
afterwards.

For Claude's output to reach the log at all, `runners/claude_runner.py`
pipes it (`stdout=PIPE`, `stderr=STDOUT`) and reprints each line rather
than letting the child inherit the terminal -- an inherited descriptor
writes past Python and leaves no record. One consequence: Claude no longer
sees a TTY on stdout, so it emits plain streamed lines instead of
terminal-rendered progress. Tests call `main()` directly and never call
`start_console_logging()`, so they never write to the real `agent/logs/`.

Unlike `artifacts/`, `agent/logs/` is **committed to Git, not ignored** --
it is meant to be a permanent historical record, not disposable runtime
output. `.gitignore`'s blanket `*.log` rule is deliberately carved out for
it with a negation pattern (`!agent/logs/*.log`).


### Planning holds and independent work

`NEEDS_USER` blocks only work dependent on the listed decisions. The planner
must finish justified independent stories/request processing in that same pass;
those outputs undergo the normal validation. Milestone transitions still require
COMPLETE. An unfinished Claude story and its active pointer remain protected
while Codex plans during Claude capacity waits.

`user_decision_ids` uses stable IDs (`UD-010`), resolved from exactly one filename
`agent/user-decisions/UD-010-*.md` (or `UD-010.md`). Markdown titles are optional;
filenames/paths in this result field and duplicate filename IDs are rejected.

The scheduler atomically persists `artifacts/PLANNING_CACHE.json` after NEEDS_USER,
no-work results, or planner execution/validation failure. It fingerprints the
post-pass inputs, including UDs, PO/architect requests, stories/backlog, current
story, continuity, authoritative docs, and runtime contracts/code. Changes,
additions and deletions trigger another pass; logs, result JSON, timestamps and
capacity cooldowns do not. Holds survive restarts. COMPLETE passes that report
further independent work and demonstrate concrete progress continue replenishment; a validated milestone transition can plan the
next phase. Model capacity interruption is not a planning failure and resumes
after capacity returns.

FAILED is distinct from NEEDS_USER and retains diagnostics in the local cache.
It does not repeatedly retry after a cooldown or prevent independent execution
or architecture work. Correct the input/contract problem to retry, or remove
PLANNING_CACHE.json to request an explicit retry. Waiting polls local files and
prints terminal heartbeats; user waits also wake for unrelated planning input
changes. No model is called merely because a wait timer expired.


### Review past individual milestone blockers

A planning pass explores past individual blockers and maximizes useful independent
planning progress within the current milestone before declaring itself blocked.
The planner first reviews the remaining relevant phase areas and inbox requests,
then creates a bounded batch of at most six stories. It discovers all currently
identifiable independent UD/AR questions during that review; the story limit does
not cap question discovery. A question genuinely dependent on an earlier answer
can wait, but an unrelated question must not be deferred to the next human-input cycle.

The result now requires:
- `phase_review`: compact entries with `area`, `outcome`, and `references`. Outcomes
  are PLANNED, READY (independent work left for another batch), USER_DECISION,
  ARCHITECT_REQUEST, COVERED, or PREREQUISITE. Mixed areas use separate entries.
  User/architect blockers cite stable UD/AR IDs; other entries cite source/story artifacts.
- `independent_work_remaining`: true exactly when the review includes READY work.
  It requires COMPLETE plus concrete progress (new stories, UDs, ARs or resolved
  PO requests). Prose-only edits cannot request repeated model calls.

COMPLETE means a successful planning batch, not phase completion. It may report
open UDs, new ARs and independent stories together. NEEDS_USER is valid only after
the review finds no further useful independent planning without human input. A
final NEEDS_USER batch may still publish independent stories; those execute normally.
Architecture-only or story-prerequisite waits use COMPLETE with the flag false.
The scheduler holds either exhausted planning state locally, while separately
executing eligible stories or dispatching actionable architect requests. During
Claude capacity waits, Codex continues bounded batches only while independent
work remains. Meaningful input changes release the existing persistent hold.

For A -> UD-010, B -> UD-011, C -> AR-006 and executable D/E, one pass records
both UDs, the AR, and D/E stories, with five review entries. If another executable
area remains beyond that batch, it is READY and the flag is true; otherwise the
flag is false. Answering UD-010 later does not cause the already-identifiable
UD-011 question to be created for the first time.

Before creating artifacts, reuse existing coverage and OPEN/NEEDS_USER questions,
and consume RESOLVED answers. The prompt includes decision summaries and resolved
PO coverage receipts. Validation rejects duplicate decision IDs/questions, duplicate
story IDs, unreported new UDs, repeated reports of existing ARs, and duplicate open
architecture questions. Semantic coverage/deduplication remains the planner's duty:
local validation can verify a review's structure and consistency, not prove that a
model noticed every relevant area or paraphrased duplicate.

Dependencies remain local. Story eligibility now checks explicit UD and AR references
as well as story IDs, including mixed lists. Every referenced decision must resolve
uniquely with RESOLVED status; every story prerequisite must be DONE. Missing or
ambiguous references stay blocked. New stories with unresolved prerequisites cannot
claim an executable status. Do not guess acceptance criteria behind an unanswered
product or architecture question.

`tests/test_milestone_planning.py` exercises mixed outcomes, early multi-question
reporting, continued idle batches, local exhaustion, duplicate guards and dependent
story selection with scripted outputs. These are offline contract/flow tests, not
a claim of measured live-model discovery completeness.

### Stable planning holds and Codex quota visibility

A final NEEDS_USER pass is held against its post-pass inputs. Schema 2 of
PLANNING_CACHE.json records normalized per-file hashes, the aggregate fingerprint,
the remaining-work flag, and paths changed by that pass. Its new UDs/stories,
backlog changes and PO resolutions are already part of that baseline.

Fingerprinting normalizes UTF-8 BOMs and CRLF/CR/LF line endings only; all other
content and whitespace remains significant. Delayed editor/Git line-ending saves
cannot launch another model pass. Real UD/PO/AR changes, story completion,
CLAUDE_RESULT.md, authoritative documents and planning/validation contracts can.
Own results/cache/logs, test code and presentation/runner code cannot. Changed
input paths are logged once when planning actually resumes. An architect invocation
alone no longer clears a hold; changed authoritative artifacts do. A successful
COMPLETE batch with independent_work_remaining=true can still continue.
Schema-1 aggregate hashes are invalidated by this schema/contract upgrade;
subsequent holds persist in schema 2.

Both CapacityScheduler.plan_if_useful() and answer_architect_request() check quota
before invoking the role. The default probe reads codex app-server's
account/rateLimits/read, never a model turn. Invalid/missing quota fails closed.
Both windows must be below 100%; an explicit reached-limit state also blocks.
This is an availability check, not a reservation for the entire run.

That same reading now prints one terminal-only pre-role line, for example:
`Codex available before architect: primary 25% used/300min; secondary 80% used/10080min`.
Display causes no extra RPC. Local capacity-wait heartbeats show the last reading.
The endpoint provides percentages/windows, not an exact number of tokens remaining.
Role completion lines now distinguish planner/architect and include cached input.
Gross input is cumulative across model requests, not initial prompt size.

See [the measured usage investigation](reports/2026-09-26-planner-usage.md) for
the evidence behind the supplied-context bounds described next.

### Supplied context is indexed, not narrated

`core/planning_context.py` builds everything the two Codex roles receive up
front. Each role starts a fresh session, so whatever is supplied is resent in
full with every model request inside the run; the measured planning prompt was
221k characters, 139k of it completed-story narrative from `BACKLOG.md`.

What is compacted, and what is guaranteed to survive it:

- **Backlog index.** `## Active`/`## To Do`/`## Blocked` keep BACKLOG's own
  order (the To Do order is the execution priority) and their descriptions.
  `## Done`/`## Archived` become one line per story:
  `ID | filename | status | milestone | title`. Every story ID and filename in
  the project is present, so duplicate detection and milestone-coverage review
  need nothing else; status, milestone and dependencies come from each story
  file, not from backlog prose, and every prerequisite identifier
  (`STORY-*`, `UD-*`, `AR-*`) is listed in full even when the surrounding prose
  is summarized. A per-milestone status roll-up is derived at the end. A
  backlog entry whose story file is missing is reported as `FILE MISSING`
  rather than silently indexed.
- **Decisions, receipts, architect requests.** Anything unresolved is supplied
  in full -- its wording is what makes a duplicate recognizable. A RESOLVED
  user decision keeps its question and its answer; a resolved PO receipt keeps
  a summary plus the artifacts it cites; a resolved architect request keeps its
  decision line plus the documents recording it.

Nothing is dropped: each compacted entry names the file that holds the detail,
and both role contracts allow reading that one file, or one named BACKLOG
section, when a specific detail decides something. Both prompts and both
contracts (and `AGENTS.md`'s routing step) now state that a contract supplied
in the prompt must not be read again from disk, and the shared
`CONTEXT_DISCIPLINE` block in `runners/local_planner_runner.py` states the
read rules both roles share: section reads over whole documents, batched
independent reads, never the same read twice, never a file printed merely to
edit it, and never truncated evidence.

Measured on the repository state of 2026-09-26, the planning prompt went from
221,331 to ~86,600 characters (backlog 139,117 -> ~17,100; user decisions
16,822 -> ~7,600; resolved PO receipts 10,530 -> ~5,700; architect requests
3,429 -> ~2,200). These are prompt characters, not model tokens: the recorded
token counters below are what actually establishes a usage change.

### Prompt-size and usage diagnostics

Before each planner/architect run, a terminal-only breakdown prints how many
characters each supplied section costs (never the sections themselves):

```text
Planner context:
  backlog index: 17,141 chars
  ...
  role instructions: 29,110 chars
  harness prompt scaffolding: 13,497 chars
  total supplied context: 86,610 chars (prompt only; tool output during the run adds to it)
```

When the run ends, `UsageTally` reports what the model actually charged:
number of model requests, gross input, cached input, arithmetic uncached input
and output tokens. Per-request `token_count` updates and the turn's own
`turn.completed` usage are reported as separate lines rather than added
together, and a run whose stream carried no usage says so instead of printing
zeros.
