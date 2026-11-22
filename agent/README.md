# Agent development

[AGENTS.md](../AGENTS.md) contains the shared binding instructions and documentation map.
[Development workflow](DEVELOPMENT_WORKFLOW.md) is the process reference:
issue preparation, agent handoffs, explicit Claude dispatch, branch/PR publication,
CI, exact-head Codex review, recovery, local acceptance and approved release.

## Active files

| File | Responsibility |
|---|---|
| [`SUPERVISOR_INSTRUCTIONS.md`](SUPERVISOR_INSTRUCTIONS.md) | Interactive Codex readiness, supervision and acceptance |
| [`PLANNER_INSTRUCTIONS.md`](PLANNER_INSTRUCTIONS.md) | Explicitly assigned Codex planning |
| [`../CLAUDE.md`](../CLAUDE.md) | Programmer instructions |
| [`../AGENTS.md`](../AGENTS.md) | Shared rules and native Codex PR-review guidance |

The harness (this folder, `.github/`, `tasks/`, `AGENTS.md`, `CLAUDE.md`, the quality-gate
configuration) belongs to the owner and supervisor sessions. The programmer and the planner work only
on the application and never change it; they send proposals as `## Harness feedback`
([boundary](../AGENTS.md#scope-and-the-harness-boundary)).

GitHub issues/PRs and Actions runs own execution state. No background planner or
supervisor service is created by these instruction files.

Historical story contracts, user decisions, architecture requests and completed
requests are under [`docs/archive/agent/`](../docs/archive/agent).
The retired executable Python workflow remains recoverable from the
`pre-github-flow` tag. Do not update archive status fields to represent current
GitHub progress.
