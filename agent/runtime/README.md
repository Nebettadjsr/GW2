# Agent runtime

The story workflow runs as the n8n **GW2 - Development Pipeline**, which calls
the local bridge (`local_bridge/`) one step at a time. The bridge's README owns
the pipeline: its steps, rules, budgets, recovery and how to start it. This
package holds the building blocks the bridge calls: QA, the implementation
prompt and Claude runner, the Evaluator, planner and architect passes, story
state and the Git/GitHub CI integration. `core/orchestrator.py` is no longer an
entry point; it keeps only the shared story-cycle helpers.

Run the tests from the repository root (they use fixtures and mocks and never
start a model or push):

```powershell
python -m unittest discover -s agent/runtime/tests -t . -p 'test_*.py'
python -m unittest local_bridge.test_bridge local_bridge.test_claude_budget
```

| Folder | Responsibility |
| --- | --- |
| `core/` | Story-cycle helpers used by the bridge (`orchestrator.py`), planning, architecture, story selection/state and archiving. |
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
`SELECTOR_RESULT.json`, `QA_STATE.json`, and `NEXT_PROMPT.md`. Claude owns its implementation
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
QA run, buffers the harness's own append-only log writes until the
snapshot is checked, restores forbidden writes without overwriting a newer
concurrent change, and rejects the plan if QA changes anything outside its
test boundary. Plans store acceptance checks, invariants, test
levels, existing tests reviewed, test files/specifications, pre-code results,
coverage notes, external source facts, clarifications and conditional-review
requirements. The harness persists a validated plan only after QA finishes.

The coding prompt includes the plan. QA-owned test bytes and the plan are
snapshotted before every coding attempt; edits are restored and reported to
the evaluator, which can trigger independent QA review. The evaluator checks
whether the plan was implemented, not merely whether tests pass. Evaluator
rejection, a stated plan deviation, a test-integrity finding, insufficient
verification, or a QA plan marked critical triggers a read-only QA review.

An invalid QA plan may be retried up to `MAX_QA_ATTEMPTS_PER_STORY` times, with
the validation error supplied as corrective feedback. File-protection,
repository snapshot/restoration, and model-runner failures are technical
failures: they do not consume repeated no-op retries or create Product Owner
decisions. The story is blocked with an actionable report under ignored
`agent/runtime/artifacts/qa-failures/`; after fixing the technical cause, a
developer manually requeues it. Only a validated `NEEDS_USER` plan uses the
Product Owner decision flow. Model capacity exhaustion is a scheduling wait,
not a failed attempt. A completed QA plan survives interruption, so the next
run skips QA; generated tests from an interrupted QA run are recorded and reused.

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
`EVALUATION_ATTEMPTS * MAX_EVALUATION_BATCHES` = 4 runs before the evaluate
step fails; the story keeps its evaluate phase, so the next pipeline run
evaluates again and never re-runs Claude. Usage exhaustion is **not** one of
those attempts: `ModelCapacityUnavailable` passes straight through and the
bridge records it as a pause (`model_capacity_exhausted`).

## Resuming an interrupted step

The bridge's per-story cycle record (`local_bridge/README.md`) replaced the old
`ATTEMPT_STATE.json` journal. A step only advances the story's phase once it has
finished, so an interruption reruns the same step; retry and CI-fix budgets live
in the same record. An interrupted Claude run resumes with a continuation prompt.

## Commit, push, and the GitHub CI verification gate

A story used to be finished when the evaluator said so. The evaluator judges the
story against its own Definition of Done from what Claude reports; it cannot know
whether the rest of the repository still passes, and proving that inside a model
session means paying for a full regression run on every attempt. So the
authoritative regression verdict moved to GitHub Actions
(`.github/workflows/ci.yml`, defined in `docs/TEST_STRATEGY.md` §36) and this
package waits for it.

What happens after the evaluator returns COMPLETE, in the bridge's `publish`
and `ci` steps:

1. `support/git_sync.py` stages only the story's publication scope: changed
   paths observed around the coding attempts, validated planning outputs, the
   story/backlog/pointer, and its QA plan and prepared tests. It commits exactly
   those paths (`git commit --only`) as `implemented <STORY-ID>: <title>` and
   pushes the current branch to `origin`. Unrelated dirty or staged files stay
   out of the commit.
2. `support/github_ci.py` polls the workflow runs of that published SHA until
   they are decided. Only the application jobs decide the story
   (`APPLICATION_CI_VERDICT_POLICY`); a failing agent-runtime job is reported as
   a workflow warning.
3. **PASSED** finalizes the story. **FAILED** is attributed using structured
   failing-job names. A known failing suite whose source scope is disjoint from
   the published story paths stops the run without invoking Claude; the story
   stays at its `ci` phase until CI is fixed and rerun. Failures in a known
   overlapping suite, or failures whose ownership cannot be established, are
   conservatively sent through the bounded repair path. Anything **UNVERIFIED**
   — no run appeared, a cancelled run, an unreadable API — also keeps the `ci`
   phase for a later recheck: an unproven pipeline is never completed as if it
   were green.

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
`CI_WAIT_TIMEOUT_SECONDS` ceiling overall, and rate-limit backoff instead of
hammering the API. The log records the step outcome, not the waiting.

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
reached the publication path would commit and push this repository for real.
`local_bridge/test_bridge.py` imports the same guards.
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

