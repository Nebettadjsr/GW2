# Claude GitHub Action setup and limits

The repository's `.github/workflows/claude.yml` uses the owner's Claude Pro
subscription. It runs only when **Nebettadjsr** selects **Actions → Run next
ready story with Claude → Run workflow**, leaves the branch set to **master**,
and clicks **Run workflow**. No issue number is required. Each click authorizes
one run. Issue labels, comments, pushes, and schedules do not start Claude.
There is no time-of-day gate; the owner can choose when to spend usage.
After a workflow fix, start a new run with this button on `master`.
GitHub's **Re-run jobs** repeats the original run's commit and workflow version.

The button checks open GitHub stories. A story labeled `auto` is eligible when
its listed dependencies are closed with linked PRs merged into `master`, and
it has no `pairing`, `needs-decision` or `blocked` label. An open PR is selected only when
Codex has marked its issue
`needs-rework`; such PRs take priority over new stories. Otherwise the oldest
eligible issue is selected. The workflow reports the choice or the reason
nothing is ready in the Actions run summary. With no ready story, it stops
before accessing the Claude token. The `auto` label means a planning session
has prepared the story for unattended implementation; `pairing` means local
supervision is required. After a rework run publishes its branch, the workflow
clears `needs-rework` while CI and Codex review the new commit. It does not
automatically retry a PR. The GitHub review/CI gate restores `needs-rework` when
the current commit has a completed Codex finding, failed CI, or a run report
listing unresolved gaps or remaining checks. The next button press resumes that
PR. Old-commit failures cannot requeue newer work. Java 25 and disposable
PostgreSQL are supplied so Claude can run the required isolated database tests.
Claude writes a temporary run report with
its changes, checks, and gaps. The workflow places it in a new PR or adds it
as a comment on a rework PR; a missing report is shown explicitly for review.

The separate `review-merge.yml` workflow may squash-merge a Claude story PR
after the current head has green Maven, Vitest, and coverage checks and the
native Codex review summary reports a completed review with no findings for
that same head. It requires a linked open `auto` story, a same-repository Claude
story branch, a run report, and explicit statements that no local-only checks
or unresolved gaps remain. `pairing`, `needs-decision`, `blocked`, and
`needs-rework` hold the merge. Other PRs still need a supervised merge. A
new commit invalidates the prior review and CI evidence until both repeat.

To inspect the queue before pressing the button, use GitHub Issues filtered by
[`auto`](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Aauto),
[`pairing`](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Apairing),
or [`needs-decision`](https://github.com/Nebettadjsr/GW2/issues?q=is%3Aissue+is%3Aopen+label%3Aneeds-decision).
The Actions run summary shows the selected issue and why any earlier `auto`
issues were skipped.

Planning sessions prepare and label the milestone's dependent stories in advance.
The selector automatically unlocks each one when its prerequisites have merged;
no per-click planning message is needed. The gate maintains review/rework state
without invoking a model or starting Claude. It cannot invent new stories or make
Product Owner decisions: a new planning session is needed when the prepared
milestone queue is exhausted or a material requirements question arises.

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
   workflow uses it to open a PR or comment with run evidence so that PR CI
   and review run automatically.

Never paste token values in an issue, PR, chat, or committed file.

## Usage limits

One run at a time is allowed. Each run defaults to **40 Claude turns** and a
**45-minute** job timeout. Change the `CLAUDE_MAX_TURNS` repository **variable**
(Settings → Secrets and variables → Actions → Variables) to an integer from 1
to 100 to override the turn allowance. This is not a secret. These are bounds per run, not token or subscription
budget limits. GitHub's `claude setup-token` credential cannot read the
subscription's usage percentages on a clean runner, so this workflow cannot
enforce a five-hour or weekly percentage threshold. Anthropic's own plan limits
are the final stop. A single manually started run may still consume substantial
usage; check Claude **Settings → Usage** or `/usage` locally before starting.

Keep **Usage credits** disabled if the intent is to remain strictly within the
Pro subscription allowance. Claude's five-hour and weekly limits are shared
with Claude Chat and local Claude Code. Reaching a plan limit pauses work when
credits are disabled.

If Claude fails or exhausts its turns, the run remains failed and no code or
acceptance report is published to the PR. The run's **Artifacts** section keeps
a `claude-recovery-<issue>-<attempt>` artifact for seven days with a binary Git
patch (including new non-ignored files), its base commit, and the run report if
Claude wrote one. A planning session can recover those edits before the next
manually authorized attempt. No automatic retry starts, and a hard cancellation
or lost runner can prevent recovery capture.
