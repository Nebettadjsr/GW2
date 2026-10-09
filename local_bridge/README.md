# n8n agent bridge for Claude Code and Codex

The local bridge lets n8n run one task at a time in Claude Code or Codex CLI from this GW2 repository. It saves task results and sends completion callbacks to the waiting n8n execution.

## Tell real executions from simulations and replays

n8n pinned data replaces a node's normal execution with saved sample output. An execution can therefore say “success” while its HTTP node never contacted the bridge. The activation workflow's earlier execution **38** was such a fixture run: its trigger, story fetch and activation nodes were pinned, and its `STORY-FIXTURE-001` result did not activate anything. Current workflow definitions have no pins on their HTTP or agent nodes. In executions 44 and 45, n8n records the empty Manual Trigger output under `pinData`; the bridge HTTP nodes themselves ran normally.

When reviewing an execution, inspect `resultData.pinData` and `resultData.runData` together. A pin on an HTTP or agent node means that node was bypassed, even when the execution status is `success`. A pin on the empty Manual Trigger output alone does not bypass downstream calls. A manual execution is not automatically simulated: confirm that the HTTP nodes appear in `runData` as executed nodes and that no HTTP node appears in `pinData`. For story activation, `activation_status: activated` means the bridge changed state; `already_active` means the request was an idempotent no-op. For task submissions, the bridge response reports `submission_disposition: created` or `idempotent_replay`; the result itself is loaded from the persisted bridge task record. A replay returns the existing task and never starts another process.

The workflow's fixed prompts and the parent test's supplied inputs are intentional test inputs, not mocked node results. They still make real bridge calls when their HTTP/sub-workflow nodes execute.

## Prerequisites

- Windows, Docker Desktop and the local n8n container running.
- Python 3.12 available as `python` in PowerShell.
- Node.js at `C:\Program Files\nodejs\node.exe`.
- Claude Code at `C:\Users\Administrator\.local\bin\claude.EXE`.
- Codex CLI installed for this Windows account, and already signed in. Check with `codex --version` and `codex login status`.
- This repository at `C:\Users\Administrator\IdeaProjects\GW2`.
- The Windows-side Docker interface address, currently `172.29.240.1`.

## Start or restart the bridge

Open PowerShell and paste this block. It creates the bearer token the first time and keeps it outside the repository.

```powershell
Set-Location C:\Users\Administrator\IdeaProjects\GW2

$secretDir = Join-Path $env:USERPROFILE '.gw2-claude-bridge'
New-Item -ItemType Directory -Force -Path $secretDir | Out-Null
$tokenFile = Join-Path $secretDir 'token.txt'
if (-not (Test-Path $tokenFile)) {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    $generator.GetBytes($bytes)
    $generator.Dispose()
    [IO.File]::WriteAllText($tokenFile, [Convert]::ToBase64String($bytes), [Text.Encoding]::ASCII)
}

$env:GW2_BRIDGE_TOKEN = (Get-Content -Raw $tokenFile).Trim()
$env:GW2_BRIDGE_HOST = '172.29.240.1'
$env:GW2_BRIDGE_STATE = Join-Path $secretDir 'tasks.json'
$env:GW2_N8N_CALLBACK_ORIGIN = 'http://localhost:5678'
python .\local_bridge\bridge.py
```

Keep that PowerShell window open. To restart after closing it, open a new PowerShell window and paste the same block. The saved token and task history are reused. The bridge invokes Codex with its installed CLI, existing account login and local configuration, in non-interactive read-only mode with this repository as its working root.

After changing `local_bridge/bridge.py`, stop the running Python bridge process and run the same startup block again so the process loads the updated code.

If n8n uses a different local port or loopback name, set `GW2_N8N_CALLBACK_ORIGIN` to the origin shown in its Wait resume URL and restart the bridge.

### If the Windows interface address changes

In this setup, `host.docker.internal` resolves to `192.168.65.254`, which is not a Windows listening address. Use the Windows address on the Docker/virtual interface instead. Docker Desktop or Windows may change it after a restart.

```powershell
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
    Format-Table InterfaceAlias, IPAddress, PrefixLength
```

Identify the interface address reachable from the n8n container, update `$env:GW2_BRIDGE_HOST` in the startup block, and restart the bridge. Check connectivity from the container:

```powershell
docker exec n8n node -e "fetch('http://172.29.240.1:8765/health').then(r=>r.text()).then(console.log).catch(e=>{console.error(e);process.exit(1)})"
```

Replace the address in the check if it changed. A working check prints `{"status":"ok"}`.

## Configure the n8n credential

Both test workflows use the existing **Header Auth account** credential. Confirm it has:

