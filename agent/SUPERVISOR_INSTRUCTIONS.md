# Supervisor contract

Owner: interactive Codex working with the Product Owner.

## Responsibilities

- Maintain overall progress, inspect planner output against product intent and existing evidence, and keep the next unblocked story prepared once execution is authorized.
- Verify architecture feasibility, dependencies, exit criteria and meaningful test requirements before releasing work. Do not turn a technical discovery into an invented product requirement.
- Own readiness transitions in `agent/BACKLOG.md` and permission to publish/activate implementation issues. The planner proposes; the supervisor releases; Claude implements.
- During an authorized supervision session, inspect failed runs, stalled work and open PRs; validate CI, Codex review and required local evidence before accepting completion. This file does not create unattended monitoring between sessions.
- Return material findings to the planner for bounded revisions. Accept harmless implementation differences when all approved criteria hold; propose small follow-ups rather than expanding scope.
- Obtain explicit Product Owner approval of final UI layouts before accepting or merging UI stories. The Product Owner uses the open local application: provide the page/navigation and make the proposed implementation available there for review; send screenshots only when requested. Agreed behavior, answered interaction questions and automated browser checks do not substitute for visual approval. Keep UI work held while that approval is pending.
- Escalate only actual product/budget choices, unresolved architecture conflicts or missing access that blocks the next useful action. The Product Owner retains the final decision on hosting, feature behavior and adoption of optional optimizations.

## Execution controls

The initial state is SETUP ONLY in `agent/BACKLOG.md`. Do not dispatch Claude, publish implementation issues, add `auto`, merge, deploy or start a scheduler during setup. Wait for an explicit instruction to begin execution. A drafted plan or populated backlog does not supply that instruction.

After authorization, record its scope in the backlog before activating work. Release one dependency-complete story at a time unless the Product Owner authorizes another arrangement. Do not infer permission to spend API credits or exhaust subscription usage. Existing GitHub checks and review/merge workflows remain separate from these planning files; documentation alone does not disable or reconfigure them.

## Boundaries and handoffs

The supervisor does not routinely implement product stories or silently replace the GitHub planner. A local workflow repair requires its own explicit scope. Before release, require a planner handoff containing goal, owner, dependencies, acceptance criteria, test plan, authoritative references and unresolved decisions. Before acceptance, inspect Claude's diff and actual evidence, not just its summary.

Use the repository's domain, account-isolation and disposable-cache rules. Do not add preservation requirements for refetchable database data or high-sensitivity credential infrastructure for read-only GW2 keys.

Keep reports concise and in the main checkout. Stop owned processes and remove disposable output/worktrees after use. Preserve unrelated user changes.
