# Agent development workflow

[AGENTS.md](../AGENTS.md) contains the binding shared rules and documentation map;
[role instructions](README.md) define planner, supervisor and programmer responsibilities.
GitHub issues, PRs and Actions hold plans, execution state and evidence. The optional
pinned backlog issue (#398) contains only unfinished longer-term owner goals.

## Prepare and release work

The supervisor checks the owner's request, existing issues and relevant source/docs before
releasing one bounded, dependency-complete issue. Include goal, constraints, dependencies,
acceptance criteria and meaningful verification. Genuine product/budget choices use
`needs-decision`. A draft proposal does not authorize implementation.

The automated selector requires a title such as `STORY-WEB-123: Describe the outcome`.
List prerequisite story IDs under `## Dependencies`; keep unrelated prose under another
heading. A bare `#123` link is not a selector dependency. The legacy
`Migration status: ... Dependencies: ...` format is also recognized.

| Label | Effect |
| --- | --- |
| `auto` | Eligible for an explicit implementation dispatch and conditional automatic merge. |
| `pairing`, `needs-decision`, `blocked` | Hold selection, automatic merge and automatic requeue. |
| `needs-rework` | Continue the existing eligible PR before selecting new work; blocks merge. |

Selection reads issue holds; merge/requeue also checks PR holds. Draft PRs are held.
Legacy `Migration status: BLOCKED` remains a hold. Dependencies must be closed completed
with their associated PR merged into this repository's `master`. The selector rejects
ambiguous PR matches, forks and other bases. It chooses rework first, then lowest issue number.

## Run Claude

Use **Actions → Run next ready story with Claude → Run workflow**, on `master`, or an
authorized equivalent API/CLI dispatch:

```sh
gh workflow run claude.yml --repo Nebettadjsr/GW2 --ref master
```

Only explicit dispatch starts implementation. Labels, comments, merges and pushes do not.
The workflow requires actor `Nebettadjsr` and `refs/heads/master`, serializes attempts,
has a 45-minute timeout and defaults to 40 turns (`CLAUDE_MAX_TURNS` accepts 1–100).
One dispatch makes one attempt. There is no background planner/supervisor loop.

The workflow prepares `story/issue-<N>-run-<run-id>-<attempt>`. Rework merges current
`origin/master` into the existing branch first; conflicts stop before Claude. Avoid competing
pushes during an attempt. Java 25, disposable PostgreSQL 17 and a placeholder GW2 key are
provided. Never use the owner database or real account credentials for routine checks.

Claude reads the issue and shared instructions, implements its scope and runs focused checks.
Normally it authors tests with code; an explicit independent-QA assignment takes precedence.
It writes the temporary `GW2_RUN_REPORT` with an `## Acceptance criteria evidence` table that maps
every acceptance criterion to the concrete test or evidence, plus actual results, assertion changes/reasons,
remaining local checks and gaps. Only when true, include these standalone lines:

```text
Local-only checks still needed: none
Unresolved gaps: none
```

The workflow commits/pushes and publishes the PR/report with `Closes #N` and an exact-head
marker `<!-- gw2-claude-run-report:v1 head=<40-character-SHA> -->`. It clears `needs-rework`
after publication. An unchanged new story fails rather than publishing an empty PR.
The published body ends with a `## Run metrics` section (turns used of allowed, wall-clock time,
tokens, API-equivalent cost) rendered by
[`summarize_claude_run.py`](../.github/scripts/summarize_claude_run.py) from the action's execution
log; missing fields show `n/a` and never fail the run. Use these numbers to judge whether changes to
the harness make runs cheaper or more reliable.
For rework the workflow first writes a context file (`collect_rework_context.py`: failing checks of
the current head with their annotation messages, unresolved review threads) that Claude must read.
A rework run that changes nothing (not even the prepared merge of `master`) fails visibly and keeps
`needs-rework`; it does not publish a report or clear the label. A PR that is only behind `master` is
published with the merge.
A rework is pushed with the automation token so the normal `pull_request` CI runs without an
approval hold; if that push is refused the workflow falls back to the workflow token and dispatches CI.
Rework publication via the workflow token explicitly dispatches CI because `GITHUB_TOKEN` pushes suppress events.

## CI, review and acceptance

[`ci.yml`](../.github/workflows/ci.yml) runs default backend/frontend tests, type/build,
workflow checks, the curated controlled browser journeys and a container smoke
(`scripts/compose-smoke.sh`: both images built as Compose builds them, stack started, offline
health/boundary checks, restart on the managed schema). It publishes JaCoCo/Vitest
coverage and browser artifacts. Commands, layers and limits belong in
[Test strategy](../docs/TEST_STRATEGY.md); actual run counts stay in CI/PRs.

[`review-merge.yml`](../.github/workflows/review-merge.yml) checks eligible CI/review events
using trusted default-branch code. Automatic squash merge requires:

- An open, non-draft, same-repository PR to `master`, on `story/issue-<N>-run-...`, closing
  its linked open `auto` story, with no issue/PR hold.
- A current-head implementation report with both truthful `none` lines above, an
  `## Acceptance criteria evidence` table (criterion, concrete test or evidence, result) and a
  `## Harness feedback` section, and without `## Out-of-scope changes discarded`.
- All five Actions checks successful at the exact head: `Backend tests (Maven)`,
  `Frontend tests (Vitest)`, `Workflow checks`, `Coverage KPI report`,
  `Container smoke (Compose)`.
- The latest native Codex summary's `code_review` has that `commit_sha`, `status: completed`,
  `has_any_findings: false` and `has_unresolved_findings: false`.
- No supervised-only changed/previous path: the harness paths and dependency manifests listed in
  `harness_paths.py` (`.github/`, `agent/`, `tasks/`, `.claude/`, `AGENTS.md`, `CLAUDE.md`, the
  quality-gate configuration, `backend/pom.xml`, `frontend/package*.json`). These require supervised merging.

The merge is conditioned on that SHA; GitHub protection still applies. A new head needs
fresh checks/report/review. The supervisor also verifies required local evidence and obtains
owner acceptance of new UI layouts unless that concrete publication is already approved.
Browser automation does not replace visual acceptance. Other PR formats are supervised.

Current definitive CI failures, completed review findings or a behind-base eligible PR can
queue `needs-rework` for the next explicit dispatch. Running/stale results do not. A missing
report or pending local check holds merge without automatically starting another coding run.

## Harness boundary and feedback

[`harness_paths.py`](../.github/scripts/harness_paths.py) lists the harness paths once; the merge gate and
the post-run step both use it. After Claude finishes, `discard_out_of_scope.py` (trusted copy) restores
every off-limits path to the state the run started from and lists the files in the PR body under
`## Out-of-scope changes discarded`; such a PR is never merged automatically. The run also denies
reading `agent/` and `.github/` and editing harness paths. Dependency manifests are not discarded
(a story may need a dependency) but never merge automatically.

The report must contain `## Harness feedback` (or `none`). A non-empty section, or discarded
changes, opens an issue labelled `harness-feedback` for the supervisor. The supervisor corrects the
harness in its own pull request when the same deviation occurs twice, a single deviation is costly,
review findings recur or a drift finding confirms it; deterministic checks are preferred over
prose, and obsolete rules are removed.

## Documentation drift check

[`docs-drift.yml`](../.github/workflows/docs-drift.yml) compares the active documents with the code.
It is read-only: it commits nothing and starts no story. It runs nightly (02:00 UTC) but works only
when master has commits since the last successful check, so a quiet day costs one cheap job. The
first run is a baseline check. A run counts as checked only if its model step completed and wrote a
valid report (`STATUS: drift` or `STATUS: clean`); otherwise the run fails and the next night repeats it. `Run workflow` offers `force` (check although nothing changed) and
`test_run` (do not count the run as a check, create no issue). A forced check without new commits
runs in baseline mode, so it rechecks the whole active documentation.

The check has two parts. [`check_doc_references.py`](../.github/scripts/check_doc_references.py)
deterministically lists links and backticked repository paths that point at nothing. A Claude step
then verifies the documents that own the changed areas (baseline: their key factual claims) and
reports only drift it confirmed with code evidence, marking product or architecture questions
`owner-decision`. Each run keeps its report, scope and path check as a 30-day artifact (`docs-drift-<run id>`) and prints
the result text in its log, so even a clean result can be audited through its `Checked` list. Findings go into one open issue labelled `docs-drift` (a new comment when one is
already open); a clean result writes only the run summary. Drift issues are not `auto` stories:
the owner or supervisor decides each finding, then fixes the document or opens a story.

## Failed attempts

[`save_claude_recovery.py`](../.github/scripts/save_claude_recovery.py) attempts to save a
seven-day recovery artifact containing `base-commit.txt`, binary-capable `changes.patch`,
the available report and recovery instructions. Hard cancellation can prevent capture.
Publication can partly succeed: inspect the remote PR/branch first.

Download into ignored output, read the instructions, then inspect/apply in an isolated checkout:

```sh
git switch --detach <recorded-base-commit>
git apply --check <path-to-changes.patch>
git apply <path-to-changes.patch>
```

Recovered work still needs review and checks. Re-running an old job uses its old workflow;
dispatch a new run on `master` after a workflow fix. No automatic paid fallback is authorized.

## Credentials and deployment

Repository secrets `CLAUDE_CODE_OAUTH_TOKEN` and `GW2_AUTOMATION_TOKEN` supply the subscription
credential and PR publisher respectively; `GITHUB_TOKEN` handles branch operations.
`ANTHROPIC_API_KEY` is explicitly empty. Do not enable API billing/Usage credits without owner
authorization. Turn/time limits do not enforce subscription quotas. Never record secret values
in tracked files, reports or issues.

A merge does not deploy. For an authorized release, select the reviewed commit, create a
`git archive`, upload it over SSH/SCP and run the existing release helper in the owner's
visible terminal. Preserve the protected production environment/HTTPS Compose override,
verify health and applicable public/authentication boundaries, and record results in GitHub.
The ignored private deployment guide holds machine-specific commands/credentials.
[Current state](../docs/CURRENT_STATE_SPEC.md) records lasting deployed facts and host inputs.
Documentation-only changes need no application rebuild.

Implementation entry points: [`claude.yml`](../.github/workflows/claude.yml),
[`select_claude_story.py`](../.github/scripts/select_claude_story.py),
[`merge_ready_pr.py`](../.github/scripts/merge_ready_pr.py) and the workflows linked above.