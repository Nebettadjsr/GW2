# Workflow glossary

Terms used in the development workflow documentation.

### General AI Terms

* **LLM (Large Language Model):** A model that processes and generates text. The workflow uses the `claude` and `codex` CLIs to run model tasks.
* **Tokens:** Chunks of text processed by a model, often parts of words. Usage can be billed or limited by token count.
* **Prompt:** The instructions and context given to a model. The runtime builds prompts from project files using Python code.
* **Hallucination:** Model output that invents facts, rules, or requirements.

### Project Specific Terms

* **SDLC (Software Development Life Cycle):** The process of planning, developing, testing, and deploying software.
* **n8n:** A visual workflow automation tool that runs the development pipeline.
* **Local Bridge:** A Python application on the host machine that connects n8n to the repository and runs requested workflow steps.
* **State Machine / Deterministic Orchestration:** Code that controls transitions between predefined stages. For example, implementation requires a validated QA plan.
*   **Backlog (`BACKLOG.md`):** The authoritative list of all work that needs to be done, is currently being done, or is blocked.

### Agent roles

* **Planner:** Reads the roadmap and prepares stories for the backlog.
* **Architect:** Answers architecture questions, such as how to structure the database for a feature.
* **QA (Quality Assurance) Agent:** Reads a story and prepares criteria and tests before implementation.
* **Implementer (Claude):** Writes application code using the story and QA plan.
* **Evaluator (Codex):** Reviews the Implementer's code against the story and QA plan in a read-only sandbox.

### Decisions and verification

* **Product Owner (Human):** Decides product behavior and resolves missing or conflicting business requirements recorded by agents.
* **Read-Only Sandbox:** An execution environment that prevents an agent from modifying the files it inspects.
* **CI/CD (Continuous Integration / Continuous Deployment):** Automated checks and deployment steps. This workflow uses GitHub Actions to test published commits and feeds failures back to the Implementer.
