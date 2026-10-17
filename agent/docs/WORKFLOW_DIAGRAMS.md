# How work moves through the system

Start with the milestone diagram for the overall process. The decision diagram explains who may settle a question; the failure diagram distinguishes correction, waiting, and blocking. The [architecture diagram](ARCHITECTURE.md#components-and-communication) shows the host processes and their connections.

## From product request to completed milestone

A story is one implementation task. A milestone contains several stories and has its own exit criteria. The loop below is run through one bridge task at a time. Planning and architecture calls occur when needed, not before every coding attempt.

```mermaid
flowchart TB
    PO[Human supplies product requests and decisions] --> P[Planner reviews current roadmap phase]
    P --> B[Stories with criteria and prerequisites]
    B --> S[Harness activates first eligible story]
    S --> Q[QA prepares or reuses plan and tests]
    Q --> C[Implementer changes code]
    C --> E[Evaluator inspects code against story and QA plan]
    E -->|Actionable correction within budget| C
    E -->|Accepted| PUB[Harness commits and pushes story scope]
    PUB --> CI[Check application CI for published commit]
    CI -->|Relevant failure within budget| C
    CI -->|Passed or verification unavailable and skipped| D[Harness marks DONE and publishes lifecycle updates]
    D --> INT[Attempt fast-forward publication to master]
    INT --> MORE{More eligible stories?}
    MORE -->|Yes| S
    MORE -->|No| CHECK[Planner reviews remaining work and phase exit evidence when triggered]
    CHECK -->|Missing work can be planned| B
    CHECK -->|Exit criteria met| NEXT[Record milestone transition; plan next phase separately]
    CHECK -->|Waiting on decisions or prerequisites| WAIT[Keep unresolved work recorded]
```

DONE is a harness transition, not the Evaluator's own file edit. CI verification can be explicitly skipped when unavailable; the code does not make a passing CI result unconditional. A rejected push to `master` leaves a warning and the completed story on `agent/dev`. Neither a milestone transition nor a branch push means the application has been deployed.

## Who makes which decision?

The human owns product intent. The Architect can settle technical architecture questions within its authority. Python owns eligibility and lifecycle transitions. A blocked question prevents dependent work from running; it does not automatically freeze the whole milestone.

```mermaid
flowchart TB
    R[Question arises while planning or checking work] --> KIND{What needs deciding?}
    KIND -->|Product behavior is missing or conflicting| UD[Open User Decision]
    UD --> H[Human records answer]
    KIND -->|Architecture question| AR[Planner queues Architect Request]
    AR --> A[Separate Architect invocation]
    A -->|Within authority| AD[Record architecture decision]
    A -->|Needs Product Owner answer| UD
    H --> DEP[Planner or Architect consumes answer; harness rechecks blocked dependencies]
    AD --> DEP
    KIND -->|Implementation meets story and QA plan?| E[Evaluator returns verdict]
    E --> T[Harness applies correction, publication, or blocking transition]
    KIND -->|Which story may run next?| S[Python checks queue order and prerequisites]
    DEP --> READY[Dependent work may become eligible]
```

QA can open a product clarification through the normal User Decision flow. A coding/evaluation failure can also produce a User Intervention requiring human attention. These records have different owners; an Architect answer is not permission for a model to resolve a User Decision. [Role contracts and ownership](ARCHITECTURE.md#documentation-ownership-is-part-of-the-design) define the exact boundaries.

## Failure does not always mean another coding attempt

```mermaid
flowchart TB
    F[Step cannot advance] --> TYPE{What happened?}
    TYPE -->|Actionable implementation defect| LIMIT{Relevant retry budget left?}
    LIMIT -->|Yes| FIX[Build correction prompt; return to Implementer]
    LIMIT -->|No| BLOCK[Block story and record intervention]
    TYPE -->|Missing product rule| UD[Record User Decision; block affected story]
    TYPE -->|Model capacity unavailable| KEEP[Keep phase; wait for a later run]
    TYPE -->|Bridge process interrupted| AGAIN[Repeat saved step; continue interrupted coding]
    TYPE -->|Push rejected or CI result unknown| KEEP
    TYPE -->|CI failure outside story scope| STOP[Stop for investigation; do not invoke coder]
    BLOCK --> CLEAR[Clear active pointer; later run can select independent work]
    UD --> CLEAR
    CLEAR --> ANSWER[After resolution, harness rechecks and requeues eligible work]
```

| Additional case | Behavior and reason |
| --- | --- |
| Invalid QA plan | Bounded correction retry with validation feedback. A QA technical failure blocks the story with a failure report; it does not invent a Product Owner question. |
| Evaluator cannot return a usable verdict | The evaluation phase is retained for recovery. A completed coding attempt does not need to be rerun just to obtain a verdict. |
| Coding changes protected tests, plan, or lifecycle files | The harness restores them and reports the finding. This can trigger independent read-only QA review. |
| Agent-runtime CI job fails | Recorded as a workflow warning separately from application acceptance. |
| Documentation sync review fails twice | The range and failure are handed to planning, with a workflow warning. |

These diagrams omit internal validation attempts and transport details. Separate counters exist for evaluation-driven coding retries, CI fixes, and consecutive non-capacity Claude failures. The [bridge guide](../../local_bridge/README.md) owns exact limits, outcomes, and recovery commands; the [runtime guide](../runtime/README.md) explains completed-attempt preservation and QA protection.

## How maintainer and agent work meet

The normal setup keeps maintainer edits on `master` and pipeline edits in the separate `agent/dev` worktree. Sync runs only between stories and when its interval is due. A review is needed when incoming files are not limited to the dependency-only filter.

```mermaid
flowchart TB
    M[Maintainer pushes master] --> SYNC[Between stories: merge origin/master into agent/dev]
    SYNC -->|Conflict| ABORT[Abort merge and stop for manual resolution]
    SYNC -->|Would overwrite uncommitted work| DEFER[Defer sync]
    SYNC -->|Merged changes need review| R[Codex reviews incoming range against owning docs]
    R --> DESC[Update permitted stale descriptive docs]
    R --> CON[Open User Decision or Architect Request for authoritative contradiction]
    R --> FIND[Report probable defect or bounded problem]
    DESC --> P[Findings feed Planner]
    CON --> P
    FIND --> P
    P --> STORY[Normal story cycle on agent/dev]
    STORY --> DONE[After CI step: finalize and push agent branch]
    DONE --> FF{Fast-forward master accepted?}
    FF -->|Yes| PUB[Completed work reaches master]
    FF -->|Master moved incompatibly| WARN[Keep story complete; warn and integrate on a later sync]
```

The sync reviewer does not choose a new product rule to make code and documentation agree. It can repair an approved description of an intentional change; a conflict with a binding specification goes to its decision owner. The [architecture guide](ARCHITECTURE.md#branch-integration-and-document-review) links the implementation and operating rules.
