# Agent development

[AGENTS.md](../AGENTS.md) contains the shared binding instructions and the documentation map.
[Development workflow](DEVELOPMENT_WORKFLOW.md) is the process reference, from request to merged result.
[Comparison with the reference workflow](VIDEO_COMPARISON.md) records what this setup took from the
workflow it was modelled on, how it implemented it, and what is still open.

## Roles

A role is a set of responsibilities, not a model. Which model or session fills it can change without
changing the contract.

| Role | Does | Works on | Contract |
|---|---|---|---|
| Owner | Sets goals, decides product and budget questions, accepts new UI layouts, authorizes releases | everything | [AGENTS.md](../AGENTS.md) |
| Supervisor | Prepares and releases stories, dispatches runs, merges, triages harness feedback and drift findings, corrects the harness | the harness and the process; the application only on request | [SUPERVISOR_INSTRUCTIONS.md](SUPERVISOR_INSTRUCTIONS.md) |
| Planner | Proposes bounded stories with acceptance criteria; the supervisor may take the role when asked | the application and its documentation, never the harness | [PLANNER_INSTRUCTIONS.md](PLANNER_INSTRUCTIONS.md) |
| Programmer | Implements one story with its tests and a report, in GitHub Actions | the application and its documentation, never the harness | [CLAUDE.md](../CLAUDE.md) |
| Reviewer | Reviews every PR at its exact head commit | read-only | review rules in [AGENTS.md](../AGENTS.md) |

Current assignment: the supervisor is an interactive Claude Code session with the owner, the programmer
is Claude Code in GitHub Actions (`claude.yml`), and the reviewer is the native Codex PR review. The
planner role is assigned explicitly per task.

## Files in this folder

| File | Responsibility |
|---|---|
| [`DEVELOPMENT_WORKFLOW.md`](DEVELOPMENT_WORKFLOW.md) | The implemented process: release, programmer run, CI, review, merge, rework, harness boundary, drift check, recovery, deployment |
| [`SUPERVISOR_INSTRUCTIONS.md`](SUPERVISOR_INSTRUCTIONS.md) | Readiness, release, supervised merge, harness stewardship, deployment care |
| [`PLANNER_INSTRUCTIONS.md`](PLANNER_INSTRUCTIONS.md) | Story planning method and handoff |
| [`VIDEO_COMPARISON.md`](VIDEO_COMPARISON.md) | Reference workflow versus this implementation, and the open items |

The programmer's instructions are [`../CLAUDE.md`](../CLAUDE.md); the shared rules and the native
review guidance are [`../AGENTS.md`](../AGENTS.md).

## The harness boundary

The harness (this folder, `.github/`, `tasks/`, `.claude/`, `AGENTS.md`, `CLAUDE.md`, the rule
documents and the quality-gate configuration) belongs to the owner and the supervisor. The programmer
and the planner work only on the application and never change it; they send proposals as
`## Harness feedback` ([boundary](../AGENTS.md#scope-and-the-harness-boundary)).

GitHub issues, PRs and Actions runs own execution state. Open owner goals are kept in the pinned backlog
issue (#398). Historical story contracts, user decisions and architecture requests are under
[`docs/archive/agent/`](../docs/archive/agent); the retired executable Python workflow is recoverable from
the `pre-github-flow` tag. Do not update archive status fields to represent current progress.