**Measuring the effect (A/B comparison):** `_prepare_implementation_prompt()`
prints a `RepoMap: ...` line (enabled/disabled, budget, generated size,
generation time) in the bridge console before every Claude run. To compare,
start the bridge once with `$env:AGENT_REPO_MAP_ENABLED = "0"` and once with
`"1"`, and compare the Claude weekly-usage change per story (`/usage`, or the
`now_weekly_used` column of the GW2 Claude Budget data table).

## Pinned Claude model

`runners/claude_runner.py` launches Claude Code with an explicit
`--model` flag whose value is `CLAUDE_MODEL` in `support/config.py`
(currently `claude-opus-5`). Both story execution and Claude planning
runs go through `run_claude()`, so both use that one value -- change it
in that single place to move the pipeline to a different model.

The pin exists so unattended runs do not silently follow whatever the
interactive CLI default (`~/.claude/settings.json`, `/model`) happens to
be set to. The usage probe (`claude -p /usage`) is deliberately left
unpinned: it runs a slash command and never performs inference.

## Model capacity

Claude starts only while its 5h session is below `CLAUDE_USAGE_LIMIT_PERCENT`
and its weekly usage is within the Claude budget kept in n8n
(`local_bridge/claude_budget.py`, documented in `local_bridge/README.md`).
`claude -p /usage` is a slash command and spends no tokens.

A Claude run that exits non-zero is classified from its own output tail
(`runners/claude_runner.py`'s `run_claude_attempt()` and
`output_indicates_capacity_exhaustion()`) and, as a second check, from `/usage`.
A capacity interruption keeps the story at `implement`, costs nothing from any
budget, and resumes with a continuation prompt. A non-zero exit with no
capacity signal is a *failed run*; after `MAX_CLAUDE_FAILED_RUNS_PER_STORY`
consecutive ones the story is escalated to a user intervention. Codex
exhaustion during QA, evaluation, planning or architecture
(`ModelCapacityUnavailable`) is likewise a pause, never a failed step.

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
bridge's `plan` step answers it before any planning pass, between stories and
while Claude waits for its budget, because an answered question is what
unblocks the planner. It dispatches exactly one request per step.

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

`planning_fingerprint()` includes the inbox, so a resolved question changes the
planning inputs and leads to a fresh planning pass rather than a suppressed
one. A RESOLVED request is never dispatched again.

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
status and is dispatched again on a later pipeline run.

`validate_planning_result()` additionally rejects a planner that creates a
duplicate of an unresolved question (normalized question text), reuses an
`AR-*` ID, fills in its own `Architect Decision`, creates a request without
reporting it in `architect_requests_created`, or writes an ADR.

Codex as planner: `core/project_planner.py`'s `_run_guarded_planner()`
snapshots protected state (the active story file, `CURRENT_STORY.md`, the
BACKLOG `## Active` section) before every planning pass and verifies it
byte-for-byte afterwards, rolling back and raising if anything protected
changed. This is what guarantees Codex never touches the active story and
never becomes a second writer of workflow state -- the bridge runs one step at
a time, so exactly one process writes workflow state, by construction.

## Daily operational log

`agent/logs/` holds one append-only file per calendar day, named by date
(`YYYY-MM-DD.log`, e.g. `agent/logs/2026-09-20.log`), written by
`support/daily_log.py`'s `log_line()` and located via `LOGS_DIR` in
`support/config.py`. A file is created automatically the first time a line
is logged after the date rolls over; an existing day's file is only ever
appended to, never truncated or overwritten.

The bridge writes one line per finished pipeline step
(`Pipeline <step> [<story>]: <outcome> -- <reason>`), `WORKFLOW WARNING` lines
for agent-workflow problems, and the transitions recorded by the runtime
helpers it calls (QA preparation, evaluation retries, story blocking and
finalization, backlog inconsistencies). Waiting is never logged. Claude's
streamed output is piped through `runners/claude_runner.py` and printed in the
bridge console, not the log. Tests never write here: `agent/runtime/tests`
drops writes to the committed directory, and the bridge tests import that guard.

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

The bridge decides when to plan (`local_bridge/cycle.py`'s `codex_work_needed()`,
documented in `local_bridge/README.md`) from `planning_fingerprint()`. The
fingerprint includes UDs, PO/architect requests, authoritative docs, planner
contracts, continuity and story requirements. Product/domain and target
requirements, architecture decisions, known defects, coding/testing contracts,
and story requirements are planning inputs. Descriptive implementation snapshots
(`CURRENT_STATE_SPEC.md`, `CURRENT_ARCHITECTURE.md`), logs, result JSON,
timestamps and capacity readings are excluded: updating those records alone does
not earn a full planning pass. Story status/result/follow-up sections,
active-story pointers, backlog section moves and Claude run artifacts are
excluded because they record execution progress, not new planning inputs.
Fingerprinting normalizes UTF-8 BOMs and line endings only.

Relevant input changes trigger another pass even with eligible stories waiting.
A pass on unchanged inputs is never repeated, which also holds a FAILED pass
until its inputs change. A validated Planner result may request one bounded
follow-up pass when independent work remains and that pass changed something;
finding presence alone does not request another pass. A validated milestone
transition can plan the next phase. Model capacity interruption is not a
planning failure.

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
Eligible stories still execute and actionable architect requests are still
answered while planning has nothing new to do. During Claude budget waits, Codex
continues bounded batches only while independent work remains.

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
reporting, duplicate guards and dependent story selection with scripted outputs. These are offline contract/flow tests, not
a claim of measured live-model discovery completeness.

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
