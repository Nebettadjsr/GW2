# AI Development Orchestrator & SDLC Pipeline

Welcome to the AI Development Orchestrator for the GW2 crafting application.

This directory (`/agent`) and the neighboring local bridge (`/local_bridge`) house a state-of-the-art **AI-assisted Software Development Lifecycle (SDLC)**.

Unlike conventional AI tools that act as "chatbots" or simple autocomplete plugins, this system functions as an autonomous, multi-agent engineering team governed by a strict, deterministic state machine orchestrated through **n8n**.

## What is this system?

At its core, this project aims to answer a fundamental question:
> *How far can software development be automated in practice, while still producing a working product and maintainable software?*

This system achieves autonomy not by throwing a massive prompt at a single Large Language Model (LLM) and hoping for the best. Instead, the software engineering process is divided into isolated, distinct roles (Planner, Architect, QA, Implementer, and Evaluator). These AI roles communicate exclusively through structured artifacts (Markdown and JSON files) and are bound by rigid Python guardrails.

**Key Features:**
*   **Deterministic Orchestration:** n8n acts as the workflow engine, calling a local Python bridge that enforces state transitions (e.g., code implementation cannot begin without a validated QA plan).
*   **Separation of Concerns:** Different AI models handle different tasks. A reasoning model (Codex) acts as the Evaluator, grading the work of the Implementation model (Claude). This prevents the AI from "grading its own homework."
*   **Built-in Cost Management:** The system paces itself. A `claude_budget.py` monitor ensures API/subscription limits are never accidentally overrun by enforcing weekly usage shares and session limits.
*   **Human-in-the-Loop Escapes:** The system is designed not to hallucinate requirements. If the documentation contradicts itself or the code, the pipeline pauses and generates a "User Decision" or "Architect Request," waiting for a human Product Owner to resolve the ambiguity.

## Why this Architecture? (A Comparison)

If evaluating this system against modern AI coding frameworks, here is how it compares:

| Feature | The Orchestrator System (n8n Multi-Agent SDLC) | Single-Agent Systems (e.g., Aider, Devin) | In-Memory Multi-Agent (e.g., CrewAI) |
| :--- | :--- | :--- | :--- |
| **State Persistence** | **Absolute.** All state is written to physical files (`CURRENT_STORY.md`, backlog indexes). A crash resumes exactly where it left off. | Varies. Often relies on the LLM's conversational memory or a simple log. | Highly fragile. If the Python script crashes at step 4 of 10, the run is lost. |
| **Verification** | **Read-Only Evaluator.** A separate AI reads the actual Git working tree to verify the implementer's code. | The same agent writes the code and runs the tests (prone to self-deception). | Varies, but often agents just read each other's chat outputs, not the actual codebase. |
| **Speed & Prototyping** | **Slow & Methodical.** Built for high-risk, complex, domain-heavy applications. | Very fast. Great for spinning up quick react apps or scripts. | Fast, but difficult to debug when agents get stuck in conversational loops. |

*In summary:* Rapid, chaotic prototyping speed was traded for **accountability, traceability, and correctness**.

## Navigating the Documentation

To fully understand the architecture, history, and mechanics of this system, please explore the following deep-dive documents located in `agent/docs/`:

1.  **[Glossary of Terms](docs/GLOSSARY.md)**: An entry point for non-technical team members (Marketing, HR, Management) to understand the vocabulary used across these documents.
2.  **[System Architecture & Technical Design](docs/ARCHITECTURE.md)**: A deep dive into the Python bridge, n8n integration, and the read/write boundaries of the agents.
3.  **[Workflow Diagrams & Happy Paths](docs/WORKFLOW_DIAGRAMS.md)**: Visual maps (Mermaid.js) of the story lifecycle, the CI/CD feedback loop, and how milestones are processed.
4.  **[Evolution & Lessons Learned](docs/EVOLUTION.md)**: The historic journey of this system. Explains *why* the rules exist by tracking the "hiccups" and failures (like token runaways and hallucinated requirements) that forced the creation of these guardrails.

---
*For the previous, highly-detailed experimental readmes and old design notes, see `agent_README_experimental_archive.md`.*
