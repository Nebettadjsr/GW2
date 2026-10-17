# Workflow glossary

Terms used in the development workflow documentation.

### General AI Terms

* **LLM (Large Language Model):** A model that processes and generates text. Models run through the `claude` and `codex` command-line tools in this workflow; the tools and the models they invoke are separate configuration choices.
* **Tokens:** Chunks of text processed by a model, often parts of words. Usage can be billed or limited by token count.
* **Prompt:** The instructions and context given to a model. The runtime builds prompts from project files using Python code.
* **Hallucination:** Model output that invents facts, rules, or requirements.

### Project Specific Terms

* **SDLC (Software Development Life Cycle):** The process of planning, developing, testing, and deploying software.
* **n8n:** A visual workflow automation tool that runs the development pipeline.
* **Local Bridge:** A Python application on the host machine that connects n8n to the repository and runs requested workflow steps.
* **State Machine / Deterministic Orchestration:** Code that controls transitions between predefined stages. For example, implementation requires a validated QA plan.
*   **Backlog (`BACKLOG.md`):** The authoritative list of all work that needs to be done, is currently being done, or is blocked.
* **Story:** An implementation contract in `agent/stories/` with scope, acceptance criteria, prerequisites, and completion requirements. The backlog indexes these files.
* **Cycle record:** The bridge's persisted execution state for one story: its next phase, retry counters, changed paths, and published commit SHA.
* **QA plan:** A persistent JSON contract in `agent/qa-plans/`. It maps story requirements to checks, tests, and invariants. Valid outcomes are `READY`, justified `NO_TESTS_NEEDED`, or `NEEDS_USER`.

### Agent roles

* **Planner:** Reviews the current roadmap phase and product requests, prepares stories, maintains planning continuity, and queues architecture questions it cannot decide.
* **Architect:** Answers one dispatched Architect Request within its authority and records significant decisions. It may escalate a human decision; it does not plan implementation stories.
* **QA (Quality Assurance) Agent:** Prepares a persistent plan and may write tests in approved test roots before implementation. Conditional post-implementation QA review is read-only.
* **Implementer (Claude):** Writes application code using the story and QA plan.
* **Evaluator (Codex):** Reviews the Implementer's code against the story and QA plan in a read-only sandbox.

### Decisions and verification

* **Product Owner (Human):** Decides product behavior and resolves missing or conflicting business requirements recorded by agents.
* **User Decision (`UD-*`):** A recorded question requiring a human answer. Agents may open a decision but may not resolve it. Dependent work waits for the answer.
* **Architect Request (`AR-*`):** A Planner-created architecture question answered by a separate Architect invocation. A request needing human input can wait on a User Decision.
* **User Intervention (`UI-*`):** A harness-created record requiring human attention, for example after an implementation retry budget is exhausted.
* **Read-Only Sandbox:** An execution environment that prevents an agent from modifying the files it inspects.
* **CI/CD (Continuous Integration / Continuous Deployment):** Automated checks and deployment steps. This workflow uses GitHub Actions to test published commits and feeds failures back to the Implementer.
* **Capacity wait:** A pause because a model cannot run within its current usage allowance. The phase is preserved; this is distinct from an implementation failure consuming the failure budget.

The [bridge guide](../../local_bridge/README.md) owns cycle and recovery behavior. The [runtime guide](../runtime/README.md) describes agent execution, and the [QA plan contract](../qa-plans/README.md) defines plan fields and ownership.
