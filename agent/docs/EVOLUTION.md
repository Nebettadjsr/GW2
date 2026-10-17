# Evolution & Lessons Learned

This document traces the evolution of the AI Development Orchestrator. It explains *why* the system is architected with rigid, deterministic boundaries by exploring the failures and "hiccups" that occurred during earlier iterations of the agent workflow.

## Generation 1: The "Simple Coder" Script

**The Architecture:** A basic Python script that sent a prompt containing a user goal and the entire codebase to an LLM, asking it to "write the feature."

**The Failures:**
*   **Vibe-Coding:** The LLM confidently outputted code that superficially looked correct but failed edge cases or broke existing features because it lacked a formal test plan.
*   **Context Bloat & Amnesia:** Passing the entire codebase in every prompt caused the LLM to exceed context limits, forget the original requirements, or hallucinate files that didn't exist.
*   **Cost Runaways:** A single unhandled loop error in the script could trigger the LLM to retry hundreds of times overnight, burning through API budgets in hours.

## Generation 2: Introduction of the Planner and Backlog

**The Fix:** To solve Context Bloat, the "Planner" role was introduced. Its job was to read a high-level roadmap and break it down into small, digestible Markdown "Stories" listed in a `BACKLOG.md` index.

**The Failures:**
*   **File Corruption via Full Rewrites:** Initially, the Planner LLM was tasked with rewriting the entire `BACKLOG.md` file every time it updated a story's status. Over time, the LLM would quietly drop completed stories, alter formatting, or hallucinate new status tags, causing the Python regex parsers to crash.
*   **Token Waste:** Rewriting a 2,000-line backlog file just to change the word `TODO` to `ACTIVE` wasted massive amounts of output tokens and increased latency.

**The Current Solution:** The Planner was heavily restricted to output specific *diff commands* (`backlog_actions` like `move` or `add_to_do`) in JSON. A deterministic Python script (`backlog_writer.py`) now safely applies these targeted actions to the physical file without requiring the LLM to rewrite the entire index.

## Generation 3: The "Evaluator" and Separation of Duties

**The Architecture:** After realizing the Coder was producing bug-ridden code, the workflow was updated to ask the Coder to verify its own work in a follow-up prompt.

**The Failures:**
*   **Grading its Own Homework:** The Implementation LLM (Claude) would write a bug, and when subsequently asked "Did you fulfill the requirements?", it would confidently respond "Yes!" without actually executing or physically verifying the code.
*   **Endless Loops:** If the LLM *did* notice a bug, it would try to fix it, fail again, retry, and get stuck in an infinite conversational loop, never recognizing it was stuck.

**The Current Solution:** A strict Separation of Duties was introduced across multiple agents:
1.  A **QA Agent** writes the acceptance criteria and test plan *before* implementation begins.
2.  **The Implementer** writes the code.
3.  A totally separate reasoning model (**Codex**) acts as the **Evaluator**. Crucially, it operates in a *Read-Only Sandbox*. It does not rely on the Implementer's claims; it physically inspects the Git working tree. If it fails, the orchestrator manages the retry loop, hard-capped at a maximum budget.

## Generation 4: Visual Orchestration and Human Governance

**The Architecture:** The high-level loop management was moved to **n8n** for visual debugging, state tracking, and deterministic retry management.

**The Failures:**
*   **Hallucinating Business Rules:** When the LLM encountered ambiguous domains (like GW2 crafting rules), it would confidently invent business logic rather than asking for clarification, leading to fundamentally incorrect application behavior.
*   **Merge Clashes & Documentation Drift:** The agent originally ran directly on the `main` branch. If a human developer made a quick manual fix while the agent was running, the agent would either overwrite the human's changes or fail due to git conflicts. Furthermore, if the human forgot to update the AI's authoritative documentation (`docs/ARCHITECTURE.md`), the Planner would detect the discrepancy between the new code and old docs, flag it as a defect, and attempt to "fix" the human's code.

**The Current Solution:**
1.  **Product Owner & Architect Requests:** The workflow introduced a system of formal "Requests" and "User Decisions". If rules conflict, the agent pauses the active story and generates a markdown file asking the human for a decision, eliminating hallucinated business logic.
2.  **Dedicated Agent Branching & Sync:** The agent now works exclusively on an isolated branch (`agent/active-development`).
3.  **Documentation Sync Phase:** Before starting a cycle, the agent pulls `main` and checks the diff. If it detects a discrepancy between the human's new code and the documentation, it generates a non-blocking request for the human to update the docs, ensuring the Planner does not mistakenly roll back valid human interventions.

## Conclusion

Every rule, guardrail, and split-agent role in this system exists as a solution to a previous failure mode. By replacing LLM conversational memory with physical Markdown files, and replacing LLM decision-making with deterministic Python transitions and separate, read-only evaluation roles, the system evolved from a fragile script into a highly reliable software factory.