- **Name:** `Authorization`
- **Value:** `Bearer ` followed by the contents of `%USERPROFILE%\.gw2-claude-bridge\token.txt`

Do not put the token in workflow fields or source files. The submit node in each workflow must use this credential.

## Run either test workflow

1. Start the bridge and confirm the n8n container can reach `/health`.
2. In n8n, open **GW2 - Bridge Connection Test** for Claude or **GW2 - Codex Connection Test** for Codex. Both are inactive and unpublished.
3. Click **Test workflow** / **Execute workflow** to start the Manual Trigger.
4. The workflow submits a fixed harmless prompt and waits up to 30 minutes for the bridge callback.
5. Check the final output for task ID, status, response, exit code and error information.

The Codex test asks Codex to return one fixed sentence. The bridge selects the agent from the `agent` request field; omitted `agent` continues to use Claude for compatibility. The bridge owns executable paths, command arguments and working directory. Requests cannot override these settings.

The submit node uses an idempotency key based on the n8n execution ID. Retrying that same submission does not start a second process. Starting a new manual execution has a new key and submits a new task.

## Reuse the agent executor from another workflow

**GW2 - Execute Agent** is the shared child workflow. In a parent, add an **Execute Sub-workflow** node targeting its n8n workflow ID and keep **Wait for Sub-Workflow Completion** enabled. Map one input item with `agent` (`claude` or `codex`), `prompt`, optional `role`, and a stable, unique `request_id`. The parent test workflow shows this wiring with a harmless Codex prompt.

The child uses `request_id` directly as the bridge Idempotency-Key. Reuse the same ID and same agent/prompt when retrying one logical request; choose a new ID only for a deliberately new task. The bridge returns the original task and never launches a second process for that ID. Reusing the ID from a new child execution registers its new Wait callback URL. If the original task already reached a terminal state, the bridge sends its persisted result to the replacement callback. Reusing the same ID with a different agent or prompt returns HTTP 409.

The returned item contains `request_id`, `task_id`, `agent`, `role`, `status`, `exit_code`, `result`, and `error`. A `callback_wait_expired` status is a timeout, not an agent failure. Retry the parent with the same `request_id` to attach a new callback; do not use a new ID. If the result is `interrupted`, inspect the saved task through `GET /tasks/{task_id}` before deciding what to do. The bridge will not restart an interrupted task automatically.

n8n saves the paused Wait execution data to its database and gives each execution a unique resume URL. With n8n's execution database retained, a normal restart can resume that same Wait execution when its callback arrives. The parent Execute Sub-workflow node waits for the child when its completion option is enabled. If an execution is stopped, deleted, or otherwise replaced, retry the parent with the same `request_id`: the bridge replaces the callback URL on the existing task and either delivers the eventual completion or replays the saved terminal result. It never relaunches the agent for that ID.

Callback delivery retries are bounded. If the replacement execution also expires or the bridge cannot reach n8n after exhausting retries, use `GET /tasks/{task_id}` to inspect the saved status and result. A callback already in flight to an obsolete Wait URL cannot be withdrawn, but that URL belongs only to the old execution; after replacement, the bridge stops retrying it and sends to the new URL. A bridge restart while an agent process is running marks the task `interrupted` because its outcome is uncertain. Recovery reports that status and does not relaunch the process.

The test parent uses a fixed request ID to make retries safe. To intentionally run a fresh test task, change that ID in **Provide Test Inputs** before executing it again.

## Select the next development story

`GET /stories/next` reads the canonical `agent/stories/BACKLOG.md` To Do order and applies the existing Python selector's status and dependency checks. It returns the first eligible story, including its ID, filename, title and Markdown content, or `queue_empty: true` with a `NO_WORK`, `NEEDS_USER` or `BLOCKED` decision. The endpoint is read-only: it does not activate a story, edit the backlog or write the selector result artifact. It uses the same bearer-token Header Auth credential as the task endpoints.

After updating `bridge.py`, restart an already-running bridge so it loads the `/stories/next` route. To try it in n8n, open **GW2 - Select Next Story** and click **Execute workflow**. The workflow only makes this read-only GET request; it does not start Claude, Codex or the production orchestrator.

The endpoint adapter tests use temporary backlog and story files, so they do not alter the real development queue:

```powershell
python -m unittest discover -s local_bridge -p "test_story_selection.py" -v
```

## Finalize a story after CI

`GET /stories/active/finalization-readiness` verifies the existing implementation, QA plan and protected tests, latest APPROVE review, COMPLETE Evaluator result, and the application-scoped PASSED final-CI result for the published HEAD commit. `POST /story-finalizations` accepts the story ID and readiness contract hash. It repeats the checks under the shared story lifecycle lock, marks the story DONE, moves its backlog entry, clears `CURRENT_STORY.md`, and creates one local finalization commit. It never pushes or activates the next story.

