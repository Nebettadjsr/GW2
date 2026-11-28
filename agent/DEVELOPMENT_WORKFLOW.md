# Agent development workflow

How a change travels from an owner's request to a merged, optionally released result. GitHub issues,
pull requests and Actions hold plans, execution state and evidence; no background planner or
supervisor service exists. [AGENTS.md](../AGENTS.md) has the binding shared rules,
[README](README.md) the roles, and [VIDEO_COMPARISON](VIDEO_COMPARISON.md) how this process relates to
the reference workflow it was modelled on.

```text
owner request ─► planner proposes stories ─► supervisor releases one ─► owner/supervisor dispatches
                                                                              │
   merge (auto or supervised) ◄─ CI + Codex review + merge gate ◄─ PR with report ◄─ programmer run
        │                                                                     ▲
        └─ release (separate, owner-authorized)          rework ──────────────┘ (CI/review findings)
```

## 1. Prepare and release work

The supervisor checks the owner's request, existing issues and the relevant source and documents
before releasing one bounded, dependency-complete issue. A story states goal, constraints,
dependencies, acceptance criteria and a meaningful verification plan. Genuine product or budget choices
use `needs-decision`. A draft proposal does not authorize implementation.

The selector (`select_claude_story.py`) requires a title such as `STORY-WEB-123: Describe the outcome`.
List prerequisite story IDs under `## Dependencies`; keep unrelated prose under another heading. A bare
`#123` link is not a dependency. The legacy `Migration status: ... Dependencies: ...` format is also read.

| Label | Effect |
| --- | --- |
| `auto` | Eligible for an explicit implementation dispatch and conditional automatic merge. |
| `pairing`, `needs-decision`, `blocked` | Hold selection, automatic merge and automatic requeue. |
| `needs-rework` | Continue the existing eligible PR before selecting new work; blocks merge. |
| `harness-feedback` | A proposal about the harness from a run; the supervisor triages it. Never an `auto` story. |
| `docs-drift` | Findings of the nightly documentation check (section 6). Never an `auto` story. |
| `backlog` | The pinned standing list of unfinished owner goals. Not a story. |

Selection reads issue holds; merge and requeue also check PR holds. Draft PRs are held. Dependencies
must be closed as completed with their PR merged into this repository's `master`. The selector rejects
ambiguous PR matches, forks and other bases, and chooses rework first, then the lowest issue number.

Acceptance criteria describe application behavior only. A criterion that requires reading or changing a
harness path cannot be satisfied by the programmer and is rejected or split by the supervisor.

## 2. The programmer run

Start it with **Actions → Run next ready story with Claude → Run workflow** on `master`, or an authorized
equivalent:

```sh
gh workflow run claude.yml --repo Nebettadjsr/GW2 --ref master
```

Only an explicit dispatch starts implementation; labels, comments, merges and pushes do not. The workflow
requires actor `Nebettadjsr` and `refs/heads/master`, serializes attempts, has a 45-minute timeout and a
turn limit (`CLAUDE_MAX_TURNS`, default 40, range 1 to 100). One dispatch makes one attempt.

What `claude.yml` does, in order:

1. Selects the next ready story and prepares `story/issue-<N>-run-<run-id>-<attempt>` (Java 25,
   disposable PostgreSQL 17 and a placeholder GW2 key are provided). Helper scripts run from trusted copies
   made before the agent starts, so the story branch cannot alter them.
2. For rework only: writes a context file with the failing checks of the current head (annotation
   messages) and the unresolved review threads, which the agent must read first (section 4).
3. Runs the programmer (`claude-code-action`) with the application-only scope, an allow list of tools and
   deny rules for harness paths (section 5).
4. Discards any change the agent made under a harness path (section 5).
5. Commits and pushes the branch, then opens the PR or comments on the existing one with the report.
6. Opens a `harness-feedback` issue when the agent reported feedback or tried to change the harness.
7. On failure, saves a seven-day recovery artifact (section 7).

The programmer reads the issue and the shared instructions, implements its scope with tests, and runs
focused checks. It writes the temporary report named by `GW2_RUN_REPORT` with these parts:

- changes, exact checks and results, changed or weakened assertions with reasons;
- `## Acceptance criteria evidence`: a table `| Criterion | Test or evidence | Result |`, one row per
  criterion with the concrete test or evidence (or `none` and why);
