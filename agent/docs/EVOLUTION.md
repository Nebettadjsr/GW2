# Workflow evolution

This document describes earlier workflow designs and the problems reported with them.

## 1. A single coding script

**Design:** A Python script sent a user goal and the entire codebase to an LLM for implementation.

**Problems:**

* Code missed edge cases or broke existing features. There was no formal test plan.
* Sending the entire codebase in every prompt exceeded context limits. Model outputs omitted requirements or referenced nonexistent files.
* Unhandled retry loops could exhaust the usage budget.

## 2. Planner and backlog

**Design:** The Planner broke the roadmap into smaller Markdown stories listed in `BACKLOG.md` to reduce the context needed for each task.

**Problems:**

* Rewriting all of `BACKLOG.md` for each status update dropped completed stories, changed formatting, or introduced invalid status tags that broke parsing.
* Rewriting the index for a single status change used more output tokens and took longer than a targeted update.

**Change:** The Planner outputs JSON `backlog_actions`, such as `move` or `add_to_do`. `backlog_writer.py` applies these actions without asking the model to rewrite the index.

## 3. Separate evaluation

**Design:** A follow-up prompt asked the coding agent to verify its work.

**Problems:**

* The coding agent reported success without running or checking the code.
* Attempts to fix a bug could enter a repeated failure loop.

**Change:** The workflow separates preparation, implementation, and review:

1. The QA agent prepares acceptance criteria and a test plan before implementation.
2. The Implementer writes the code.
3. The Evaluator (Codex) inspects the Git working tree in a read-only sandbox. The orchestrator manages retries within a usage budget.

## 4. n8n and human decisions

**Design:** n8n took over workflow loop management, with visual debugging, state tracking, and retry handling.

**Problems:**

* Models invented business rules when requirements were ambiguous.
* The agent ran on `main`, where concurrent human edits could be overwritten or cause Git conflicts. When code changed without corresponding documentation updates, the Planner could treat valid code as a defect and attempt to revert it.

**Changes:**

* **Requests and User Decisions:** Agents record questions in Markdown files. Product decisions require a human answer; architecture questions go to the Architect.
* **Separate branch:** Agent work moved to `agent/active-development`.
* **Documentation sync:** Before a cycle, the agent pulls `main` and checks the diff. A discrepancy between code and documentation produces a non-blocking request for a documentation update.
