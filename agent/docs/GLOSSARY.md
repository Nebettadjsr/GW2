# Workflow glossary

These definitions explain this documentation. Binding domain terminology belongs in the separate [crafting glossary](../../docs/crafting/GLOSSARY.md).

## Agents and models

| Term | Meaning here |
| --- | --- |
| **Large language model (LLM)** | A model that processes and generates text. It can produce useful code or reasoning and can also produce unsupported answers. |
| **Agent** | A model running in a loop with tools, such as file reads, shell commands, and edits. It chooses actions within the task and permissions it receives. |
| **Role** | A particular task, instructions, and permission boundary for an invocation. Planner and Evaluator can use the same configured model while remaining separate roles. |
| **Claude / Codex CLI** | Command-line tools used to run agents. The tool, the underlying model, and the assigned role are separate choices. Claude runs implementation; Codex runs planning, architecture, QA, evaluation, and sync review. See the [runtime configuration](../runtime/README.md) for model settings. |
| **Prompt** | Instructions and context supplied to a model for a task. The runtime builds prompts from project files and workflow state. |
| **Context** | Information available in a model request, including the prompt and accumulated tool results. A separate invocation needs its own relevant context; it does not automatically inherit another role's understanding. |
| **Token** | A unit of text processed by a model, often part of a word. Token counts, subscription usage percentages, and elapsed time are different quantities. |
| **Hallucination** | Output that invents a fact, file, requirement, or result. A valid JSON response can still contain one. |

## Workflow and records

| Term | Meaning here |
| --- | --- |
| **Harness / runtime** | Python code that prepares model tasks, validates results, enforces boundaries, and applies lifecycle changes. It is maintained software, not another model. |
| **Local bridge** | The host process that receives n8n's HTTP requests, runs workflow steps, and persists their state. |
| **n8n** | The visual workflow tool that schedules and requests bridge steps. |
| **State machine** | Code defining allowed stages and transitions, such as moving from evaluation to coding when corrections are required. Fixed transitions do not make model output deterministic. |
| **Milestone / roadmap phase** | A larger unit of planned product work with exit criteria. It can contain several stories and unresolved decisions. Completing one story does not complete its milestone. |
| **Story** | A bounded implementation contract with scope, acceptance criteria, prerequisites, and completion requirements. |
| **Backlog** | The index and priority order of stories. Python selects the first eligible story from `To Do`; the story file supplies its full contract. `User` is a separate index of pending human requests. |
| **QA plan** | A persistent JSON record of checks, tests, and invariants prepared before implementation. `READY` permits coding; justified `NO_TESTS_NEEDED` permits work without new tests; `NEEDS_USER` requires a product answer. |
| **Invariant** | A condition that must continue to hold, including outside the specific examples in a test. |
| **Artifact** | A generated prompt, result, or backup from a run. Temporary runtime artifacts are distinct from tracked story and QA contracts. |
| **Cycle record** | The bridge's saved execution state for a story, including the next phase, counters, publication paths, and commit SHA. |
| **Worktree** | A separate checked-out directory of the same Git repository. `agent/dev` uses one so pipeline edits do not share the maintainer's working files. Branches can still conflict when merged. |
| **CI / published SHA** | Continuous integration runs automated checks on a specific Git commit. The SHA identifies that exact commit; a later branch update is not the same test target. Publication here means pushing commits, not necessarily deploying an application. |

## Roles and decisions

| Term | Responsibility |
| --- | --- |
| **Product Owner** | The human who decides intended product behavior and resolves missing or conflicting product requirements. |
| **Planner** | Reviews the current phase and requests, prepares stories, maintains planning continuity, and queues architecture questions. |
| **Architect** | Answers one dispatched architecture question within its authority and records significant decisions. It does not select or implement stories. |
| **QA agent** | Prepares a plan and may write acceptance tests in approved roots. Conditional review after implementation is read-only. |
| **Implementer** | Writes code and reports its work against the story and QA plan. It does not own their acceptance rules or mechanical completion status. |
| **Evaluator** | Inspects the implementation in a read-only sandbox and returns a verdict against the story and plan. The harness applies the resulting transition. |
| **Documentation sync review** | A bounded review of changes merged from the maintainer branch. It reconciles approved descriptive docs and raises unresolved contradictions for the proper owner. |
| **User Decision (`UD-*`)** | A recorded question that requires a human answer. A model can open a question but cannot resolve it. |
| **Architect Request (`AR-*`)** | A recorded architecture question answered in a separate Architect invocation. It may wait for a human decision. |
| **User Intervention (`UI-*`)** | A harness-created record requesting human attention, for example when automatic retries are exhausted. |
| **Capacity wait** | A pause because a model cannot run within its current allowance. It preserves the phase instead of consuming the implementation-failure budget. |
| **Read-only sandbox** | An execution boundary that prevents the reviewer from modifying the files being inspected. It does not establish that the review is correct. |

For who decides and what happens next, see [workflow diagrams](WORKFLOW_DIAGRAMS.md). For write authority and the homes of requirements, see [documentation ownership](ARCHITECTURE.md#documentation-ownership-is-part-of-the-design).
