# Development workflow

This is the reference for how this repository develops and releases changes.
Verified against the committed GitHub workflows and helper scripts on 2026-11-17.
GitHub issues, PRs, commits, reviews and Actions runs hold execution evidence.
[The roadmap](ROADMAP.md) describes product direction; [the planning backlog](../agent/BACKLOG.md)
records draft order and the owner's current authorization. Neither is a second
execution queue.

## 1. Roles and authority

| Role | Responsibility | Contract |
|---|---|---|
| Product Owner | Product behavior, UI acceptance, budget and publication decisions | Live issue/PR and interactive session |
| Interactive Codex supervisor | Scope, readiness, results, local acceptance and approved deployment | [Supervisor](../agent/SUPERVISOR_INSTRUCTIONS.md) |
| Explicitly assigned Codex planner | Approved goals into bounded issue proposals and dependency order | [Planner](../agent/PLANNER_INSTRUCTIONS.md) |
| Claude programmer | Implement the assigned issue and respond to its review | [Programmer](../CLAUDE.md) |
| Native Codex PR reviewer | Compare actual PR with its issue, specifications and tests | [Review rules](../AGENTS.md#code-review-rules) |
| GitHub Actions | Select one issue, prepare/publish its branch, run CI, enforce the conditional merge gate and capture failed work | Implementation map in section 11 |

The planner and supervisor are roles with instructions. There is no committed
background planner/supervisor service or planner schedule. Native Codex PR
review is a separate GitHub integration.
When prepared work needs new requirements or priorities, assign a planning
session explicitly.

Normally the programmer implements meaningful tests with the code. A specific
approved issue/session may assign independent QA to test/fixture authorship;
that scoped contract takes precedence for that work. Read the current
authorization in `agent/BACKLOG.md` and the released issue. The retired mandatory
QA/coder split does not apply to every new change.

## 2. Prepare one executable issue

1. Inspect live GitHub issues/PRs before creating work. An old problem or test
   report may already be resolved.
2. Read the owning domain, architecture, UX and test documents. Source code
   establishes observed behavior; it cannot decide a new product rule.
3. Prepare goal, dependencies, constraints, outcome-based acceptance criteria,
   relevant files/docs and required verification. Include local database or
   browser evidence when default CI cannot establish the behavior.
4. Resolve genuine Product Owner questions in the issue/PR and use
   `needs-decision` while the answer is open.
5. The supervisor releases dependency-complete work within the owner's
   authorization. One issue gets one branch and one PR. A draft plan does not
   authorize implementation or a Claude run.

The implemented selector requires titles starting `STORY-<UPPERCASE>-<number>`,
for example `STORY-WEB-123: Describe the outcome`. Dependencies are other story
IDs in a `## Dependencies` section. The legacy `Migration status: ...
Dependencies: ...` line remains a fallback for migrated issues. A standalone
GitHub `#123` reference does not establish a selector dependency.

## 3. Labels and their implemented effects

| Label/state | Selection and merge behavior |
|---|---|
| `auto` on an open story | Eligible for the next explicit Claude dispatch; required for automatic merge |
| `pairing` | Supervisor hold; skipped by selection and automatic merge/requeue |
| `needs-decision` | Product decision hold; skipped by selection and automatic merge/requeue |
| `blocked` | Work hold; skipped by selection and automatic merge/requeue |
| `needs-rework` on an `auto` story with one open PR | Continue that PR before new work on the next dispatch; blocks merge until publication clears it |
| Body line `Migration status: BLOCKED` | Legacy hold still honored by selector and merge gate |
| Draft PR | Held from automatic merge and automatic requeue |

The selector checks issue holds. The merge/requeue gate checks issue and PR
holds. Put a selection hold on the issue rather than relying only on a PR label.

Labels, comments and pushes do not start Claude. A merged prerequisite can unlock
a dependent issue for the next dispatch; it does not start another run.

## 4. Explicit dispatch and selection

Use GitHub **Actions > Run next ready story with Claude > Run workflow**, on
`master`. `claude.yml` accepts only `workflow_dispatch`; no schedule, comment
or push trigger starts implementation. Its job requires actor `Nebettadjsr`
and ref `refs/heads/master`. An authorized supervisor may use the equivalent
GitHub API/CLI dispatch. Each dispatch starts one attempt, not an unattended loop.

```bash
# Use an authenticated GitHub CLI session after execution is authorized.
gh workflow run claude.yml --repo Nebettadjsr/GW2 --ref master
gh run list --repo Nebettadjsr/GW2 --workflow claude.yml --limit 5
```

The `claude-implementation` concurrency group serializes implementation;
a new dispatch does not cancel an active run. The job timeout is 45 minutes.
`CLAUDE_MAX_TURNS` defaults to 40; the repository variable accepts 1-100.
These are per-attempt bounds, not subscription usage or money limits.

`select_claude_story.py` reads all issue/PR pages and:

1. Considers open, correctly titled stories with `auto`.
2. Skips issue holds and the legacy blocked marker.
3. Requires each parsed dependency closed as completed with an associated PR
   merged into this repository's `master`. Closing as not planned or without
   merged code does not satisfy a dependency.
4. Associates PRs by a `Closes`/`Fixes`/`Resolves #N` body directive or a
   supported `story/issue-N-run-` / `story/N-` branch prefix.
5. Skips ambiguous multiple PRs, forks, another target base, and an existing PR
   that has not been explicitly queued for rework.
6. Chooses rework before new stories, then the lowest issue number in that group.

An empty queue stops before the Claude action accesses its OAuth credential.
The Actions summary reports selection, skipped candidates and holds.
There is no direct issue-number input. For a specific supervised selection,
prepare labels so the intended story is the eligible candidate before dispatch;
restore temporary queue holds afterward.

## 5. Preparation, implementation and publication

The runner checks out trusted `master`, installs Java 25 and supplies disposable
PostgreSQL 17. It validates the turn allowance and runs helper tests before
selection. Database settings are throwaway service values; `GW2_API_KEY` is a
placeholder, never the owner's real key.

A new story branch is:

```text
story/issue-<issue-number>-run-<run-id>-<attempt>
```

For rework, preparation fetches the existing same-repository PR branch and merges
current `origin/master` before starting Claude. This includes prerequisites and
workflow fixes. A conflict stops preparation before model execution and needs
supervised resolution. Do not push competing changes while that run is active.

Claude receives the issue number, instructions, prepared branch and bounded
prompt. It implements that issue, runs narrow checks and writes a temporary
Markdown report at `GW2_RUN_REPORT`. Required database checks use isolated test
schemas, never production or a real user's schema. A placeholder GW2 key cannot
establish live API evidence. Browser acceptance needs its own applicable evidence.

The report records changes, acceptance-criteria evidence, exact checks/results,
changed or weakened assertions and their reasons, pending local checks and gaps.
Only when true, it includes standalone lines:

```text
Local-only checks still needed: none
Unresolved gaps: none
```

Claude leaves committing, pushing and PR publication to the workflow.
On success, the workflow stages its disposable checkout, commits if changed and
pushes the prepared branch. A new story producing no change fails without a PR.
For an existing PR, branch publication explicitly dispatches CI and removes
`needs-rework` from the issue.

A separate evidence step opens a PR to `master`, or comments on the existing PR.
It supplies `Closes #N`, the report, run link and exact-head marker:

```text
<!-- gw2-claude-run-report:v1 head=<40-character-commit-SHA> -->
```

The report file is temporary, not a committed planning artifact. A missing report
is declared explicitly and cannot authorize automatic merge.

## 6. CI and coverage

`ci.yml` runs on pushes, PR events and explicit dispatch. Its concurrency policy
preserves each run instead of canceling it when another commit arrives.

| Check name | Actual work |
|---|---|
| `Workflow checks` | actionlint 1.7.12, native nginx test runtime, Python helper tests |
| `Backend tests (Maven)` | Java 25, isolated PostgreSQL 17, `./mvnw -B -ntp test jacoco:report` |
| `Frontend tests (Vitest)` | Node 22, `npm ci`, Vitest coverage, type-check/build and the controlled browser journey step described below |
| `Coverage KPI report` | Combine available JaCoCo/Vitest reports for the source revision |

The Maven default includes database-backed `*Test` classes using isolated schemas;
`*IT` classes remain excluded by naming convention. Live API, populated-database
equivalence and real-backend/full-stack browser checks still require explicit
commands/evidence under [Test strategy](TEST_STRATEGY.md). Controlled browser
journeys are a separate deterministic CI selection; they do not verify production.

### Controlled desktop journeys: STORY-COV-006

[Issue #243](https://github.com/Nebettadjsr/GW2/issues/243) implements `npm run smoke:ci`
(`node scripts/run-desktop-journeys.mjs`) after the production frontend build,
inside the existing required frontend job. The PR's exact-head CI and required
owner visual acceptance remain separate from local verification. An older green
frontend check is not evidence that this step ran for the current head.

The runner executes a curated sequence of real-browser journeys against fresh
built assets and owned `127.0.0.1` synthetic API origins. It covers public reading,
connection, failures/recovery, account switching, the narrow-screen notice/scrolling
and reservations. It blocks real upstream traffic and needs no real
GW2 key, application backend or database. It does not invoke every legacy
`smoke:*` script, some of which require live or full-stack environments.

The [product viewport policy](DOMAIN_SPEC.md#supported-browser-viewport) owns the threshold and
public-page exemptions. Following the 18 November revision, browser evidence checks the
notice with actual tool content and horizontal scrolling at 390/1023 CSS pixels, no notice
at 1024 and wider, and preserves desktop keyboard/focus,
short-height and zoom checks. Home, guides and legal reading pages remain
responsive below the notice threshold; their narrow checks are still relevant. Earlier
width-blocking/no-request/forced-modal-close assertions are replaced for this owner decision.

The existing locked `playwright-core` CLI installs matching Chromium and Linux
dependencies; the browser run step exports its executable as `GW2_BROWSER_PATH`
in the same shell before `npm run smoke:ci`, rather than depending on a runner's
preinstalled browser. Two child processes run sequentially, each bounded to
180 seconds plus 10 seconds for owned-resource teardown, with no retries. The
CI step has an eight-minute timeout. An occupied
port, unexpected request, stale bundle or missing browser fails verification.
See [Test strategy §12.5](TEST_STRATEGY.md#125-automatic-desktop-user-journeys)
for local/Linux/Windows commands and fixture boundaries.

Named outcomes and bounded failure logs/screenshots/traces are written to
`frontend/quality-reports/desktop-journeys/`, overridable by `GW2_SMOKE_OUTPUT`,
and uploaded as `desktop-browser-journeys` with `if: always()` and seven-day
retention. A journey failure fails the required frontend job. These results do
not alter Vitest/JaCoCo percentages or prove production latency. Record the exact
revision and actual run; local verification does not imply a merge or deployment.

Artifacts are `java-coverage`, `frontend-coverage` and combined
`coverage-reports`. The combined report supplies raw sources, module/package
breakdowns and revision. The long-term 90% line/branch target is **warning-only**:
a green KPI job does not mean every package meets it. Missing reports are
unavailable. Aggregates weight measured line/branch counts, not language
percentages. [Quality metrics](QUALITY_METRICS.md) owns dated measurements.

Run narrow local checks for the affected behavior; CI provides full default
regression evidence. Improve useful boundary/regression assertions rather than
changing source exclusions or padding coverage with implementation duplicates.

## 7. Native Codex review and merge

The native Codex GitHub integration reviews PRs using `AGENTS.md`. Its
installation/configuration is external to these workflows. Review is neither
a planning invocation nor another Claude run. The supervisor checks the issue,
diff, test changes, CI and required local acceptance.

`review-merge.yml` handles CI completion for `story/issue-` branches and
created/edited native review-summary comments from
`chatgpt-codex-connector[bot]`. It checks out the trusted default branch without
persisted credentials; it does not execute PR code with the write-capable token.

Automatic merge requires:

- An open, non-draft, same-repository PR targeting `master`.
- Its linked open story with eligible title, `auto`, no issue/PR holds and a
  `story/issue-<N>-run-` branch. Continuing another branch format does not make
  that branch eligible for automatic merge.
- A PR body closing exactly that story.
- An implementation report for the current full head SHA with both truthful
  `none` lines. The gate reads the PR-body report or latest matching report
  comment from the repository owner.
- All four required GitHub Actions check names complete and successful for the
  current head. A different app's lookalike check does not qualify.
- The latest native summary contains `codex-review-state:v1` with
  `code_review.commit_sha` equal to the head, `status: completed`, and both
  `has_any_findings` and `has_unresolved_findings` false.
- No changed/previous path caught by the protection rule below.

The actual protected list is `.github/**`, `AGENTS.md`, `CLAUDE.md` and the
reserved legacy root name `GW2 GitHub Flow Migration.md`, including renamed
previous paths. The migration record now lives in the archive; this guard still
contains its old literal. It is not a generic protection of every documentation/instruction file.
Process changes outside that list still require supervisor review; documentation
alone does not expand the automatic guard.

The gate requests a squash merge conditioned on that exact head SHA. GitHub
still enforces repository protection. After confirmed automatic merge, it
closes the story as completed. An interactive supervisor may instead perform
a verified merge through normal GitHub controls; protected-process changes and
other branch formats use that path.

A model summary, eye reaction or old-commit review does not complete current
review. New commits invalidate earlier head-specific report, CI and review
evidence. This gate does not deploy to Oracle.

## 8. Rework, decisions and local acceptance

For an eligible automatic story, the gate adds `needs-rework` when its branch
is behind `master`, required current CI definitively fails/times out, or a
completed current-head Codex review has findings. Stale failures and running
reviews do not requeue newer work. Requeue means the **next explicit dispatch**;
the gate never starts Claude.

A missing report, report gap or pending local-only check blocks merge but does
**not** automatically requeue coding. The supervisor identifies whether the next
action is local verification, a product decision or bounded implementation.
Hold labels suppress automatic requeue and merge. Resolve genuine behavior
questions through `needs-decision` in the live issue/PR instead of creating a
retired-harness decision file.

UI layouts need owner review in the running local app unless the owner already
explicitly approved publication of that concrete scope. Provide the local page
and proposed implementation. Browser automation verifies behavior; it does not
replace visual acceptance. Send screenshots when requested. Keep required
acceptance held. Preserve unrelated edits and the ordinary app; stop only
processes owned by the completed review task.

## 9. Failed runs and recovery

Implementation, branch-publication and PR-publication failures attempt recovery
through `save_claude_recovery.py`. It records:

- `base-commit.txt`: the published base before preparation, or initial new-branch
  base, so already committed work is included.
- `changes.patch`: binary-capable tracked/new/deleted changes since that base,
  including prerequisite merges; new ignored files stay excluded.
- `run-report.md` when present, plus recovery instructions.

The artifact `claude-recovery-<issue>-<attempt>` is retained seven days.
It contains unaccepted, unverified work. Hard cancellation/lost runners can
prevent capture. Publication may partially succeed; inspect the remote branch/PR
before applying anything.

Download into ignored output, read its instructions and apply in an isolated
checkout at its recorded base:

```bash
git switch --detach <base-commit-from-artifact>
git apply --check <path-to-changes.patch>
git apply <path-to-changes.patch>
```

Review recovered changes, merges and tests, reconcile current master/PR state,
then finish verification before publication. Workflow-file changes need a
publisher with workflows permission. No automatic retry or paid API fallback
starts. GitHub **Re-run jobs** uses the original workflow revision; dispatch
a new run on `master` after a workflow fix.

## 10. Subscription, credentials and production release

Claude uses `CLAUDE_CODE_OAUTH_TOKEN` from the owner's subscription.
The job sets `ANTHROPIC_API_KEY` empty. Do not switch to Console/API billing or
enable Usage credits without owner authorization. Shared five-hour/weekly
subscription percentages cannot be enforced by this action; time/turn bounds
do not establish a budget. [Claude setup](CLAUDE_GITHUB_SETUP.md) records secret
names/configuration, never real secret values.

GitHub's job token handles branch operations. `GW2_AUTOMATION_TOKEN` publishes
PRs/reports so their events can start CI/review. Because ordinary push events
created with `GITHUB_TOKEN` are suppressed, rework publication explicitly
dispatches CI. Never put real GW2 keys, deployment keys or tokens in tracked
files, issues, PRs or reports.

Production release is a separate, owner-authorized supervised action:

1. Merge the verified PR, fetch the result and select the exact application
   commit. For a merge commit, check its application tree against the accepted
   candidate rather than assuming different SHAs mean different code.
2. Create a `git archive` of that commit into ignored output. It includes tracked
   content at the commit, without unrelated working-tree edits or ignored secrets.
3. Upload the archive and existing release inputs to Oracle with SCP over SSH.
4. Run the existing release command in the owner's visible SSH terminal. Build
   backend/frontend Docker images on Oracle, replace those app containers and
   retain protected host configuration. No committed GitHub workflow deploys.
5. Verify health, HTTPS, public/protected routes and applicable authentication,
   origin, database-role and port boundaries. The existing host release helper
   attempts to restore previous app images for failures in its trapped update/verification phase; failures before replacement, after trap removal, or a lost host/session need inspection of actual state. Record revision/results in
   release evidence and the current-state document.

Machine-local release helpers/credentials are ignored/private and are not
provisioned by these three GitHub workflows. Deployment inputs/limits belong in
[Current state](CURRENT_STATE_SPEC.md) and [Operations](OPERATIONS_RUNBOOK.md).
Documentation-only merges do not require an app rebuild/deployment.

## 11. Implementation map

| File | What it implements |
|---|---|
| [`claude.yml`](../.github/workflows/claude.yml) | Dispatch, serialized runner, environment, preparation, Claude action, publication, recovery artifact |
| [`select_claude_story.py`](../.github/scripts/select_claude_story.py) | Story IDs/dependencies, eligibility, PR matching and rework priority |
| [`test_select_claude_story.py`](../.github/scripts/test_select_claude_story.py) | Offline selector, merged-dependency, rework and fork checks |
| [`save_claude_recovery.py`](../.github/scripts/save_claude_recovery.py) | Binary patch/base/report capture |
| [`test_save_claude_recovery.py`](../.github/scripts/test_save_claude_recovery.py) | New/binary/deleted/committed recovery and failed publication checks |
| [`ci.yml`](../.github/workflows/ci.yml) | Full default suites, type/build, workflow checks, coverage artifacts and STORY-COV-006's controlled journey integration |
| [`summarize_test_failures.py`](../.github/scripts/summarize_test_failures.py) | Backend JUnit failure annotations |
| [`publish_coverage_kpi.py`](../.github/scripts/publish_coverage_kpi.py) | Weighted measured coverage and warning-only report |
| [`review-merge.yml`](../.github/workflows/review-merge.yml) | Trusted-default-branch review/CI event gate |
| [`merge_ready_pr.py`](../.github/scripts/merge_ready_pr.py) | Exact-head eligibility, report, checks, review, protected paths, rework labels and merge |
| [`test_merge_ready_pr.py`](../.github/scripts/test_merge_ready_pr.py) | Stale review/check/report, holds, requeue, protected rename and merge/close checks |
| [`pull_request_template.md`](../.github/pull_request_template.md) | Contributor acceptance, checks and assertion-change report structure |
| [`AGENTS.md`](../AGENTS.md), [`CLAUDE.md`](../CLAUDE.md), [`agent/`](../agent/README.md) | Current role, product, review and authorization contracts |

Historical decisions and harness artifacts are in [the archive](archive/README.md).
The executable pre-migration workflow is recoverable from Git tag
`pre-github-flow`. Those records explain history; they do not select or advance
today's GitHub work.
