# Claude GitHub Action setup and limits

The repository's `.github/workflows/claude.yml` uses the owner's Claude Pro
subscription. It runs only when **Nebettadjsr** selects **Actions → Claude
implementation → Run workflow**, leaves the branch set to **master**, enters one
open story issue number, and clicks **Run workflow**. Each click authorizes one
run for that issue. Issue labels, comments, pushes, and schedules do not start
Claude. There is no time-of-day gate; the owner can choose when to spend usage.

For an issue with an existing open story PR, another manual run continues its
branch. Otherwise, the run creates a story branch and PR. The workflow does not
merge the PR or automatically retry on CI or review feedback. Codex reviews the
PR separately, and the owner can inspect the result before starting another run.

## One-time setup

1. Install the [Claude GitHub App](https://github.com/apps/claude) on **Nebettadjsr/GW2**.
2. In a normal PowerShell terminal, run `claude auth status` to confirm
   `subscriptionType: "pro"` and `authMethod: "claude.ai"`. Run
   `claude setup-token` and copy the generated token privately.
3. In **Settings → Secrets and variables → Actions → New repository secret**,
   create `CLAUDE_CODE_OAUTH_TOKEN` with that token as its value.
4. Create a fine-grained GitHub personal access token restricted to
   **Nebettadjsr/GW2**, with **Pull requests** read/write permission. Store it
   as a second Actions repository secret named `GW2_AUTOMATION_TOKEN`. The
   workflow uses it only to open a PR so that PR CI runs automatically.

Never paste token values in an issue, PR, chat, or committed file.

## Usage limits

One run at a time is allowed. Each run has at most **12 Claude turns** and a
**45-minute** job timeout. These are bounds per run, not token or subscription
budget limits. GitHub's `claude setup-token` credential cannot read the
subscription's usage percentages on a clean runner, so this workflow cannot
enforce a five-hour or weekly percentage threshold. Anthropic's own plan limits
are the final stop. A single manually started run may still consume substantial
usage; check Claude **Settings → Usage** or `/usage` locally before starting.

Keep **Usage credits** disabled if the intent is to remain strictly within the
Pro subscription allowance. Claude's five-hour and weekly limits are shared
with Claude Chat and local Claude Code. Reaching a plan limit pauses work when
credits are disabled.
