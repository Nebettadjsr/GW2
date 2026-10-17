# Project and workflow history

## Origins: the maintainer's account

The project began with a junior developer trying to build the application by pair programming with AI. Despite repeated attempts to keep it organized, business logic ended up in the UI and controllers. Correctly implementing the crafting tree became increasingly difficult. Fixing one bug exposed others, and previously fixed bugs returned.

The maintainer set the project aside for a few months, then returned after learning enough to attempt a more structured AI-supported development workflow. Before starting that workflow, the maintainer established documents describing the current architecture, target architecture, roadmap, domain specification, and a long list of bugs in Known Problems. Those documents gave planning a defined starting point instead of asking an agent to derive product intent from the existing code.

The initial workflow paired Codex as Planner with Claude as coder. The other roles, records, and safeguards developed in response to practical problems. The rough milestones already existed; they became more detailed as the project approached them. Product Owner requests were added as a way for the human to steer development, not as the source of every milestone.

As implementation became automated, the maintainer could spend more time testing features. That testing uncovered bugs and generated new ideas. Product Owner requests provided a route to change behavior or expand scope during development. User Decisions and interventions provided the reverse route: the workflow could ask the human to settle a question or investigate a failure.

The Python orchestrator eventually grew to roughly 3,000 lines in the maintainer's account and remained difficult to maintain even after parts were split into separate workflows. Moving the outer workflow to n8n made its steps and outputs visible. The reason was personal control as well as maintenance: even when AI performs the implementation, the maintainer wants to understand what the system is doing and what information each step generates.

These details come from the maintainer's account. The Git snapshots below show specific implemented stages after work on the workflow had begun; they do not reconstruct every earlier event. The [architecture guide](ARCHITECTURE.md#why-the-responsibilities-are-separate) explains the current mechanisms, and the [comparison](COMPARISON.md) considers other ways to organize the work.

## A few implementation snapshots

| Snapshot | What the record establishes | Why it matters to today's design |
| --- | --- | --- |
| [September 20: `f11ce57`](https://github.com/Nebettadjsr/GW2/commit/f11ce57) | A Python orchestrator already called a Planner, a Claude coding runner, and an evaluator using Ollama. Configuration named `hermes3:8b` and bounded story retries. | The tracked workflow was already moving judgment and task management out of one coding session. This snapshot does not prove that the Implementer previously reviewed its own work. |
| [September 20: `1b97d16`](https://github.com/Nebettadjsr/GW2/commit/1b97d16) | Runtime code was separated by responsibility, with recovery handling and distinct source, test, and generated-artifact locations. | A workflow that lasts across interruptions needs explicit records and ownership, rather than relying on one live conversation. Persisted state also creates recovery work of its own. |
| [October 3: `0ef2391`](https://github.com/Nebettadjsr/GW2/commit/0ef2391) | The QA gate introduced persistent plans, prepared tests, protected-test handling, and recovery integration. | Acceptance checks became a contract prepared before implementation. The coding agent could no longer alter those checks without the harness restoring and reporting the change. |
| [October 6–10: `c253215`](https://github.com/Nebettadjsr/GW2/commit/c253215), [`aed2822`](https://github.com/Nebettadjsr/GW2/commit/aed2822) | Migration to n8n began; the retired standalone outer loop was later removed while reusable runtime helpers remained. | n8n made execution and step outputs visible to the maintainer. Python retained repository-specific rules, with an additional HTTP/process boundary to operate. |
| [October 17: `02eb09e`](https://github.com/Nebettadjsr/GW2/commit/02eb09e) | The pipeline gained its own worktree, maintainer sync and document review, and harness-applied JSON backlog actions. These are present in the current source. | Maintainer changes can reach the pipeline between stories without sharing its working tree. Backlog mutations have one mechanical writer, and incoming code/document contradictions have an explicit review step. |

## Problems with recorded evidence

### Review needed access to the implementation

The [runtime evaluation guide](../runtime/README.md#evaluation-codex-read-only) describes the earlier `hermes3:8b` evaluator. Its input was the story and Claude's report. The guide reports empty retry verdicts and explains the move to a stronger evaluator with access to the working tree. It does not provide a controlled comparison of model accuracy or establish when every part of that transition happened.

The important change was the evidence available for review. An Implementer can report that a feature is complete while leaving its user-facing call path unwired. Current evaluation asks whether both the criteria and the story's purpose are met. A read-only sandbox prevents the reviewer from editing the uncommitted work it is judging. It still needs sufficient tests and context to detect that sort of omission.

### Large context and repeated planning consumed usage

The [September 26 usage investigation](../runtime/reports/2026-09-26-planner-usage.md) recorded a prompt with 139,095 characters of backlog text out of 220,114 supplied characters. Most backlog text described completed or archived work. The report also found evidence of a repeated pass following a formatting-only input rewrite, while identifying the historical cache details it could not recover.

The response was to normalize BOMs and line endings for planning fingerprints and supply compact indexes for historical material. The [runtime guide](../runtime/README.md#supplied-context-is-indexed-not-narrated) records a prompt reduction from 221,331 to about 86,600 characters on the September 26 repository state. Those are prompt characters, not a billing comparison with a single-agent workflow. This was historical planning-efficiency work; Codex allowance is not the maintainer's current bottleneck.

### The backlog needed one format and one writer

The module notes in [`backlog_writer.py`](../runtime/core/backlog_writer.py) record different writers using incompatible backlog representations. A completed story remained under `Active`, and explanatory prose became detached from its entry. For a queue read by code, a malformed line can make real work disappear from selection.

Canonical entries and validation addressed that parsing problem. Current Planner output uses `backlog_actions`, which the harness applies from story data. This keeps prioritization with the Planner while separating it from the mechanics of rewriting an index and changing lifecycle state.

## Intent preserved from the experiment

The experiment uses a product with interacting domain rules, rather than judging success by code generation alone. Human product authority, planning, architecture decisions, implementation, and review have separate responsibilities. The [document ownership model](ARCHITECTURE.md#documentation-ownership-is-part-of-the-design) gives the requirements and decisions an authoritative home.

Earlier experiment notes record a local `gpt-oss:20b` Planner, including long passes, excessive reads, and editing problems. These are reported experiences under the setup used then, not grounds for declaring local models generally unsuitable. The current architecture reflects those experiences and the repository's contracts; it is one solution to them, not evidence that a larger pipeline is always necessary.

## Current constraint: Claude's allowance

The maintainer reports frequently exhausting Claude's allowance on a $20 plan. Claude is currently the coding bottleneck; Codex allowance is not currently an issue. The workflow can use a Claude wait for needed Codex planning or architecture work, but it cannot implement the next change without coding capacity. Ways to reduce Claude usage are still being investigated. The [architecture guide](ARCHITECTURE.md#current-implementation-bottleneck) describes the existing quota controls; the [comparison](COMPARISON.md#cost-and-the-current-bottleneck) discusses the cost trade-offs without claiming that this constraint has been solved.
