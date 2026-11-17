# Historical project records

This archive preserves retired workflow records, previous architecture documents, completed planning contracts and dated investigations. GitHub issues, pull requests, reviews and CI are the current execution record. These files do not form an executable queue; old TODO, BLOCKED, phase, authorization and harness instructions describe their original context.

Current behavior belongs in [the domain specification](../DOMAIN_SPEC.md), current structure and constraints in [Architecture](../ARCHITECTURE.md), and the implemented development process in [Development workflow](../DEVELOPMENT_WORKFLOW.md). Moving a decision here does not reverse a product rule: applicable decisions have been carried into those maintained documents.

## Contents

| Location | Historical material |
| --- | --- |
| `agent/stories/` | Legacy implementation contracts and their original milestone archives. |
| `agent/architect-requests/` | Questions and responses from the retired architect dispatch mechanism. |
| `agent/user-decisions/` | Product Owner decisions and their original supporting discussion. |
| `agent/product-owner-requests/` | Original requests; current work is described in GitHub and the roadmap. |
| `architecture/decisions/` | Architectural rationale, including accepted and superseded decisions. |
| `architecture/CURRENT_ARCHITECTURE.md` | Structural snapshot before consolidation into the maintained architecture. |
| `architecture/TARGET_ARCHITECTURE.md` | Former migration target and selected design constraints. |
| `investigations/` | Retired capacity plans, dated observations, optimization reports and experiment evidence. |
| `quality/` | Retired coverage snapshots, tied to their original commits. |
| `GW2 GitHub Flow Migration.md` | Rationale and stages of the completed GitHub workflow migration. |

The `pre-github-flow` Git tag additionally preserves the retired Python harness and its runtime. Do not restore a scheduler, resource-test programme, old credential policy or legacy desktop requirement merely because it appears in an archived file.

Historical relative links are retained or repaired where their target still exists. Original code paths, section numbers, issue status and past test commands can describe an earlier commit. Use the recorded revision when reproducing historical evidence.