The n8n workflow **GW2 - Finalize Story** is manual, inactive and unpublished. Restart the bridge after updating `bridge.py`. Before the first real run, inspect readiness without changing anything:

```powershell
$token = (Get-Content -Raw (Join-Path $env:USERPROFILE '.gw2-claude-bridge\token.txt')).Trim()
Invoke-RestMethod -Uri 'http://172.29.240.1:8765/stories/active/finalization-readiness' `
    -Headers @{ Authorization = "Bearer $token" }
```

Proceed only if `finalization_permitted` is true and the story is the one intended. Then execute the n8n workflow once; it fetches readiness again and submits the guarded transition. Verify the resulting local commit and the Done/empty-pointer state before publishing that commit separately. Do not run this workflow while the old orchestrator is running. If a lifecycle write or local commit is interrupted, rerunning the same readiness/submission is safe only when the bridge identifies the exact known transition prefix; unrelated staged files or other repository inconsistencies return a recovery diagnostic and require manual inspection.

Finalization fixture tests use temporary Git repositories and never change the live backlog:

```powershell
python -m unittest local_bridge.test_finalization -v
```

## Safely activate a selected story

The existing runtime's rules remain authoritative: selection walks `## To Do` in backlog order; it skips missing, archived, completed, unfinished, superseded, blocked, or dependency-incomplete stories. `## Active` holds at most one story and must agree with `CURRENT_STORY.md`. Activation moves the existing backlog row and its summary from To Do to Active. Completion later moves it to Done and clears the pointer. `ATTEMPT_STATE.json` records the orchestrator's later execution/evaluation/publication phase; it is not an activation journal, so the bridge refuses to start another story while that record is present.

`POST /stories/activate` accepts `story_id` and `filename` from the selection response, using the same bearer authentication:

```json
{"story_id":"STORY-WEB-021","filename":"STORY-WEB-021-example.md"}
```

The bridge rechecks that the story is still the first eligible To Do item, including its status and dependencies. A stale selection, another active story, or an unfinished `ATTEMPT_STATE.json` is rejected. Repeating activation for the already active same story returns `already_active` without writing again. Activation does not start an agent.

The bridge and the existing orchestrator activation function share a cross-process file lock. The orchestrator now reconciles activation state at the start of each scheduling cycle. If activation is interrupted after writing `CURRENT_STORY.md` but before moving the backlog entry, retry the same activation or restart the updated orchestrator; it recognizes that exact pointer-first state and completes the backlog move. Other mismatches stop with a manual-reconciliation error. Do not run an already-started pre-update orchestrator process during initial rollout; restart it so it uses the shared lock and reconciliation code.

**First controlled activation test:**

1. Restart the bridge after updating `bridge.py` and confirm its health endpoint responds.
2. Stop any already-running old orchestrator process. Confirm `agent/CURRENT_STORY.md` is empty, BACKLOG.md has no Active entry, and there is no unfinished `ATTEMPT_STATE.json`.
3. In n8n, open **GW2 - Select and Activate Story** and execute it once. It fetches the next story, activates it only if it is still eligible, and displays the active story and content. This changes the real backlog and current-story pointer but does not launch Claude, Codex or the orchestrator.
4. For the first test, review the selected story before executing. Do not retry with a different story if the activation response reports a conflict; fetch the queue again and inspect the active state.

Activation tests use temporary repositories and never change the development queue:

```powershell
python -m unittest discover -s local_bridge -p "test_story_activation.py" -v
```

After changing `bridge.py`, restart the running bridge so its HTTP responses include the provenance fields. Before treating an n8n activation result as real, confirm the execution has no `pinData` for its HTTP or activation nodes, then verify `CURRENT_STORY.md` and the backlog independently. A bridge process that has not been restarted returns no `operation_source`; n8n labels that output `bridge_marker_unavailable`, and the execution's node data must be used to verify whether the HTTP call actually ran.

## Recover a missed callback

The bridge saves the result before sending its callback. If n8n times out or a callback cannot be confirmed, use the task ID from **Submit Claude Task** or **Submit Codex Task** and check the saved task:

```powershell
$token = (Get-Content -Raw (Join-Path $env:USERPROFILE '.gw2-claude-bridge\token.txt')).Trim()
$taskId = 'PASTE-TASK-ID-HERE'
Invoke-RestMethod -Uri "http://172.29.240.1:8765/tasks/$taskId" `
    -Headers @{ Authorization = "Bearer $token" }
