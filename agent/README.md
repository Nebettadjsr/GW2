# Agent development

[Development workflow](../docs/DEVELOPMENT_WORKFLOW.md) is the detailed reference:
issue preparation, agent handoffs, explicit Claude dispatch, branch/PR publication,
CI, exact-head Codex review, recovery, local acceptance and approved release.

## Active files

| File | Responsibility |
|---|---|
| [`BACKLOG.md`](BACKLOG.md) | Draft planning order, current authorization and handoffs |
| [`SUPERVISOR_INSTRUCTIONS.md`](SUPERVISOR_INSTRUCTIONS.md) | Interactive Codex readiness, supervision and acceptance |
| [`PLANNER_INSTRUCTIONS.md`](PLANNER_INSTRUCTIONS.md) | Explicitly assigned Codex planning |
| [`../CLAUDE.md`](../CLAUDE.md) | Programmer instructions |
| [`../AGENTS.md`](../AGENTS.md) | Shared rules and native Codex PR-review guidance |

GitHub issues/PRs and Actions runs own execution state.
[The roadmap](../docs/ROADMAP.md) owns milestone intent. No background planner or
supervisor service is created by these instruction files.

Historical story contracts, user decisions, architecture requests and completed
requests are under [`docs/archive/agent/`](../docs/archive/agent).
The retired executable Python workflow remains recoverable from the
`pre-github-flow` tag. Do not update archive status fields to represent current
GitHub progress.
