# System architecture

## The system in plain terms

The workflow manages changes to a software product across multiple tasks. Its roadmap, domain specification, architecture documents, and initial problem list were established before the automated workflow started. A Planner turns the current roadmap phase into stories: bounded tasks with acceptance criteria. QA prepares checks before coding. An Implementer changes the repository, and an Evaluator inspects the result. Python decides which task may run next and when its status may change.

The human continues steering the product while it is being built. Testing implemented features produces bug reports, ideas, and requested behavior changes. Product Owner requests bring that feedback into planning; they are not the origin of every milestone. The [feedback diagram](WORKFLOW_DIAGRAMS.md#human-testing-and-scope-changes) shows that route, and [the comparison](COMPARISON.md) evaluates alternatives to this implementation.

This matters for a crafting application because a plausible result can still use the wrong domain rule. For example, a passing calculation test does not establish that the tested rule is the intended rule, or that the browser reaches that calculation. The design gives requirement questions, test preparation, and review explicit places in the workflow. It cannot make an incorrect specification correct.

### Components and communication

```mermaid
flowchart TB
    N[n8n in Docker: schedule, wait, and inspect results]
    subgraph PY[Python on the Windows host]
        B[Bridge: HTTP interface and persisted task state]
        T[Cycle and runtime: choose stage, execute, and validate]
        B -->|Dispatch saved or next stage| T
        T -->|Outcome and next phase| B
    end
    N -->|Request a step| B
    B -->|Immediate result or completion callback| N
    B <-->|Save and reload| S[(Local cycle records)]
    T -->|Run bounded tasks| A[Claude and Codex agents]
    T -->|Read, protect, and update| R[(Repository, QA plans, artifacts, and logs)]
```

The bridge is the Python service connecting n8n to the host checkout and installed agent tools. It calls the cycle and runtime modules in the same process. Python is the implementation language for those parts; n8n provides the visible execution loop.

| Part | Owns | Why it lives there |
| --- | --- | --- |
| **n8n** | Scheduling, budget-setting input, waiting, and the execution view. | Makes runs and their returned information inspectable by the maintainer. |
| **Bridge** | Authenticated requests, one active task, completion callbacks, and persisted cycle records. | Connects the container to local execution and retains progress independently of an n8n run. |
| **Cycle and agent runtime** | Stage selection, agent invocation, validation, file protection, lifecycle changes, Git, and CI handling. | Keeps repository rules in testable Python code, with one definition of eligibility and retry policy. |

Each n8n **Run Next Step** request can execute a different stage. Python selects it from saved state and current triggers; n8n records the returned stage, outcome, reason, and next phase, then loops or stops according to `proceed`. Long tasks finish through a callback. The canvas exposes that loop, while [workflow diagrams](WORKFLOW_DIAGRAMS.md) explain the story stages it drives.

![GW2 Development Pipeline in n8n](images/n8n-development-pipeline.png)

This separation gives the maintainer a visible workflow while keeping the detailed policy in Python. It also introduces an HTTP and process boundary to maintain. Internal agent tool use is inspected through the console and runtime records, beyond what the canvas shows. [State and recovery](#state-and-recovery) describes how those records support resumption and investigation; the [bridge guide](../../local_bridge/README.md#running-the-pipeline) and [exported workflow](../../local_bridge/workflows/GW2-Development-Pipeline.json) provide the operating details.

## Why the responsibilities are separate

These are reasons for the mechanisms, not claims that they eliminate the underlying risks. [Design evolution](EVOLUTION.md) distinguishes documented incidents from architectural reasoning.

| Problem or constraint | Mechanism and reasoning | Cost or remaining limit |
| --- | --- | --- |
| Product rules must not be chosen for coding convenience. | The Planner defines bounded stories; the Implementer works within them. Product ambiguities require a human User Decision. Architecture questions use a separate Architect invocation. | A story can still misinterpret intent. Unnecessary questions delay work; a model can fail to recognize a real ambiguity. |
| A completion report is a claim about code. | A separate Evaluator reads the working tree, acceptance criteria, and QA plan. It can request concrete corrections without changing the implementation. | Review adds model usage and latency. Different roles or vendors do not establish statistically independent judgments; both can share a mistaken assumption. |
| Tests prepared after coding can merely confirm the chosen implementation. | QA prepares a persistent plan and may write tests first. The harness restores QA-owned tests and plan contents if coding changes them. | QA can choose weak tests or a wrong expectation. Restoration protects the agreed test contract, not its semantic correctness. Conditional read-only QA review handles disputed or insufficient verification. |
| Queue selection and status changes have precise rules. | Python checks eligibility and owns lifecycle changes. The Planner requests backlog additions, moves, and reordering through JSON `backlog_actions`; the writer derives and validates index entries. | Validation can check structure and dependencies, not prove that prioritization or acceptance criteria are good. The runtime itself needs maintained tests. |
| Long-running work can be interrupted or hit a usage limit. | Persisted phases and counters let the bridge resume at a step boundary. Capacity waits keep the phase; actual failures use separate retry budgets. | An interrupted step may run again. File and Git side effects require recovery handling; saving JSON does not provide a transaction over the whole workflow. |
| Maintainer edits can arrive while an agent is implementing. | The pipeline uses an `agent/dev` worktree. Between stories it merges `origin/master` and reviews incoming changes against their owning documents. | Branch isolation prevents sharing a working tree, not semantic or merge conflicts. Sync, review, and publication add code and operational failure modes. |

The separation is useful only while the additional checks catch enough relevant problems to justify their overhead. A read-only Evaluator cannot fix a defect itself; every correction requires another handoff. A narrow change can therefore spend more time in preparation and review than in coding.

## Human feedback and changes of scope

The initial roadmap supplied rough milestones. Their details can be refined as work approaches, while product requests can add ideas, report bugs, or change intended behavior during development. The Planner reconciles a request with existing coverage, updates the owning requirement or planning document, and creates work when the phase rules permit it. A later-phase request is retained as future work rather than automatically bypassing the current phase.

This keeps requests actionable without using the request itself as a second requirements store. The Planner's resolution records where the intent is represented. User Decisions serve a different purpose: agents ask the human to settle missing or conflicting behavior. User Interventions record a need for human attention when execution cannot proceed automatically. See the [Product Owner request contract](../PLANNER_INSTRUCTIONS.md#product-owner-requests) for exact processing rules.

### Researching game mechanics

The maintainer permits both Planner and QA to consult the Guild Wars 2 Wiki when their current work depends on game mechanics. QA's [external-facts guidance](../QA_INSTRUCTIONS.md#external-facts-and-ambiguity) already includes the wiki. Relevant research should retain the source URL and supported claim and distinguish game facts from choices about what this application should do. Research can establish a recipe mechanic; it cannot decide an unspecified valuation or product policy. Conflicts with confirmed product requirements still need the appropriate human decision.

### Current implementation bottleneck

The maintainer reports using a $20 Claude plan and frequently exhausting its allowance. Claude coding capacity is currently the bottleneck; Codex allowance is not currently a constraint in this setup. This is the maintainer's operating experience, not a price guarantee or a claim that Codex has no limits.

The implementation checks Claude's session and weekly allowance before coding. It does not reserve capacity for the whole run or stop a run at the threshold. While Claude waits, triggered Codex planning and architecture work can continue. The quota controls pace the available allowance; they do not increase it. Reducing Claude usage remains under investigation. The [comparison's cost section](COMPARISON.md#cost-and-the-current-bottleneck) distinguishes that practical constraint from general trade-offs between workflow designs.

## Documentation ownership is part of the design

Two different questions matter: **where does a fact belong**, and **who may change that file**? The [documentation map in `CLAUDE.md`](../../CLAUDE.md#documentation-map) answers the first. [`DOCUMENT_OWNERSHIP` in `config.py`](../runtime/support/config.py) and the role contracts define write authority. This overview is a reading guide, not a second permission table.

| Reader's question | Authoritative home |
| --- | --- |
| What should the product do, and what should its player-facing terms mean? | [Domain specification](../../docs/DOMAIN_SPEC.md) and [crafting glossary](../../docs/crafting/GLOSSARY.md). |
| What exists now, and what architecture is intended? | [Current state](../../docs/CURRENT_STATE_SPEC.md), [current architecture](../../docs/CURRENT_ARCHITECTURE.md), and [target architecture](../../docs/TARGET_ARCHITECTURE.md). Current implementation alone does not decide intended behavior. |
| What work remains, and what does one task require? | [Roadmap](../../docs/ROADMAP.md), [known problems](../../docs/KNOWN_PROBLEMS.md), [backlog index](../stories/BACKLOG.md), and the linked story contracts. [Project state](../PROJECT_STATE.md) carries Planner continuity. |
| What standards apply to implementation and verification? | [Coding guidelines](../../docs/CODING_GUIDELINES.md), [test strategy](../../docs/TEST_STRATEGY.md), and the story's [persistent QA plan](../qa-plans/README.md). |
| Who decided an unresolved question? | [User Decisions](../user-decisions/), [Architect Requests](../architect-requests/), and [architecture decision records](../../docs/architecture/decisions/). |

Keeping intended and observed behavior separate is necessary when they disagree. A maintainer may intentionally change code without updating its descriptive documentation. The sync review can reconcile a stale description; it may not silently rewrite an authoritative domain rule or target decision to match the code. It raises the appropriate question instead. Copying the same rule into several overviews would create more places for disagreement.

The harness alone changes the active-story pointer and mechanical status transitions. The Planner can maintain the roadmap within its contract, but cannot resolve human decisions or act as the Architect. QA can write tests in approved roots; the Evaluator is read-only. The documentation sync review has its own bounded write policy in [`sync_review.py`](../runtime/core/sync_review.py). These are different permissions, not a single rule that every non-coding role is read-only.

## Implementation details and limits

### State and recovery

| Record | Purpose and lifetime |
| --- | --- |
| Bridge `tasks.json` | Task records, next phase, retry counters, changed paths, and published SHA. `GW2_BRIDGE_STATE` selects the path; the default is `%USERPROFILE%/.gw2-claude-bridge/tasks.json`, outside Git. |
| `CURRENT_STORY.md` and `BACKLOG.md` | Tracked active-story pointer and queue index. Eligibility also checks the canonical story and its prerequisites. |
| `agent/qa-plans/` | Tracked QA contracts and review records used across coding attempts. |
| `agent/runtime/artifacts/` | Ignored generated prompts, results, and backups for the current runtime. These are not authoritative requirements. |
| `agent/logs/` | Tracked append-only daily operational history, separate from generated artifacts. |

Progress is recorded outside the live model conversation and independently of n8n's current execution. The bridge saves its JSON state through a temporary file and atomic replacement. That avoids exposing a partially rewritten state file, but does not make a whole stage's file edits, model calls, and Git operations one transaction.

| Interruption | How work resumes | Limit |
| --- | --- | --- |
| n8n stops or its Wait times out | A later execution re-attaches to the bridge's still-running task instead of starting another. | This depends on the bridge and its saved records remaining available. |
| The bridge process restarts | Running tasks are marked interrupted; the next run repeats the saved stage. Interrupted coding receives a continuation prompt and retains its captured baseline. | Recovery is at stage boundaries, not the exact point where a process stopped. Some work may be repeated. |
| An agent exhausts capacity or fails | Capacity waits retain the phase. Other failures follow that stage's retry or blocking rules; validated plans and completed work are reused where the recovery path supports it. | An incomplete model response is not a completed result. Uncommitted edits can need inspection before another attempt. |

The daily log in `agent/logs/` records completed step outcomes, reasons, and workflow warnings. Alongside n8n's execution records, prompts and results in `artifacts/`, and the repository diff, it helps reconstruct where a run diverged from expectations. It is a decision trail, not a complete agent transcript; streamed coding output goes to the bridge console, and generated artifacts may be overwritten by later runs. These records make it possible to investigate a failure and target a change to the responsible workflow stage instead of restarting the project without understanding the cause.

Recovery can preserve finished work and return a derailed run to a coherent point: reuse a validated QA plan, retry evaluation without repeating completed coding, or reconcile the active-story pointer, backlog, and cycle record. Git history also allows tracked files to be recovered from earlier versions. The runtime already restores specific protected files around agent attempts, but a full rollback of the project and its execution state is a maintainer action, not an automatic time-travel feature. The local cycle file and ignored artifacts are outside Git; recovering an earlier combination requires those records or backups as well as matching tracked files. There is no guarantee of zero lost progress after every failure.

The [bridge recovery guide](../../local_bridge/README.md#recovering-by-hand) describes how to reconcile state, and the [runtime guide](../runtime/README.md#resuming-an-interrupted-step) explains saved attempts. Completion requires a usable verdict, publication, and the CI step; the code allows CI verification to be `SKIPPED` when unavailable, so DONE does not unconditionally mean CI passed.

### Branch integration and document review

The normal launcher uses the separate `agent/dev` worktree. Between stories, `sync` merges `origin/master`, at most once per 30-minute interval. A merge conflict is aborted and stops the run; a merge that would overwrite uncommitted work is deferred. Incoming changes beyond the dependency-only filter receive a bounded Codex `review` pass. It can update approved descriptive docs, open decisions or architecture requests, record problems, and give findings to the Planner. It cannot change application code or settle a product contradiction. Two invalid review attempts leave a workflow warning and the range for Planner attention.

After scoped publication and the CI step, finalization records DONE and pushes lifecycle updates on the agent branch, then attempts to fast-forward `master`. If `master` moved incompatibly, the push is rejected without force; the story remains complete on the agent branch and a warning records the outstanding integration. The next sync and story can carry both histories. This is branch publication, not proof of deployment to a running application.

The most useful code entry points are [`choose_step()` and stage functions](../../local_bridge/cycle.py), [Git synchronization](../runtime/support/git_sync.py), [sync review](../runtime/core/sync_review.py), [backlog actions](../runtime/core/backlog_writer.py), and [QA/evaluation details in the runtime guide](../runtime/README.md). The [bridge guide](../../local_bridge/README.md#branches) covers the actual worktrees and commands.