- `## Harness feedback`: what in the instructions, rules or tools was wrong, missing or unclear, or `none`;
- the standalone lines `Local-only checks still needed: none` and `Unresolved gaps: none`, only when true.
  Local-only means a check that needs the owner's machine; the complete CI suites are never listed there.

The workflow publishes the PR with `Closes #N` and an exact-head marker
`<!-- gw2-claude-run-report:v1 head=<40-character-SHA> -->`. The body ends with `## Run metrics` (turns,
wall-clock time, tokens, API-equivalent cost) rendered by `summarize_claude_run.py`; missing fields show
`n/a` and never fail a run. An unchanged new story fails instead of opening an empty PR.

## 3. CI, review and merge

[`ci.yml`](../.github/workflows/ci.yml) runs on pushes, pull requests and explicit dispatch:

- **Backend tests (Maven):** the default suites on a `postgres:17` service, plus Checkstyle, Error Prone
  and the ArchUnit rules, and the bounded public API compatibility step.
- **Frontend tests (Vitest):** lint, unit tests with coverage, type check and build, and the curated
  controlled browser journeys (`npm run smoke:ci`).
- **Workflow checks:** actionlint and the helper-script tests.
- **Container smoke (Compose):** both images built as Compose builds them, stack started, offline health
  and boundary checks, restart on the managed schema.
- **Coverage KPI report:** summarizes the product coverage (a warning below 90%, never a failure).

Commands, layers and limits are in [Test strategy](../docs/TEST_STRATEGY.md); run counts stay in CI.
Every PR also receives the native Codex review.

**Automatic merge.** [`review-merge.yml`](../.github/workflows/review-merge.yml) runs the merge gate
(`merge_ready_pr.py`, trusted default-branch code) on CI and review events. A squash merge requires:

- an open, non-draft, same-repository PR to `master` on a `story/issue-<N>-run-...` branch that closes its
  open `auto` story, with no issue or PR hold;
- a current-head report with both truthful `none` lines, a populated criteria table and a
  `## Harness feedback` section, and without `## Out-of-scope changes discarded`;
- all five checks above successful at the exact head;
- the latest Codex summary for that head completed with no findings and none unresolved;
- no supervised-only path in the diff: the harness paths and dependency manifests listed in
  `harness_paths.py`.

The merge is conditioned on the head SHA. A new head needs fresh checks, report and review.

