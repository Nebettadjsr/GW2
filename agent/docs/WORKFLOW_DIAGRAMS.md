# Workflow Diagrams

This document visualizes the state machine, decision trees, and lifecycle of the AI Development Orchestrator.

## 1. The Core SDLC Cycle

This flowchart represents the main loop managed by the local bridge and n8n. It highlights the strict transition rules defined in `local_bridge/cycle.py`.

```mermaid
stateDiagram-v2
    [*] --> plan

    plan --> select: Generate Backlog actions / Answer Architect requests

    select --> qa: Queue has 'To Do' items
    select --> plan: Queue Empty (Trigger Project Planner)

    qa --> implement: Test Plan Generated
    qa --> blocked: Needs Product Owner / Human input

    implement --> evaluate: Claude writes code

    evaluate --> publish: Codex Approves Code
    evaluate --> implement: Codex Rejects Code (RETRY with findings)
    evaluate --> blocked: Evaluator Needs Human Input

    publish --> ci: Commit code to Agent Branch

    ci --> finalize: GitHub Actions Pass
    ci --> implement: GitHub Actions Fail (Return to Coder with log)

    finalize --> select: Mark Story 'Done'

    blocked --> select: Human Resolves Blocker
```

## 2. Milestone Happy Path

This sequence diagram illustrates how a specific milestone is processed from inception in the Roadmap to completion.

```mermaid
sequenceDiagram
    participant PO as Product Owner
    participant P as Planner Agent
    participant B as BACKLOG.md
    participant QA as QA Agent
    participant C as Implementer (Claude)
    participant E as Evaluator (Codex)

    PO->>P: Define Milestone in ROADMAP.md

    rect rgb(200, 220, 240)
        Note over P,B: Planning Phase
        P->>B: Read Roadmap & Project State
        P->>B: Output commands to generate Stories (To Do)
    end

    loop For Each Story
        rect rgb(220, 240, 220)
            Note over B,E: Execution Phase
            B-->>QA: Select Active Story
            QA->>QA: Generate Test Plan
            QA-->>C: Pass Test Plan to Implementer
            C->>C: Implement Code
            C->>E: Request Evaluation
            E->>C: Review Working Tree vs QA Plan
            E-->>B: Mark Story DONE
        end
    end

    P->>B: Verify Milestone Exit Criteria
    P->>PO: Report Milestone Complete
```

## 3. Human Intervention / User Decisions

The system is designed to pause and request human validation when faced with ambiguity, rather than hallucinating features.

```mermaid
flowchart TD
    A[Agent Encounters Ambiguity] --> B{What kind of ambiguity?}

    B -->|Technical/Structural| C[Architect Request]
    C --> D[Architect Agent Resolves]
    D --> E[Resume Pipeline]

    B -->|Business/Domain Rules| F[Product Owner Request]
    F --> G[Pipeline Blocks Active Story]
    G --> H((Human Product Owner))
    H -->|Provides Answer| I[Write Resolution to File]
    I --> J[Story Re-queued to To Do]
    J --> E
```
