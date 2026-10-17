# AI-assisted development workflow

This workflow develops the GW2 crafting application against an existing roadmap and domain specification. AI agents plan small implementation tasks, prepare tests, write code, and review the result. Python controls task selection and completion; the human Product Owner directs product behavior and can change scope through requests.

An **agent** here is a model running with tools and a specific role. The roles run in separate invocations, through one bridge task at a time. This is a sequence of work and checks, rather than several developers coding in parallel.

```mermaid
flowchart TB
    R[Roadmap, domain rules, and human requests] --> P[Planner: define stories]
    P --> Q[QA: prepare checks and tests]
    Q --> C[Implementer: change code]
    C --> E[Evaluator: inspect result]
    E -->|Revise| C
    E -->|Accepted| CI[Publish and check CI]
    CI --> D[Harness: mark done]
    D --> T[Human tests features]
    T -. Feedback and requests .-> P
```

n8n schedules the work and calls a local Python bridge. The bridge runs the agents, records progress, enforces file boundaries, and handles retries. Tests and GitHub Actions check the implementation. Missing product rules become questions for a human; unrelated work can continue. The automated implementation leaves the maintainer more time to test features and feed bugs and ideas back into planning.

The project began with AI-assisted pair programming, then paused after recurring bugs and tangled business logic made progress difficult. The maintainer established the specifications and roadmap before starting with Codex as Planner and Claude as coder. The later roles and safeguards grew from practical problems. n8n made the expanding workflow visible so the maintainer could keep understanding and control of the process.

Claude's usage allowance is currently the implementation bottleneck on the maintainer's reported $20 plan. Codex allowance is not currently a constraint. Work on reducing Claude usage is still under investigation; the budget controls pace consumption but do not remove that limit.

The reading guides below live in **`agent/docs/`**. Role instructions in `agent/`, such as `ARCHITECT_INSTRUCTIONS.md`, are execution contracts. Use these direct links for explanations of the system.

| Start with | What it explains |
| --- | --- |
| [agent/docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How this implementation works, why its boundaries exist, and document ownership. |
| [agent/docs/COMPARISON.md](docs/COMPARISON.md) | Single-agent and multi-agent approaches, their costs, limitations, and suitability. |
| [agent/docs/WORKFLOW_DIAGRAMS.md](docs/WORKFLOW_DIAGRAMS.md) | Milestone execution, human testing and feedback, decision authority, and failures. |
| [agent/docs/EVOLUTION.md](docs/EVOLUTION.md) | The maintainer's account and a few supporting Git snapshots. |

For operation and code details, use the [bridge guide](../local_bridge/README.md) and [runtime guide](runtime/README.md).
