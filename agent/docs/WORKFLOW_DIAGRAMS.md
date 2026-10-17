# Workflow Diagrams

These diagrams show the development cycle, milestone processing, and handling of unresolved questions.

## 1. The Core SDLC Cycle

The local bridge and n8n run this loop. Transitions are defined in [`cycle.py`](../../local_bridge/cycle.py). Planning runs when triggered, with actionable Architect Requests dispatched before a Planner pass. The diagram omits transport errors and internal correction retries; the [bridge guide](../../local_bridge/README.md) describes those outcomes.

```mermaid
stateDiagram-v2
    [*] --> dispatch
    dispatch --> plan: Planning or architecture work needed
    dispatch --> select: No background work needed
    plan --> dispatch: Validated pass completed
    plan --> stopped: Planning failed after correction retry
    select --> qa: Activate first eligible story
    select --> stopped: No eligible story
    qa --> implement: READY or NO_TESTS_NEEDED plan
    qa --> blocked: Product decision or QA technical failure
    implement --> qa: QA plan missing
    implement --> implement: Capacity wait or interrupted attempt
    implement --> evaluate: Coding attempt completed
    implement --> blocked: Consecutive failure budget exhausted
    evaluate --> publish: COMPLETE
    evaluate --> implement: Actionable RETRY within budget
    evaluate --> blocked: BLOCKED, NEEDS_USER, or retries exhausted
    publish --> ci: Push succeeded and SHA recorded
    publish --> publish: Publication failed; rerun later
    ci --> finalize: PASSED or verification SKIPPED
    ci --> implement: Relevant failure within CI-fix budget
    ci --> ci: Result unknown or failure outside story scope
    ci --> blocked: CI-fix budget exhausted
    finalize --> dispatch: Mark DONE and publish lifecycle updates
    blocked --> stopped: Clear pointer; later run can select other work
```

Wait loops mean that the phase is preserved for a later run; they do not mean continuous model calls. A resolved decision or intervention is requeued by the harness before selection. Agent-workflow CI failures are reported separately from application checks.

## 2. Milestone without retries or blockers

This sequence shows responsibilities on a successful path. Python activates and finalizes stories; the Evaluator supplies a verdict. Milestone completion is a later Planner review against roadmap exit criteria.

```mermaid
sequenceDiagram
    participant PO as Product Owner
    participant P as Planner Agent
    participant H as Python Harness
    participant B as BACKLOG.md
    participant QA as QA Agent
    participant C as Implementer (Claude)
    participant E as Evaluator (Codex)
    participant CI as GitHub Actions

    PO->>P: Define Milestone in ROADMAP.md

    rect rgb(200, 220, 240)
        Note over P,B: Planning Phase
        P->>P: Read roadmap, project state, and requests
        P->>B: Add stories through backlog_writer
        H->>B: Validate planning changes
    end

    loop For Each Story
        rect rgb(220, 240, 220)
            Note over B,E: Execution Phase
            H->>B: Activate first eligible story
            H->>QA: Prepare or reuse QA plan
            QA-->>H: Validated plan and prepared tests
            H->>C: Run story with QA contract
            C-->>H: Working tree changes and result
            H->>E: Evaluate changes against story and plan
            E-->>H: COMPLETE verdict
            H->>CI: Commit, push, and check published SHA
            CI-->>H: Application checks pass
            H->>B: Mark DONE, clear pointer, publish updates
        end
    end

    P->>P: Review completion evidence and exit criteria
    P->>PO: Report Milestone Complete
```

## 3. Human Intervention / User Decisions

The Planner queues architecture questions for a separate Architect invocation. Product questions require human answers in User Decisions. QA clarification blocks its story, while independent work can continue. The [role router](../../AGENTS.md), [QA contract](../qa-plans/README.md), and [runtime guide](../runtime/README.md) define these boundaries.

```mermaid
flowchart TD
    A[Unresolved requirement] --> B{Who must decide?}
    B -->|Architecture| C[Planner creates Architect Request]
    C --> D[Separate Architect invocation]
    D --> E{Within Architect authority?}
    E -->|Yes| F[Record architecture answer]
    E -->|Human decision needed| G[Open User Decision]
    B -->|Product behavior| G
    G --> H[Human records resolution]
    H --> I[Dependent work can be reconsidered]
    I -->|Architect Request was waiting| D
    F --> J[Consume resolved architecture in planning]
    I -->|Blocked story| K[Harness requeues after resolution checks]
    K --> L[Story becomes eligible when all prerequisites are met]
```
