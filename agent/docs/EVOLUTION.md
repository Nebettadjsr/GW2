# Why the workflow developed this way

The maintainer describes the starting point as essentially one coding agent. The earliest tracked orchestration snapshot below already contains planning and evaluation, so it cannot establish every step between that starting point and the current system. The useful history is the evidence for particular boundaries, rather than a reconstructed story about successive generations of agents.

The [architecture guide](ARCHITECTURE.md#why-the-responsibilities-are-separate) explains the current trade-offs. These snapshots supply supporting context; they are not evidence that adding more roles always improves results.

## A few implementation snapshots

| Snapshot | What the record establishes | Why it matters to today's design |
| --- | --- | --- |
| [September 20: `f11ce57`](https://github.com/Nebettadjsr/GW2/commit/f11ce57) | A Python orchestrator already called a Planner, a Claude coding runner, and an evaluator using Ollama. Configuration named `hermes3:8b` and bounded story retries. | The tracked workflow was already moving judgment and task management out of one coding session. This snapshot does not prove that the Implementer previously reviewed its own work. |
| [September 20: `1b97d16`](https://github.com/Nebettadjsr/GW2/commit/1b97d16) | Runtime code was separated by responsibility, with recovery handling and distinct source, test, and generated-artifact locations. | A workflow that lasts across interruptions needs explicit records and ownership, rather than relying on one live conversation. Persisted state also creates recovery work of its own. |
| [October 3: `0ef2391`](https://github.com/Nebettadjsr/GW2/commit/0ef2391) | The QA gate introduced persistent plans, prepared tests, protected-test handling, and recovery integration. | Acceptance checks became a contract prepared before implementation. The coding agent could no longer alter those checks without the harness restoring and reporting the change. |
| [October 6–10: `c253215`](https://github.com/Nebettadjsr/GW2/commit/c253215), [`aed2822`](https://github.com/Nebettadjsr/GW2/commit/aed2822) | Migration to n8n began; the retired standalone outer loop was later removed while reusable runtime helpers remained. | Scheduling and visible execution moved to n8n. Python still owns repository-specific rules. This added an HTTP/process boundary and n8n operations, rather than removing orchestration complexity. |
| [October 17: `02eb09e`](https://github.com/Nebettadjsr/GW2/commit/02eb09e) | The pipeline gained its own worktree, maintainer sync and document review, and harness-applied JSON backlog actions. These are present in the current source. | Maintainer changes can reach the pipeline between stories without sharing its working tree. Backlog mutations have one mechanical writer, and incoming code/document contradictions have an explicit review step. |

## Problems with recorded evidence

### Review needed access to the implementation

The [archived evaluator notes](../agent_README_experimental_archive.md#why-it-is-no-longer-a-local-model) and [runtime evaluation guide](../runtime/README.md#evaluation-codex-read-only) describe the earlier `hermes3:8b` evaluator. Its input was the story and Claude's report. The notes report empty retry verdicts and explain the move to a stronger evaluator with access to the working tree. They do not provide a controlled comparison of model accuracy or establish when every part of that transition happened.

The important change was the evidence available for review. An Implementer can report that a feature is complete while leaving its user-facing call path unwired. Current evaluation asks whether both the criteria and the story's purpose are met. A read-only sandbox prevents the reviewer from editing the uncommitted work it is judging. It still needs sufficient tests and context to detect that sort of omission.

### Large context and repeated planning consumed usage

The [September 26 usage investigation](../runtime/reports/2026-09-26-planner-usage.md) recorded a prompt with 139,095 characters of backlog text out of 220,114 supplied characters. Most backlog text described completed or archived work. The report also found evidence of a repeated pass following a formatting-only input rewrite, while identifying the historical cache details it could not recover.

The response was to normalize BOMs and line endings for planning fingerprints and supply compact indexes for historical material. The [runtime guide](../runtime/README.md#supplied-context-is-indexed-not-narrated) records a prompt reduction from 221,331 to about 86,600 characters on the September 26 repository state. Those are prompt characters, not a billing comparison with a single-agent workflow. The lesson is that role count alone says little about cost: what is supplied repeatedly matters.

### The backlog needed one format and one writer

The module notes in [`backlog_writer.py`](../runtime/core/backlog_writer.py) record different writers using incompatible backlog representations. A completed story remained under `Active`, and explanatory prose became detached from its entry. For a queue read by code, a malformed line can make real work disappear from selection.

Canonical entries and validation addressed that parsing problem. Current Planner output uses `backlog_actions`, which the harness applies from story data. This keeps prioritization with the Planner while separating it from the mechanics of rewriting an index and changing lifecycle state.

## Intent preserved from the experiment

The [old README](../agent_README_experimental_archive.md) explains why the experiment uses a product with interacting domain rules, rather than judging success by code generation alone. It also distinguishes human product authority, planning, architecture decisions, implementation, and review, and assigns facts to authoritative documents. Those ideas remain useful in the current [document ownership model](ARCHITECTURE.md#documentation-ownership-is-part-of-the-design).

The archive also describes experiments with a local `gpt-oss:20b` Planner, including long passes, excessive reads, and editing problems. These are reported experiences under the setup used then, not grounds for declaring local models generally unsuitable. The current architecture reflects those experiences and the repository's contracts; it is one solution to them, not evidence that a larger pipeline is always necessary.
