# AI development workflow

This directory (`agent/`) and the local bridge (`local_bridge/`) contain the AI-assisted development workflow for the GW2 crafting application. n8n runs the workflow, and a Python state machine controls its transitions.

## What is this system?

The project is an experiment in how much development can be automated while producing a working product and maintainable code.

The work is divided into Planner, Architect, QA, Implementer, and Evaluator roles. They exchange Markdown and JSON files. The Python runtime enforces their permissions and workflow rules.

* **Orchestration:** n8n calls a local Python bridge that enforces state transitions. Implementation requires a validated QA plan.
* **Evaluation:** Codex reviews the working tree produced by the Implementer (Claude).
* **Usage limits:** `claude_budget.py` applies weekly usage shares and session limits to control Claude usage.
* **Clarification:** Agents raise User Decisions for missing or conflicting product rules and Architect Requests for architecture questions.

## Navigating the Documentation

* [Glossary](docs/GLOSSARY.md): Terms and agent roles.
* [Architecture](docs/ARCHITECTURE.md): The Python bridge, n8n integration, and agent permissions.
* [Workflow diagrams](docs/WORKFLOW_DIAGRAMS.md): The story lifecycle, CI feedback, and milestone processing.
* [Evolution](docs/EVOLUTION.md): Earlier workflow designs, reported failures, and the changes made to address them.

Earlier experiment notes are preserved in [the archive](agent_README_experimental_archive.md).