```

Use the current bridge address if it changed. For automatic callback recovery, retry the parent with the same request ID and unchanged agent/prompt. If you only need to inspect a result, this endpoint is read-only. If the bridge restarts while a task is running, it marks the task `interrupted` and does not launch it again. A callback retry never restarts either agent.

## Stop the bridge

In the PowerShell window running it, press **Ctrl+C**.

## Troubleshooting

- **Bridge connection refused:** Confirm the bridge PowerShell window is running, verify the Docker interface address and allow TCP port `8765` through Windows Firewall for Docker Desktop.
- **401 Unauthorized:** Check that **Header Auth account** is `Authorization` with `Bearer ` followed by the saved token, and that it is attached to the submit node.
- **Codex task fails to start:** Check `codex --version`, `codex login status`, Node.js at `C:\Program Files\nodejs\node.exe`, and the CLI installation under `%APPDATA%\npm\node_modules\@openai\codex\bin\codex.js`.
- **Callback URL rejected:** Confirm `GW2_N8N_CALLBACK_ORIGIN` matches the local n8n origin in the Wait resume URL, then restart the bridge.
- **`delivery_unconfirmed` or Wait timed out:** The task result is still saved. Retry the parent with the same `request_id` to register a replacement callback, or inspect it with `GET /tasks/{task_id}` above. A timeout does not stop a running agent; never use a new request ID to recover the same task.

## Tests

The tests use fake Claude and Codex executables and a local callback receiver. They do not launch real agent sessions.

```powershell
python -m unittest discover -s local_bridge -p "test_*.py" -v
```

## Publish an implementation before final CI

Every implementation task records a `publication_metadata` object in its persisted bridge task before Claude starts. Schema version 2 records the task and story IDs, baseline `HEAD` and matching origin branch tip (the bridge refuses to start Claude when they differ), porcelain status entries, hashes for pre-existing dirty paths, and staged/working-tree diff hashes. It also records the implementation contract, active story, referenced documents, and protected QA test paths as scope context. After the runner and file-protection restorations finish, it records `changed_paths`, a SHA-256 for each changed file, ambiguous paths that were already dirty at baseline, protected/lifecycle paths restored by the harness, and final HEAD/status fingerprints.

The implementation-owned file list is the deterministic task delta from that captured baseline: only paths clean at task start and changed by task completion are candidates. Changes to a path already dirty at baseline are marked ambiguous and cannot be published. As separate, explicit story-contract artifacts, publication may include only the validated current QA plan and its prepared tests when their paths and hashes match the implementation-time QA scope. The active pointer and backlog are left unstaged; unchanged, valid lifecycle edits already present at implementation start are preserved for the existing finalization transition. Other unrelated staged/dirty files, bridge/n8n infrastructure, logs, and build/temp output stop publication. Publication revalidates the stored file hashes, current QA protected-test state, the APPROVE review and COMPLETE Evaluator result, active lifecycle consistency, baseline parent/commit subject/path set, and unrelated staged/dirty files. It reuses the existing `_story_commit_message` convention and path-scoped Git helper; it never stages the whole worktree.

`GET /stories/active/publication-readiness` is read-only. Authenticated `POST /implementation-publications` takes `story_id` and `publication_contract_sha256`, with `Idempotency-Key: gw2-publish-<story-id>-<fingerprint>`. It records the publication attempt in the bridge task store, commits only the validated paths, pushes the current branch, verifies `origin/<branch>` resolves to that exact SHA, and returns the SHA for Final CI. A completed replay returns the saved outcome. An interrupted attempt is recovered only if the exact baseline-parent commit, expected story subject, changed path set, and remote SHA can all be proved; otherwise it stops for inspection.

The inactive n8n child workflow **GW2 - Publish Implementation** calls these operations. The parent **GW2 - Development Pipeline** routes Evaluator COMPLETE through publication before Final CI. The parent passes the published SHA into **GW2 - Run Final CI**, which rejects a different HEAD before submitting CI.

## Inspect active-story implementation readiness

`GET /stories/active/implementation-contract` returns the current pointer and backlog check, story text, QA status, prerequisites, referenced decision/document information, and (only when ready) the existing Claude prompt with `implementation_contract()` appended. It is read-only: it does not run QA or write runtime artifacts. `READY` and an explicit `NO_TESTS_NEEDED` outcome can permit implementation; missing QA, `NEEDS_USER`, stale plans, changed protected tests, unresolved decisions, or inconsistent active state cannot.

QA plans now record a fingerprint of the story's requirements. Harness-owned Status, Result, Blockers and Follow-up sections are excluded, so those bookkeeping changes do not invalidate QA. A new implementation submission must use `POST /implementation-tasks` with the story ID and returned `contract_sha256`; the bridge repeats all readiness checks and launches only its regenerated prompt when the contract is unchanged. A persisted successful implementation blocks another implementation submission so review is the next operation; a later deliberate implementation retry requires a separate gate update. The runner also restores and reports any unauthorized changes to Status, BACKLOG.md or CURRENT_STORY.md. Generic harmless tasks continue to use `POST /tasks`. Both routes retain the same bearer authentication, callback delivery, idempotency key and one-task limit.

To inspect readiness, restart the bridge after updating `bridge.py`, then run **GW2 - Prepare Active Story** in n8n. It only reports the contract; it does not launch QA or Claude. If QA is missing, the follow-on phase must add a separate QA-preparation action using `qa_agent.execute_preparation()` and preserve its existing plan/test protections before an implementation task can be submitted.

If there is no current story, the workflow reports `no_active_story`; use **GW2 - Select and Activate Story** only after reviewing and choosing to activate the selected development story. Activation and readiness inspection are separate operations.

```powershell
python -m unittest discover -s local_bridge -p "test_implementation_contract.py" -v
```

## Prepare QA for the active story

`POST /qa-preparations` runs the repository's existing `qa_agent.execute_preparation()` for the current active story. The bridge fixes the runner to the existing QA Codex configuration; callers can provide only the active story ID, its requirements fingerprint from the implementation contract, and the n8n Wait resume URL. The bridge authenticates the request, checks that the story and requirements are still current, and returns `READY`, `NO_TESTS_NEEDED`, or `NEEDS_USER`. Technical failures have a failed task status and a specific error category. This operation does not launch Claude or change the backlog/current-story pointer.

Open **GW2 - Prepare QA** and execute it manually only when you intend to prepare QA for the current active story. It waits for the persisted task's completion callback, then fetches the implementation contract again. The displayed implementation-permitted value comes from that fresh contract; `NEEDS_USER` and technical failures remain distinct outcomes.

The task and QA plan use the existing bridge task file and QA artifacts. Repeating the same execution key returns the original task. A completed, still-valid persisted plan can be returned after interruption without running Codex again. If the bridge restarts during QA before a valid plan is saved, the outcome is uncertain: the bridge marks the task interrupted and refuses a new request for that story until a maintainer inspects the task, `QA_STATE.json`, plan artifacts and any Codex process. Do not retry with a new key just to restart it. If the n8n callback times out, inspect the task by ID; a callback timeout does not mean QA failed or stopped.

The existing QA guard snapshots repository files, permits only its approved test/fixture writes, restores other detected writes, and rejects the result on a protection violation. It preserves pre-existing maintainer modifications; review any reported protection violation before deliberately retrying QA. No automated QA run against WEB-021 has been performed as part of this integration.

Fixture-only bridge tests (fake Codex responses and a local callback receiver) run with:

```powershell
python -m unittest discover -s local_bridge -p "test_qa_preparation.py" -v
```

## Review an implementation

`GET /stories/active/post-implementation-review-contract` reports whether the active story is independently ready for review. This review gate permits an active story whose Status is `DONE`, provided its active pointer/backlog entry, dependencies, QA plan and protected-test snapshots are valid and the bridge has a successful persisted implementation task. It does not make implementation permissible again.

`POST /qa-reviews` submits the existing read-only `qa_agent.run_conditional_review()` using `story_id`, `story_contract_sha256` from that review contract, and `callback_url`. The bridge revalidates those records and protected-test hashes before and after Codex runs, then records the verdict in the existing QA plan. The bridge uses the existing Codex QA model and read-only sandbox; no evaluator, Claude implementation, story transition, or activation is started.

Open **GW2 - Review Implementation** and run it manually after implementation. The bridge allows one review per persisted implementation task and uses the existing task record, idempotency, and callback delivery. A duplicate submission returns the same result. If the bridge restarts before a review is persisted, the task is marked `interrupted` and will not relaunch Codex; inspect the task and QA plan before any deliberate recovery. If a review was persisted just before interruption, repeating the same request returns that stored verdict. Callback timeouts do not cancel a running review; inspect its task ID instead of submitting another implementation.

The workflow is inactive until manually run. It reports `APPROVE`, `RETRY`, or `NEEDS_USER` separately from technical task status. It does not run the Evaluator, launch Claude, mark a story Done, or activate another story.

Fixture-only post-implementation review tests use a fake Codex response and callback receiver:

```powershell
python -m unittest discover -s local_bridge -p "test_qa_review.py" -v
```
