# Comparing development workflows

This document compares ways to organize AI-assisted software development. [System architecture](ARCHITECTURE.md) describes what this repository implements; [project history](EVOLUTION.md) explains how it got here. The judgments below are architectural analysis, not a benchmark or a claim that more agents produce better software.

## What is being compared?

A single agent can use durable files, tests, a sandbox, and CI. A multi-agent system can lack all of those. Agent count, persistence, permission boundaries, and who controls the sequence are separate choices. The comparison is between configurations, rather than treating a product name as proof of one fixed design.

| Approach | Advantages and suitable work | Disadvantages, complexity, and costs |
| --- | --- | --- |
| **One coding agent with human review** | Short handoffs and one continuing task context. Fits a bounded fix when a developer can define the task and inspect the diff. Testing and CI can remain external requirements. | The human carries task selection and final judgment. The same agent may repeat its assumptions when reviewing itself. Long sessions accumulate context; low agent count does not guarantee low usage. |
| **One coding agent inside a scripted test/retry loop** | Automates repeated execution without introducing every planning role. Fits a known queue of issues with clear tests. Explicit counters and persisted stages are possible. | Passing tests can miss an incorrect rule or unwired feature. Retry failures need classification to avoid spending usage on capacity or infrastructure problems. Scripts still require maintenance. |
| **A coder plus a separate reviewer** | Adds a distinct review pass and feedback without a full planning pipeline. Fits work where the human already supplies reliable task contracts. | Additional context loading and review calls. Review can miss defects or reject valid work. Someone must define the criteria, arbitrate disagreement, and cap retries. |
| **This repository's sequential, role-based workflow** | Carries work from roadmap planning to published stories. Gives product feedback, pre-code QA, repository review, recovery, and document reconciliation explicit owners. | More stages, files, validators, CLI integration, and a custom Python service to maintain. Grafana adds a separate monitoring dependency. Handoffs can lose context. Stories run serially, so separate roles do not provide coding throughput through parallelism. |
| **A manager agent delegating to workers** | Can discover subtasks dynamically and run independent investigation or implementation in parallel. Fits work whose decomposition is not known beforehand. | Delegation and synthesis consume context and model calls. Concurrent writers need separate workspaces and integration rules. A manager can delegate badly or combine individually plausible but incompatible answers. |

For a concrete example of checks in a simpler coding tool, [Aider supports configured lint/test commands and attempts to repair failures](https://aider.chat/docs/usage/lint-test.html). Those capabilities are not exclusive to a multi-role pipeline. The manager/worker and evaluator feedback patterns are described in [Anthropic's pattern guide](https://www.anthropic.com/engineering/building-effective-agents); the suitability judgments here are our analysis of their consequences for this repository.

## Custom runtime versus a framework

This repository implements its own state machine around CLI processes and Git files. That makes story ownership, publication scope, and dependency rules directly inspectable in local Python code. It also makes this project responsible for maintaining persistence, result parsing, retries, process recovery, and framework-like glue.

A graph or workflow framework could host a similar sequence. [LangGraph provides checkpointers and stores](https://docs.langchain.com/oss/python/langgraph/persistence); durable recovery depends on the selected backend, and its in-memory checkpointers do not survive a restart. [CrewAI Flows supports state and persistence through `@persist`](https://docs.crewai.com/en/concepts/flows#flow-persistence). Neither alternative is inherently an in-memory conversation that loses all progress.

A framework would provide reusable orchestration facilities and introduce its own APIs, execution semantics, dependencies, and upgrades. It would not supply this repository's product authority, protected-test policy, backlog schema, or Git publication rules automatically. Moving to one would relocate some runtime work and still require those project-specific contracts. The previous n8n driver hosted the outer loop, while Python already held the repository-specific policy. The new runner brings that loop into the same Python service.

## Execution and visibility are separate choices

The maintainer adopted n8n to see execution and inspect outputs after the original Python orchestrator became difficult to follow. The implemented n8n graph, however, repeated a generic step request and wait. Actual planning, coding and evaluation were only identified in its returned data. Replacing that loop does not require moving the existing policy into a second implementation.

| Arrangement | What it provides | Cost or limit in this project |
| --- | --- | --- |
| **Python engine with the previous n8n driver** | Existing visual run controls, schedule, budget table and inspection of returned outputs. | An additional container and callback boundary. The executed graph shows the request loop rather than the actual development stages. |
| **Move stage logic into n8n** | Could make the executed graph correspond to workflow decisions if those decisions were actually moved into nodes. | Would require migrating and verifying selection, retries and recovery, rather than duplicating a node for each label. Host model tools, Git and repository operations would still need an execution boundary. Maintaining policy in both Python and n8n would risk disagreement. |
| **Python runner with Grafana, implemented here** | One Python execution path and an editable Canvas showing the active stage from service state. Monitoring can stop without stopping work. | The Python service still owns scheduling and recovery. Grafana is a separate service, and its diagram is maintained manually. It currently provides no run buttons or budget editor. |
| **Python runner with console output only** | Fewer moving parts and direct access to streamed activity. | The maintainer must read text to identify progress; it does not meet the requested overview of active and failed stages. |

Grafana was selected to provide that overview without building a custom browser application. It does not generate an executable graph from the code. A new or renamed stage can still require a Canvas edit, and the current view is not a full project-history store. The [architecture guide](ARCHITECTURE.md#execution-and-visibility) explains the implemented boundary and its deployment status. No measurement here establishes that removing n8n reduces model usage or makes the underlying agent decisions more accurate.

## Human feedback and replanning

In the maintainer's experience, automated implementation freed time for intensive feature testing. That testing led to bugs and new ideas, with Product Owner requests providing a route to revise requirements and scope. The Planner updates the authoritative documentation before turning requests into executable work, subject to phase constraints. The [feedback diagram](WORKFLOW_DIAGRAMS.md#human-testing-and-scope-changes) shows the process.

This automates part of the coordination work; it does not eliminate the need to decide intent, check consistency, or resolve dependencies. Human-led teams can also support frequent feedback and changing scope. The cost of replanning depends on their tooling and practices, so this project's experience does not establish that comparable changes would generally be a major problem in a conventional company workflow.

## Cost and the current bottleneck

The current practical constraint is Claude's allowance on the maintainer's reported $20 plan. It is frequently exhausted; Codex allowance is not currently a constraint. Work to reduce Claude consumption is still under investigation. The existing [budget checks](ARCHITECTURE.md#current-implementation-bottleneck) pace the allowance and preserve work while waiting; they do not remove the bottleneck.

A fresh behavior story normally invokes QA, implementation, and evaluation. Planner and Architect runs are conditional; so are sync review and post-implementation QA review. A retry repeats some of those stages. Each invocation may contain multiple requests, and each role needs enough context to do its job. A reused QA plan avoids a new preparation pass.

Moving preparation and review to Codex means those roles do not directly consume Claude's allowance. It can also produce correction prompts that require another Claude attempt. A clearer plan might reduce coding rework, but there is no controlled comparison here establishing the net effect on Claude usage.

More generally, splitting roles can reduce the information needed for one task while increasing repeated context across handoffs. Parallel workers can reduce elapsed time for independent tasks while increasing simultaneous usage and integration work. Local inference trades hosted usage for local compute and operation; a smaller model may also need more corrections. None of these choices has a universal cost ranking. Subscription allowance, model tokens, elapsed time, and maintenance effort are different costs, and this repository has no like-for-like result proving the full pipeline cheaper or more accurate than the alternatives above.
