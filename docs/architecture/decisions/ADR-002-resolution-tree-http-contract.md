# ADR-002 ? Fresh resolution detail with explicit calculation inputs

## Status

ACCEPTED ? 2026-09-25. Class C: architecturally significant technical decision.
Requires Product Owner decision: NO.

## Context

AR-002 asks for the tree transport and calculation lifetime needed by Phase 5.
CURRENT_ARCHITECTURE sections 5.5/5.6 establish request-local services and table
rows without trees. Discovery's empty reload can retain instance lookup state.
STORY-WEB-001's Result confirms the browser table has no tree contract.
DOMAIN_SPEC sections 42?44 require meaningful failure states and explanations.

## Constraints

Backend/domain authority, JavaFX coexistence, one configured account, incremental
migration, no speculative infrastructure, and the complete-page performance gate
remain binding. UD-007 requires measured execution-policy decisions. Historical
snapshot retrieval is not an established requirement in the dispatched question.

## Decision

Use independently calculated detail from explicit feature-specific inputs and
recipe identity. TARGET_ARCHITECTURE section 13 owns the exact contract, context
lifetime, single-craft explanation basis, browser association and failure rules.
Do not introduce retained calculation sessions. The returned explanation and
fresh summary use the operation's captured data, with an explicit distinction
between the single-craft tree and the simulation summary.

## Rationale

**Architectural judgment:** fresh detail is the simplest fit for this small,
single-user migration. It extends the existing request isolation and avoids
session eviction, memory quotas, restart recovery, expiry responses and coupling
between a table request and later selection. Explicit effective inputs and local
browser generations make association testable by bounded implementation changes.
It adds no dependency, service or operational owner. The cost is repeated loading
and computation, and no promise of reproducing an earlier table result.

A single-craft explanation keeps the existing selected-recipe explanation scale
without transmitting a potentially large trace of every simulated craft. It must
be labelled precisely and produced by domain resolution; it cannot be treated as
the explanation of aggregate simulation costs. This is transport/explanation
structure, not a change to simulation counts, eligibility or economic rules.

Inspection of craft.RecipeTreeBuilder shows dependency expansion using the first
producing recipe and string actions, without inventory/buy/cost resolution.
Therefore serializing the current Node is insufficient. This observed limitation
requires planner-owned follow-up; this ADR neither fixes it nor claims a semantic
trace already exists.

## Alternatives Considered

- **Retained calculation ID/context (runner-up):** supports exact prior-table
  association if all required inputs and results are retained immutably. Useful
  when historical consistency is required, but adds expiry, ownership, cleanup,
  concurrency and restart semantics without that requirement here. Rejected on
  complexity, not an unmeasured performance claim.
- **Embed every tree in table responses:** gives one response lifecycle but
  forces unselected tree work and larger transport into full-page loading. No
  evidence justifies this against the existing lazy-detail design and timing gate.
- **Frontend reconstruction / raw dependency skeleton:** simpler to serialize,
  but cannot satisfy backend authority for actual sourcing and special states.

## Consequences

Fresh detail can differ from the table; its provenance and basis must be visible.
Repeated work requires real measurements, not a speculative cache or task design.
If exact historical retrieval becomes required, a later decision must establish
retention and snapshot semantics; that would change the lifecycle but can reuse
the semantic tree payload. No new library is selected, so release cadence and
vendor support introduce no additional maintenance obligation in this decision.

The planner owns domain trace/application/API/browser implementation and targeted
verification. Current architecture remains unchanged by this documentation-only
run. The existing dependency-only tree limitation and future synchronization
snapshot consistency must not be disguised as guarantees of this contract.

## References

- [Target architecture](../../TARGET_ARCHITECTURE.md), sections 12?14, 23, 33.
- [Current architecture](../../CURRENT_ARCHITECTURE.md), sections 5.5, 5.6, 5.11.
- [Domain specification](../../DOMAIN_SPEC.md), sections 42?44 and invariants.
- [Roadmap](../../ROADMAP.md), Phase 5.
- [UD-007](../../../agent/user-decisions/UD-007-http-long-running-sync-approach.md).
- [AR-002](../../../agent/architect-requests/AR-002-resolution-tree-http-contract.md).
- Source inspection: craft.Node, craft.RecipeTreeBuilder, application crafting
  services and web.dto crafting requests/responses. No runtime measurements made.
