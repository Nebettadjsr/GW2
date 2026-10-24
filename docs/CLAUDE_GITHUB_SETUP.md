# Claude GitHub Action setup and limits

The repository's `.github/workflows/claude.yml` uses Claude Code with the
repository owner's Claude subscription. Its trigger is an `auto` label added by
`Nebettadjsr` or a PR comment by that account containing `@claude`. The action
is not active until the secrets below are configured and a trigger occurs.

## One-time setup

1. Install the [Claude GitHub App](https://github.com/apps/claude) on **Nebettadjsr/GW2**.
2. Open a normal PowerShell terminal on the computer where `claude` is installed.
   Run `claude auth status` to confirm `subscriptionType: "pro"` and
   `authMethod: "claude.ai"`, then run `claude setup-token`. This is a terminal
   command, not a `/` command in Claude Chat. Complete any browser sign-in it
   opens and copy the generated token privately.
3. In the repository, open **Settings → Secrets and variables → Actions →
   Secrets → New repository secret**. Create `CLAUDE_CODE_OAUTH_TOKEN` with that
   token as its value. Never paste the value in an issue, PR, chat or committed
   file.
4. Create a fine-grained GitHub personal access token at
   [GitHub token settings](https://github.com/settings/personal-access-tokens/new).
   Restrict it to the **Nebettadjsr/GW2** repository. Grant repository
   **Pull requests** read/write permission (GitHub also adds Metadata read).
   Store it as a second Actions repository secret named `GW2_AUTOMATION_TOKEN`.
   The workflow uses it only to open the PR after Claude has pushed a branch;
   GitHub's default workflow token would not reliably trigger the CI follow-up.

Do not enable Claude **Usage credits** if the intent is to remain strictly
within the Pro subscription allowance. Check that setting under Claude
**Settings → Usage**. Claude's five-hour and weekly limits are shared with
Claude Chat, so repository automation and personal use draw from the same pool.

## Start window and usage limits

The workflow accepts only the owner's triggers and runs one invocation at a
time. Before installing or invoking the Claude action, it checks the Berlin
start window: **17:35 through 06:35**. The window uses the Europe/Berlin time zone,
including daylight saving changes. Outside the window the workflow does not
access the OAuth secret or start Claude.

The `--max-turns 30` argument and 90-minute job timeout bound each attempt, but
they are not token limits. The `claude setup-token` credential can make model
requests but cannot read the account's `/usage` percentages on a clean runner,
so the GitHub workflow cannot enforce the former 90% five-hour or
daily-unlocking weekly budget checks. Anthropic's own five-hour and weekly
limits are the final stop. With Usage credits disabled, reaching a limit pauses
work instead of charging for overage. During supervised operation, Codex can
check `/usage` on this computer's signed-in Claude CLI before triggering work.

For now, a trigger outside the window is skipped. It is visible in the Actions
run log and must be retriggered after the gate opens.
Scheduled resumption should be added only after a live action run confirms the
OAuth and PR publication path; the two supervised local pilots did not exercise
the GitHub-hosted action.
