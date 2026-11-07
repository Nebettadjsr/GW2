# Agent roles and planning

Current roles: interactive Codex supervises, Codex on GitHub plans, and Claude programs. Contracts: [supervisor](SUPERVISOR_INSTRUCTIONS.md), [planner](PLANNER_INSTRUCTIONS.md), and [programmer](../CLAUDE.md). The current [backlog](BACKLOG.md) records draft order and activation status; the [roadmap](../docs/ROADMAP.md) defines milestones. These files configure responsibilities, not a running scheduler or a new GitHub integration.

## Historical planning records

The files under `stories/`, `product-owner-requests/`, `architect-requests/` and `user-decisions/` are historical story contracts, Product Owner requests,
architecture questions, and user decisions. Some open GitHub issues link to their
original story files, so those source links remain available. GitHub issues and
pull requests are the live work queue; do not edit historical status fields to
represent current progress.

The retired Python workflow service, role contracts, logs, QA plans, and Grafana
configuration remain recoverable from the pushed `pre-github-flow` tag. Current
agent instructions are in the repository root `AGENTS.md` and `CLAUDE.md`.
