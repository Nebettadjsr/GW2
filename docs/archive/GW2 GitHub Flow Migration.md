GW2 · Workflow migration · Approach 2

# From the Python pipeline to a GitHub pull-request flow

**Implementation update (25 Oct 2026):** GitHub issues/PRs/CI are the live workflow;
the Python service, role contracts and runtime CI job are retired and recoverable
on the pushed `pre-github-flow` tag. One manual dispatch permits one bounded Claude
story; there is no schedule or time-of-day gate. An authorized active Codex session
can dispatch successive runs and supervise review/local verification. This does
not create an unattended background planner or a subscription-percentage gate.

The JavaFX infrastructure pilot (#9 / PR #28) merged. The temporary unavailable-state
pilot (#7) was superseded by the complete credential-based browser recovery
(#13 / PR #61), following the owner's request to prioritize the permanent usable
path; it is not claimed as a merged second pilot. Subsequent scoped persistence,
HTTP/browser, measurement and container stories have exercised the delivery flow.
The implemented review gate uses a third
workflow, `review-merge.yml`, because native Codex posts its review state as a
PR comment and CI completion can happen before or after that comment. The gate
checks both against the current PR head before an eligible auto story merges.
The original phases below remain historical migration rationale, not a current
queue. Completion evidence and pilot substitution are recorded in issue #17.
Current phase/requirements live in `docs/ROADMAP.md`, selected work in GitHub issues,
and key/cache policy in UD-018/UD-016. Planning remains an interactive/supervised
Codex responsibility; implementation and independent native Codex PR review are
distinct executions. Architecture proposals can be separately reviewed before a
dependent implementation, as in #75/ADR-011. Normal code and tests are written
together. Do not restore the old Python role pipeline to represent this flow.

Each story becomes a GitHub issue. Each attempt becomes a pull request worked on by one Claude Code session. CI and one reviewer are the gates. The custom service, its state files and its role boundaries go away.

## Why

In the week up to 24 Oct 2026 the pipeline delivered one story (STORY-UI-004). That took about a dozen coder, QA and evaluator rounds and four manual restarts. Six harness defects were fixed along the way, and none of them was about GW2. Every one sat on a boundary the harness created itself: the coder may not touch tests, QA may not touch production code, two reviewers merge their verdicts, and publication is assembled path by path from a shared dirty worktree.

## Target flow

PO request / idea └─ planning session (you + Codex, interactive) ──► GitHub issue \[label: auto | pairing\] │ auto ─► Claude Code GitHub Action ─► branch story/\<n>-slug ─► PR pairing ─► you + Claude Code locally ─► same branch / PR │ CI (ci.yml: Maven + Vitest) + native Codex GitHub review │ changes requested ─► "@claude fix …" on the PR (same branch) green + reviewed ─► supervised merge until a reliable review gate is proven

State lives only in GitHub: issue open or closed, PR open or merged, CI green or red. A failed attempt is a closed PR, not a jammed queue.

## Old → new

| Today | After |
| --- | --- |
| `workflow_service/`, supervisor, `tasks.json`, process registry | GitHub Actions and issue/PR state |
| `BACKLOG.md`, `CURRENT_STORY.md`, story Status sections | Issues, labels, milestone `milestone-06`, optional project board |
| Planner, architect, QA, coder, evaluator roles (Codex + Claude) | One implementing agent plus one PR reviewer; planning in an interactive session |
| QA-owned tests, protected hashes, test reverts, QA repair rounds | The agent writes code and tests together. The reviewer and CI judge the diff, and weakened assertions need a stated reason in the PR |
| Path-scoped publication from a shared worktree | One clean branch per story. The PR contains exactly what it changed |
| User interventions and decisions (`UI-*`, `UD-*`) | A question as an issue or PR comment and the label `needs-decision`. You answer in the thread |
| Claude budget windows and 5-hour gates | Run only when triggered (label or comment), plus an optional nightly cron with a concurrency limit of 1 |
| Grafana dashboard | The repository's Issues and Pull requests pages |
| `agent-runtime` CI job | Removed together with the harness |

**Kept unchanged:** `docs/` (domain spec, architecture, test strategy, UX guidelines), `ci.yml` backend and frontend jobs, the story files as issue bodies, and the product code.

## Phases

Phase 0\
day 0

### Freeze and save the work in flight

- Halt the service after STORY-SYNC-007. A watcher is armed to pause it when SYNC-007 finishes or blocks.
- Tag the current master as `pre-github-flow`, so the whole harness stays recoverable.
- The `GW2-agent` worktree holds uncommitted work of the blocked STORY-API-010 and STORY-APP-015, plus new story files, ADR-008 to ADR-010, UD-017 and the PO requests. Commit each story's work to its own branch (`wip/api-010`, `wip/app-015`), commit the planning files to master, and push.
- Stop the workflow service and the launch supervisor, remove the `shell:startup` shortcut, and stop the Grafana container.

**Done when:** nothing runs on a schedule, `git status` is clean in both checkouts, and every piece of unfinished work is on a pushed branch.

Phase 1\
days 1–2

### GitHub foundation

- Create labels `auto`, `pairing`, `needs-decision` and `po-request`, and a milestone `milestone-06`.
- Turn every To Do and Blocked story into an issue, with the story file's Goal, Acceptance Criteria, Constraints and Definition of Done as the body. A short script against the GitHub API does this in one pass.
- Protect `master`: changes only through pull requests, with the Maven and Vitest checks required.
- Add a PR template: linked issue, acceptance criteria checklist, tests added or changed (with the reason for any removed or weakened assertion), local-only checks run.
- Shrink `CLAUDE.md` to the product rules: project, documentation map, domain and architecture rules, testing rules and coding guidelines. Remove the role, story-system and harness sections.

**Done when:** the queue is visible as issues, and a hand-made PR is blocked until CI is green.

Phase 2\
days 2–4

### Agent runner and pilot

- Add `.github/workflows/claude.yml` with the Claude Code GitHub Action, triggered when an issue gets the `auto` label or a PR comment mentions `@claude`. Authenticate with a token from your Claude subscription (`claude setup-token`) stored as a repository secret. Check the action's current documentation for the exact input names when setting it up.
- Give it a bounded tool set and turn limit. Its prompt: implement the issue on a branch, write or adjust the tests, run the narrow Maven or Vitest command, and open a PR that fills in the template.
- Pilot with **STORY-INFRA-005** (remove the JavaFX build and test remnants). It is small, mechanical and fully verifiable by CI. Then run **STORY-WEB-036** (account-data-unavailable state).

**Done when:** both pilot PRs are merged with no manual change to the agent's process. Fixing the code by asking on the PR is allowed.

Phase 3\
week 2

### One reviewer

- Connect the repository to Codex and enable native automatic GitHub reviews for all PRs, triggered on every push so a repair receives a fresh review. Use the ChatGPT plan's review allowance. Root `AGENTS.md` gives Codex concise checks for acceptance criteria, test changes, and domain boundaries. No OpenAI API key is needed for this route.
- Keep the separate automatic Security Review off during the initial pilots; it consumes Codex allowance or credits, and normal Code Review can already identify security findings. Revisit it after measuring pilot usage and review quality.
- For blocking findings, trigger Claude again on the same PR branch with `@claude`. After three unsuccessful rounds, label the PR `needs-decision` for a human decision.
- Supervise merges while testing whether native review exposes a reliable machine-readable completion and blocking signal. Enable auto-merge only after that gate is proven; CI remains required throughout.

**Done when:** a PR receives a native Codex review without a manual trigger, and a deliberately broken PR is held for correction by CI or the supervised review process.

Phase 4\
week 2–3

### Planning and pairing

- Planning becomes a short interactive Codex session, for example weekly: read open `po-request` issues and the roadmap, then create or split issues and label them `auto` or `pairing`. The connected GitHub tool writes the issues; no model API key is required.
- Do the `pairing` stories (account isolation, sessions, real-database work) in focused local sessions, on the same branch and PR model.
- Local-only checks (real-database `*IT`, browser smoke scripts, live GW2 API) are listed in the PR template and run in the pairing session. GitHub-hosted runners cannot run them.

Phase 5\
week 3

### Decommission the harness

- Delete `workflow_service/`, `agent/runtime/`, `agent/docker/grafana/`, the role instruction files, `agent/runtime/artifacts/` and the `agent-runtime` CI job. The `pre-github-flow` tag keeps them recoverable.
- Keep the story archive and the resolved decisions as read-only history under `docs/history/`, or leave them on the tag.
- Prune `tasks/lessons.md` to the lessons about the product and its tests. Remove the `GW2-agent` and `GW2-docs-review` worktrees.

**Done when:** the repository holds the product, its docs and two workflows (`ci.yml`, `claude.yml`).

## Current queue, routed

| Story | Route | Note |
| --- | --- | --- |
| STORY-INFRA-005 · JavaFX build remnants | auto | Pilot 1 |
| STORY-WEB-036 · account-data-unavailable state | auto | Pilot 2 |
| STORY-SYNC-007 · global operations after schema reset | auto | Finishes in the old pipeline if it can. Otherwise its branch becomes a PR |
| STORY-PERF-005 · resource benchmarks | pairing | Needs real measurements on this machine |
| STORY-APP-015 · account-scoped repositories | pairing | Continue from `wip/app-015`. The issue carries tests T1–T4 from UI-009, and the API adapter seam for T3 is decided in the session |
| STORY-API-010 · validated account access | pairing | Continue from `wip/api-010` |
| STORY-SYNC-006, STORY-API-011, STORY-WEB-035 | pairing | Security-relevant account chain. WEB-035 delivers the first usable key-based flow |
| STORY-PERF-006 | auto | After PERF-005 |
| New · Ecto: remove dead code, document browser calculation | auto | Low priority. Docs and two file deletions |

## Decisions for you

### Where the agent runs: GitHub-hosted runners (decided)

Simple and isolated. A self-hosted runner on this PC would give the agent the local database and browser, but runs unattended code on your machine. Revisit only if `auto` stories keep needing local checks.

### Which model reviews: Codex (decided)

A second model catches different mistakes, and it is already set up.

### Ectoplasm salvage: the browser calculation is the implementation (decided)

The deleted Java calculator only served the old desktop screen. The web page `EctoSalvageScreen.vue` calculates in the browser from item metadata, Trading Post prices and Luck, and that stays the one way to do it. Becomes one issue: delete the dead `frontend/src/api/ectoApi.ts` and `frontend/src/ecto/useEctoSalvage.ts` (they call a `/api/ecto/salvage` route that never existed), and update `DOMAIN_SPEC.md` and the architecture docs to describe the browser implementation as correct, including it as a documented exception to "business rules live in the backend".

## How we know it works

- **Throughput:** stories merged per week. Baseline: 1 in the week up to 24 Oct 2026.
- **Interventions:** times you had to act per merged story, counting PR comments that fix process rather than product. Target: at most one.
- **Harness work:** hours spent on the workflow instead of the product. Target: none after Phase 5.
- Review after the two pilots and again after two weeks. If `auto` PRs mostly need rework, shift more stories to `pairing` rather than rebuilding automation.

## Risks

- **Test quality without QA-first.** The reviewer prompt checks the test diff explicitly, and removed or weakened assertions must be justified in the PR.
- **Subscription limits in Actions.** One run at a time, triggered rather than continuous. A run that hits the limit fails visibly on the PR, and you re-trigger it later.
- **Local-only checks.** They are not run automatically. That is why database and browser-heavy stories are labelled `pairing`.
