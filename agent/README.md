# AI development workflow

This directory (`agent/`) and the local bridge (`local_bridge/`) contain the AI-assisted development workflow for the GW2 crafting application. n8n runs the workflow, and a Python state machine controls its transitions.

## What is this system?

The project is an experiment in how much development can be automated while producing a working product and maintainable code.

The work is divided into Planner, Architect, QA, Implementer, and Evaluator roles. They exchange Markdown and JSON files. The Python runtime enforces their permissions and workflow rules.

* **Orchestration:** n8n calls a local Python bridge that enforces state transitions. Implementation requires a validated QA plan.
* **Evaluation:** Codex reviews the working tree produced by the Implementer (Claude).
* **Usage limits:** `claude_budget.py` applies weekly usage shares and session limits to control Claude usage.
* **Clarification:** Agents raise User Decisions for missing or conflicting product rules and Architect Requests for architecture questions.

## Why the workflow is split this way

The bridge separates model tasks from lifecycle changes. Models propose stories, write code, and review results. Python selects the active story, applies status transitions, publishes commits, and records the CI result. A model's completion message alone cannot mark a story DONE.

| Concern | Mechanism in this repository | Limit |
| --- | --- | --- |
| Resuming work | A persisted cycle record stores the next phase, retry counters, and published SHA. | An interrupted step runs again; an interrupted coding attempt receives a continuation prompt. Recovery is at step boundaries. |
| Independent review | The Evaluator inspects the working tree against the story and QA plan in a read-only sandbox. | A model verdict can still be wrong. Python validates the verdict structure; tests and CI provide additional checks. |
| Stable acceptance tests | QA can prepare tests before coding. The harness restores QA-owned tests and the plan after coding attempts. | A valid plan does not prove that its tests cover every requirement. Evaluation also checks the story's intended result. |
| Controlled retries | Evaluation retries, CI fixes, and consecutive Claude failures have separate counters. | Exhausting a retry budget blocks the story; capacity waits preserve the current phase. |
| Human decisions | Missing product rules become User Decisions. Architecture questions go to a separate Architect invocation. | Only a human resolves a User Decision. Independent work can continue while a decision is open. |

These are properties of this implementation. They do not establish a speed, cost, or correctness advantage over other coding tools. Running the workflow also requires maintaining the bridge, n8n configuration, role contracts, and recovery state.

The implementation and operating procedures are documented in the [bridge guide](../local_bridge/README.md) and [runtime guide](runtime/README.md). The table above is based on [`cycle.py`](../local_bridge/cycle.py), the [QA contract](qa-plans/README.md), and the runtime's evaluation and recovery descriptions.

## Navigating the Documentation

* [Glossary](docs/GLOSSARY.md): Terms and agent roles.
* [Architecture](docs/ARCHITECTURE.md): The Python bridge, n8n integration, and agent permissions.
* [Workflow diagrams](docs/WORKFLOW_DIAGRAMS.md): The story lifecycle, CI feedback, and milestone processing.
* [Evolution](docs/EVOLUTION.md): Earlier workflow designs, reported failures, and the changes made to address them.

Earlier experiment notes are preserved in [the archive](agent_README_experimental_archive.md).
