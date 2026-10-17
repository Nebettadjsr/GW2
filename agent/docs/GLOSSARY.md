# Glossary of Terms

This document provides simple explanations for the terminology used throughout the AI Orchestrator documentation. It is designed to help non-technical team members (Marketing, HR, Management) understand the system, while providing a baseline for technical engineers.

### General AI Terms

*   **LLM (Large Language Model):** The "brain" behind the AI. The system uses two different models: **Claude** (by Anthropic) for implementation, and **Codex** (an internal or localized reasoning model) for evaluation and planning.
*   **Tokens:** The currency of AI. An LLM reads and writes text in chunks called "tokens" (roughly parts of words). Usage is billed or limited based on the number of tokens processed.
*   **Prompt:** The instructions and context given to the LLM. In this system, prompts are entirely constructed by Python code based on project files, never by a human typing in a chatbox.
*   **Hallucination:** When an AI confidently invents facts, rules, or requirements that do not exist. The system's rigid rules are built primarily to catch and prevent this behavior.

### Project Specific Terms

*   **SDLC (Software Development Life Cycle):** The formal process of planning, creating, testing, and deploying software. This system automates the SDLC.
*   **n8n:** A visual workflow automation tool (similar to Zapier, but for enterprise workflows). It acts as the "conductor," determining when the system moves to the next step.
*   **Local Bridge:** A Python application running on the host server that connects n8n to the actual codebase. When n8n requests an implementation or QA pass, the local bridge safely executes that command against the repository.
*   **State Machine / Deterministic Orchestration:** A system that can only move between specific, predefined stages (e.g., QA must be passed before Implementation). "Deterministic" means it is predictable—it relies on hard-coded Python rules, not AI whims.
*   **Backlog (`BACKLOG.md`):** The authoritative list of all work that needs to be done, is currently being done, or is blocked.

### The AI Roles (The "Team")

*   **The Planner:** An AI role responsible for looking at the roadmap and deciding what "Stories" (tasks) need to go into the Backlog.
*   **The Architect:** An AI role that answers high-level technical questions (e.g., "How should the database be structured for this feature?").
*   **The QA (Quality Assurance) Agent:** An AI role that reads a story and writes the strict criteria and tests needed to prove the feature works *before* any code is written.
*   **The Implementer (Claude):** The "coder." This agent takes the story and QA plan and actually writes the application code.
*   **The Evaluator (Codex):** The "inspector." A completely separate, read-only AI that looks at the code the Implementer wrote to ensure it actually solves the problem and passes the tests.

### System Safety Terms

*   **Product Owner (Human):** When the AI team encounters conflicting rules or missing information regarding business logic, they generate a "Product Owner Request." A human must step in and make the call.
*   **Read-Only Sandbox:** A security measure where specific agents (like QA and Evaluator) are physically prevented from changing code. They can only inspect it and report back.
*   **CI/CD (Continuous Integration / Continuous Deployment):** An automated system (usually GitHub Actions) that runs the test suite every time new code is written. If the CI fails, the system receives the error log and attempts to fix it.
