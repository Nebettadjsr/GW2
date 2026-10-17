# Workflow evolution

This history follows repository commits and the recorded usage investigation. It covers changes supported by those records, beginning with the September 20 runtime reorganization.

## September 20: Runtime organization and recovery

Commit [`1b97d16`](https://github.com/Nebettadjsr/GW2/commit/1b97d16) reorganized the runtime into packages for story orchestration, runners, evaluation, human requests, and support code. It also added recovery handling for interrupted stories and separated generated artifacts from maintained source and tests.

That split remains visible in the [runtime layout](../runtime/README.md). Model outputs under `runtime/artifacts/` are disposable run state. Stories, persistent QA plans, and human decisions have their own tracked locations. Daily logs preserve operational history separately from generated results.

## September 26: Planning context and repeated input

The [usage investigation](../runtime/reports/2026-09-26-planner-usage.md) examined recorded planner sessions, logs, and runtime code. One supplied prompt contained 139,095 characters of backlog text, about 63% of its 220,114 characters. Completed and archived story narratives accounted for most of that backlog content.

The report also found evidence of a repeated planning pass after a formatting-only input rewrite. It distinguishes that evidence from what could not be recovered: the earlier cache digest and the process responsible for the rewrite were unavailable.

The resulting changes normalized BOMs and line endings for planning fingerprints and supplied compact indexes for completed stories and resolved requests. The [runtime guide](../runtime/README.md#supplied-context-is-indexed-not-narrated) records a measured planning-prompt reduction from 221,331 to about 86,600 characters on the September 26 repository state. Those are character counts, not a measured billing reduction or a guarantee for future runs.

## Backlog updates: One writer and a parseable index

The module notes in [`backlog_writer.py`](../runtime/core/backlog_writer.py) describe a concrete failure: the harness, context renderer, and Planner used different backlog formats. A completed entry remained in `Active`, and supplementary prose became detached from its entry.

The writer now formats canonical entries and validates the whole index before writing. Lifecycle transitions remain harness-owned. This addresses a parsing and ownership problem: an entry the selector cannot parse may disappear from the executable queue even while its text remains in the file.

The October 17 commit [`585c2ec`](https://github.com/Nebettadjsr/GW2/commit/585c2ec) added an index of open User Decisions and interventions under `User`. That section helps humans find pending questions without putting those requests into the story queue.

## Evaluation: From implementation reports to repository evidence

The [runtime guide's evaluation history](../runtime/README.md#evaluation-codex-read-only) records an earlier evaluator using `hermes3:8b` through Ollama. It judged story text against Claude's written report and could return empty retry verdicts. The evaluator was a separate model, but its evidence came from the Implementer's account.

The current Evaluator uses Codex in a read-only sandbox to inspect the working tree. It checks both the explicit criteria and whether the implementation achieves the story's purpose. Python validates and normalizes the verdict; unmet work can turn a reported `COMPLETE` into `RETRY`. The change gives evaluation repository evidence, but it does not prove that every defect will be found.

## October 3: Persistent QA before implementation

Commit [`0ef2391`](https://github.com/Nebettadjsr/GW2/commit/0ef2391) added the QA gate, persistent plans, prepared acceptance tests, and protection around coding attempts.

QA can write tests before implementation. The harness saves the validated plan and restores protected tests and plan contents if the coding agent changes them. Evaluation can request an independent read-only QA review. A missing product rule becomes a User Decision and blocks the affected story. See the [QA plan contract](../qa-plans/README.md).

## October 6–10: n8n takes over the outer loop

The migration began in [`c253215`](https://github.com/Nebettadjsr/GW2/commit/c253215). Commit [`0b491ea`](https://github.com/Nebettadjsr/GW2/commit/0b491ea) integrated CI and finalization; [`e941c78`](https://github.com/Nebettadjsr/GW2/commit/e941c78) added Claude usage budgets and scheduling. Commit [`aed2822`](https://github.com/Nebettadjsr/GW2/commit/aed2822) removed the retired standalone orchestrator loop.

n8n now requests one bridge step at a time. The bridge stores the next phase and reuses runtime helpers for planning, QA, coding, evaluation, publication, CI, and finalization. Recovery repeats an interrupted step. The [bridge guide](../../local_bridge/README.md) documents how to re-attach a workflow, handle capacity waits, and recover state manually.

## October 17: Distinguishing commit claims from implemented behavior

Commit [`25ff822`](https://github.com/Nebettadjsr/GW2/commit/25ff822) describes JSON backlog actions, automatic agent branching, and a documentation-sync phase in its message. The later documentation repeated those descriptions as current behavior.

The source in this documentation branch does not implement that full account. `cycle.py` has no documentation-sync phase, `Bridge.next_step()` does not switch or pull branches, and `git_sync.py` pushes the branch already checked out. The Planner and writer code here also do not provide the claimed `backlog_actions` execution path. Current behavior must be checked against these files rather than inferred from that commit message.