**Supervised merge** (a PR that the gate does not take: harness or manifest changes, unresolved gaps, a
supervisor's own PR). Branch protection requires `Backend tests (Maven)` and `Frontend tests (Vitest)` on
the head, a branch that is up to date with `master`, and resolved conversations; no human review is
required, and administrators are not exempt. The supervisor merges only when all five checks are green on
the current head and the Codex summary for that head has no unresolved finding. Every Codex thread is
answered and resolved (fix first, then reply). A PR that is behind `master` is updated
(`gh pr update-branch`) and the checks are awaited again. Merge with a squash commit titled
`Title (#N)`. Required local-only checks need recorded evidence; green CI does not stand in for them. New
UI layouts need the owner's acceptance in the running local app unless that publication is already
approved; browser automation does not replace it.

## 4. Rework

CI failures, completed review findings or a behind-base eligible PR queue `needs-rework`; a running or
stale result does not. The next explicit dispatch continues the PR before new work: it merges current
`master` into the branch first (a conflict stops before any model use), then runs the agent with the
context file. A rework that changes nothing, not even the prepared merge, fails visibly and keeps
`needs-rework`; it does not publish a report. The rework is pushed with the automation token so the normal
`pull_request` CI runs; if that push is refused the workflow falls back to the workflow token and
dispatches CI explicitly. A missing report or a pending local check holds the merge without starting
another coding run.

## 5. Harness boundary, feedback and stewardship

The harness is everything that steers or checks the agents. `harness_paths.py` lists it once for the
merge gate and the post-run step: `.github/`, `agent/`, `tasks/`, `.claude/`, `AGENTS.md`, `CLAUDE.md`,
the rule documents (coding guidelines, test strategy, frontend UX guidelines) and the quality-gate
configuration. Dependency manifests are supervised-only but editable by a story. The programmer and the
planner work only on the application: the run denies reading `agent/` and `.github/` and editing harness
paths, and `discard_out_of_scope.py` restores any harness path the agent changed to the state the run
started from, listing the files in the PR body under `## Out-of-scope changes discarded`. Such a PR is
never merged automatically.

The ArchUnit store is such a path. A story that fixes a recorded violation makes the test run rewrite the store;
the discard step restores it, so the PR lists it as discarded and is merged by the supervisor, who refreezes
the store on the PR branch (`./mvnw test -Dtest=ArchitectureTest -Darchunit.freeze.refreeze=true`) and checks
that the diff only removes entries or, for the package cycles, adds no dependency edge between packages.

Agents report instead of fixing. A non-empty `## Harness feedback`, or discarded changes, opens an issue
labelled `harness-feedback` even when the run publishes no PR. The supervisor (or the owner) corrects the
harness, in its own pull request with the evidence, when the same deviation occurs twice, one deviation is
costly, review findings recur or a drift finding confirms it. A deterministic check is preferred over a
sentence of prose, and rules that stopped earning their place are removed.

## 6. Documentation drift check

[`docs-drift.yml`](../.github/workflows/docs-drift.yml) compares the documents that describe the
application with the code. It commits nothing and starts no story. It runs nightly (02:00 UTC, in practice
a few hours later) but works only when `master` has commits since the last successful check, so a quiet
day costs one cheap job; the first run is a baseline check. `Run workflow` offers `force` and `test_run`
(not counted, no issue). A forced check without new commits runs in baseline mode.

Every counted run checks all of these, one parallel job per group (twelve, at most four at a time), so coverage
does not depend on how often work happens. The groups are listed in
[`drift-groups.json`](../.github/drift-groups.json): the domain specification in five parts, the crafting
references, the UI specification in two parts, `ARCHITECTURE`, the current-state documents in two parts
(facts, workflows) and `KNOWN_PROBLEMS` (entries that are already fixed). Behavior claims are checked against
the code and the tests that pin them; a rule that code implements but no test pins is listed as an untested
rule, not as drift. The harness documents are not part of the model comparison: judging them is the
supervisor's job, and `check_doc_references.py` still lists links and repository paths in all active documents
that point at nothing.

Each finding carries its git history: the last change of the document and of the cited code, and the
commits and PR numbers that touched the code since. The supervisor uses it to decide which side is stale
(`doc-wrong`, `code-wrong` or `owner-decision`) and can look at the issues that touched the subject. A run
counts as checked only if every group completed and wrote a valid report (`STATUS: drift` or
`STATUS: clean`); otherwise it fails and the next night repeats it. Each group keeps its report, including
the `Checked` and `Not checked` lists, as a 30-day artifact, and the log prints the combined result. Findings
go into one open `docs-drift` issue (a comment when one is open); the owner or supervisor decides each
finding, then fixes the document or opens a story.

## 7. Failed attempts

[`save_claude_recovery.py`](../.github/scripts/save_claude_recovery.py) attempts to save a seven-day
recovery artifact with `base-commit.txt`, a binary-capable `changes.patch`, the available report and
instructions. Hard cancellation can prevent capture, and publication can partly succeed: inspect the
remote PR and branch first. Download into ignored output, then inspect and apply in an isolated checkout:

```sh
git switch --detach <recorded-base-commit>
git apply --check <path-to-changes.patch>
git apply <path-to-changes.patch>
```

Recovered work still needs review and checks. Re-running an old job uses its old workflow; dispatch a new
run on `master` after a workflow fix. No automatic paid fallback is authorized.

## 8. Credentials and deployment

Repository secrets `CLAUDE_CODE_OAUTH_TOKEN` and `GW2_AUTOMATION_TOKEN` supply the subscription credential
and the PR publisher; `GITHUB_TOKEN` handles branch operations. `ANTHROPIC_API_KEY` is explicitly empty. Do
not enable API billing or usage credits without owner authorization. Turn and time limits do not enforce
subscription quotas. Never record secret values in tracked files, reports or issues.

A merge does not deploy. For an authorized release, select the reviewed commit, create a `git archive`,
upload it over SSH/SCP and run the existing release helper in the owner's visible terminal. Preserve the
protected production environment and HTTPS Compose override, verify health and the applicable public and
authentication boundaries, and record the results in GitHub. The ignored private deployment guide holds
machine-specific commands and credentials. [Current state](../docs/CURRENT_STATE_SPEC.md) records lasting
deployed facts and host inputs. Documentation-only changes need no application rebuild.

Entry points: [`claude.yml`](../.github/workflows/claude.yml),
[`select_claude_story.py`](../.github/scripts/select_claude_story.py),
[`merge_ready_pr.py`](../.github/scripts/merge_ready_pr.py), [`harness_paths.py`](../.github/scripts/harness_paths.py)
and the workflows linked above.
