# Claude GitHub Action setup and limits

The repository's `.github/workflows/claude.yml` uses the owner's Claude Pro
subscription. It runs only when **Nebettadjsr** selects **Actions → Run next
ready story with Claude → Run workflow**, leaves the branch set to **master**,
and clicks **Run workflow**. No issue number is required. Each click authorizes
one run. Issue labels, comments, pushes, and schedules do not start Claude.
There is no time-of-day gate; the owner can choose when to spend usage.

The button checks open GitHub stories. A story labeled `auto` is eligible when
its listed dependencies are closed and it has no `pairing`, `needs-decision` or
`blocked` label. An open PR is selected only when Codex has marked its issue
`needs-rework`; such PRs take priority over new stories. Otherwise the oldest
eligible issue is selected. The workflow reports the choice or the reason
nothing is ready in the Actions run summary. With no ready story, it stops
before accessing the Claude token. The `auto` label means a planning session
has prepared the story for unattended implementation; `pairing` means local
supervision is required. After a rework run publishes its branch, the workflow
clears `needs-rework` while CI and Codex review the new commit. It does not
merge or automatically retry a PR.

To inspect the queue before pressing the button, use GitHub Issues filtered by
[`auto`](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Aauto),
[`pairing`](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Apairing),
or [`needs-decision`](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Aneeds-decision).
The Actions run summary shows the selected issue and why any earlier `auto`
issues were skipped.

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
