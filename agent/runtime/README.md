# Agent runtime

Run from the repository root:

```powershell
python -m agent.runtime.core.orchestrator
python -m unittest discover -s agent/runtime/tests -t . -p 'test_*.py'
```

The orchestrator command starts the live workflow. Tests use fixtures and mocks;
run tests separately from live orchestration. Individual suites can be run as
`python -m unittest agent.runtime.tests.test_orchestrator -v`.

| Folder | Responsibility |
| --- | --- |
| `core/` | Workflow orchestration, planning, story selection/state and archiving. |
| `runners/` | Claude and planner process execution, output and capacity handling. |
| `evaluation/` | Dispatch/evaluation prompts and the Hermes/Ollama client. |
| `human/` | Product Owner requests, user decisions and intervention records. |
| `support/` | Shared configuration, paths and file helpers. |
| `tests/` | Runtime regression tests and temporary fixtures. |
| `artifacts/` | Generated results and the next execution prompt. |

Python modules and tests are maintained source. Use `agent.runtime.*` package
imports; do not add module directories to `sys.path` or duplicate modules at
the runtime root. All repository and artifact paths belong in
`support/config.py` and are resolved independently of the working directory.

`artifacts/` contains `CLAUDE_RESULT.md`, `DISPATCH_RESULT.json`,
`EVALUATOR_RESULT.json`, `PLANNING_RESULT.json`, `SELECTOR_RESULT.json`, and
`NEXT_PROMPT.md`. Claude owns its implementation result; dispatch/evaluation,
planning, selection and orchestration own their respective generated outputs.
These files are local runtime state, ignored by Git, and are not authoritative
requirements or reusable test fixtures. The tracked `.gitkeep` preserves the
directory in a fresh checkout. Python bytecode caches are also ignored.

Authoritative stories, the current-story pointer, backlog, planning documents,
PO requests, user decisions and interventions remain in their existing locations
outside this package. Their ownership rules remain in `CLAUDE.md` and
`agent/PLANNER_INSTRUCTIONS.md`.

Place new runtime files by responsibility, not in the root. Keep shared helpers
small, tests separate from production code, and generated outputs separate from
source. Avoid duplicate state, compatibility copies and redundant abstractions.
