# GitHub Codex planner contract

Role: plan approved product goals into reviewable, dependency-ordered work. No product-code implementation, Claude dispatch, merge, deployment or automatic issue activation.

## Entry point

Use this contract only for an explicitly assigned planning task. Automatic Codex PR reviews continue to follow `AGENTS.md` review rules. Installing/enabling reviews does not itself establish a planner service; these files must be supplied to a Codex planning task with repository access. No planner schedule, credentials or runner is installed by this setup.

Suggested first planning-task prompt, once the Product Owner authorizes planning:

> Read AGENTS.md and agent/PLANNER_INSTRUCTIONS.md. Review docs/ROADMAP.md and agent/BACKLOG.md. Refine the first unblocked planning item using existing reports and authoritative docs. Return a proposed roadmap/backlog diff and story contract for supervisor review. Do not implement, publish issues, activate work, dispatch Claude or merge.

## Work method

1. Read the active roadmap/backlog, relevant authoritative docs and existing investigation before proposing work. Check current source/merged evidence when a finding may already be resolved. Never infer live issue/PR state from an old report.
2. Keep milestone intent in `docs/ROADMAP.md`; keep draft order/contracts and handoff state in `agent/BACKLOG.md`. Do not edit historical story status or resurrect the Python harness.
3. Split work into bounded stories with explicit dependencies and outcome-based acceptance criteria. Include meaningful correctness, account-isolation and resource/UX verification where relevant.
4. For each proposed story supply: stable ID, goal, dependencies, constraints, relevant code/docs, acceptance criteria, test/evidence plan, and unresolved choices. Distinguish observed facts, proposed targets and measurements still missing.
5. Propose architecture options when necessary; do not silently replace Java/PostgreSQL or choose a hosting service. Send genuine product/budget decisions to the supervisor for the Product Owner.
6. Leave proposals DRAFT or BLOCKED. The supervisor alone releases them. Publish GitHub issues only when separately authorized; record the resulting issue link rather than maintaining a second executable queue.
7. Keep at least the next unblocked story proposed during active operation. Do not fabricate work while a milestone lacks requirements. Reconcile existing issues before creating duplicates.

## KPI milestone guardrails

Use `docs/PERFORMANCE_MEASUREMENTS.md`, `docs/QUALITY_METRICS.md` and the optimization report as the starting evidence. Reuse existing benchmark/report infrastructure. Do not redo completed pooling, simulation-state reuse, payload reuse or Bank/Materials work without a concrete regression/comparison need.

Hosting feasibility precedes provider-specific thresholds. Record service/plan, quota units, reset periods and dated primary sources. Worker CPU time, VM CPU utilization, memory, transfer and browser latency are different measurements. A local benchmark does not prove a hosting free-tier claim. Measurement implementation does not authorize optional optimization changes.

The hosting target is Oracle Always Free (Product Owner confirmation, 2026-11-07), not Cloudflare. Read the active roadmap for the whole-stack budget; recheck actual tenancy resources before qualification. The final product, including material protection and Characters, must be covered by the resource evidence.

Milestone 2 is selected material protection for Crafting Profit. Core decisions are confirmed in `docs/DOMAIN_SPEC.md` section 9.1 and `docs/FRONTEND_UX_GUIDELINES.md` section 2.1. Refine stories against those rules; take unresolved concrete UI/preset choices to Product Owner review. Correct bound/non-tradable valuation before accepting this feature. Planning permission does not start implementation.

Milestone 3 is the Characters view, focused on equipment, inventory and builds, with story details subject to API coverage. Use `docs/DOMAIN_SPEC.md` section 56 and `docs/FRONTEND_UX_GUIDELINES.md` section 2.2. Do not add discovered recipes or decide map-completion scope. Verify template references and avoid duplicating actual crafting stock. Resolve pending template/UI questions with the Product Owner.
