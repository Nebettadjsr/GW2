# Claude GitHub configuration

For the complete selection, implementation, review, rework and release process,
use [Development workflow](DEVELOPMENT_WORKFLOW.md). This page owns setup and
usage configuration for the existing integration.

## Integrations and credentials

1. Install the [Claude GitHub App](https://github.com/apps/claude) for
   `Nebettadjsr/GW2`.
2. In the owner's normal terminal, run `claude auth status` to confirm the
   subscription-backed `claude.ai` login. Run `claude setup-token` and copy the
   generated token privately.
3. Store it under **Settings > Secrets and variables > Actions** as repository
   secret `CLAUDE_CODE_OAUTH_TOKEN`.
4. Configure a fine-grained GitHub token restricted to this repository with
   **Pull requests: read/write**, stored as `GW2_AUTOMATION_TOKEN`. It publishes
   PRs/report comments whose events can start CI/review. Workflow-file publication
   may require additional workflows permission; denied publication is captured
   by the recovery path.
5. Enable native Codex PR review separately. `AGENTS.md` supplies review rules.
   The Claude workflow does not install or start a Codex planning service.

Tracked files contain secret names only. Keep values out of tracked files,
reports, PRs, issues and chat. Deployment credentials are separate from the
disposable CI database settings.

## Run configuration

The only implementation trigger is explicit dispatch of `claude.yml` on
`master`, by `Nebettadjsr`. Use **Actions > Run next ready story with Claude >
Run workflow**, or an authorized GitHub API/CLI equivalent. Each run selects
one eligible issue; an empty queue does not invoke Claude.

| Setting | Current implementation |
|---|---|
| Credential | `CLAUDE_CODE_OAUTH_TOKEN` |
| Paid API fallback | Disabled: job sets `ANTHROPIC_API_KEY` empty |
| Turn allowance | 40 default; repository variable `CLAUDE_MAX_TURNS` accepts 1-100 |
| Job timeout | 45 minutes |
| Parallel implementation | One concurrency group; active run is not canceled |
| Runtime | Java 25, disposable PostgreSQL 17, placeholder GW2 key |
| Recovery artifact retention | Seven days |

These controls do not enforce remaining five-hour/weekly subscription usage.
Usage is shared with other Claude sessions. Check the owner's Usage page or
local `/usage` before a long session. Keep Usage credits disabled when execution
must remain within the subscription; do not change to API billing without
authorization.

After changing a workflow, dispatch a fresh run on `master`. GitHub **Re-run
jobs** repeats the original workflow revision. Recovery artifacts contain
unverified work, not an accepted implementation.

## Queue and results

- [Ready automatic issues](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Aauto)
- [Supervised pairing issues](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Apairing)
- [Open product decisions](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Aneeds-decision)
- [Implementation runs](https://github.com/Nebettadjsr/GW2/actions/workflows/claude.yml)

The Actions summary explains selection/skips; issue/PR evidence records contract,
checks and review. Report gaps or pending local checks block merge but do not
automatically requeue Claude. The detailed workflow lists the actual conditions
that add `needs-rework`.
