# ADR-003 - Separate requested recipe identity from actual root sourcing

## Status

ACCEPTED - 2026-09-25. Class C: architecturally significant technical decision.
Requires Product Owner decision: NO.

## Context

AR-003 exposes an ambiguity in the detail contract established by ADR-002:
requested recipe identity and actual producing recipe identity need not coincide.
CURRENT_ARCHITECTURE section 5.12 and STORY-DOM-020's Result document that the
existing explanation preserves both identities. KNOWN_PROBLEMS CH-15 documents
finished stock or another recipe satisfying the requested output. DOMAIN_SPEC
section 28 still defines craftable count as executions of the requested recipe;
section 44 requires explanations of actual calculation choices.

## Constraints

Preserve authoritative domain evaluation, captured request-local inputs, truthful
quantities and costs, table compatibility and JavaFX coexistence. A transport
mapper cannot correct domain economics or manufacture an executed recipe.
Existing behavior is evidence of a defect, not authority to redefine product intent.

## Decision

Separate selection identity from sourcing identity, and describe the tree as one
output requirement rather than promising execution of the requested recipe.
TARGET_ARCHITECTURE sections 13.3-13.5 own the exact revised wire semantics,
browser obligations and migration constraints; they supersede ADR-002's earlier
single-craft wording on this point. Other ADR-002 decisions remain in force.

CH-15 correction is not an architectural prerequisite for delivering a truthful
explanation of current calculation results. It remains domain debt under its
existing owner, with no change to the normative definition of craftable count.

## Rationale

Architectural judgment: separate identities faithfully expose facts already
available from the domain and preserve the incremental migration boundary.
Changing the basis literal before the documented HTTP/browser integration avoids
teaching consumers that supplied output proves execution. No dependency, process,
retained state or additional calculation engine is needed. The contract remains
useful after a domain correction because selection and actual sourcing are
separate responsibilities even when their recipe IDs coincide.

This resolves a representation conflict without deciding new product behavior.
The intended execution rule is already established by DOMAIN_SPEC section 28;
this decision neither reverses it nor marks the current calculation compliant.

## Alternatives Considered

- **Require CH-15 correction before detail (runner-up):** aligns current results
  with intended requested-recipe execution sooner, but couples an explanation
  integration to a change in shared simulation economics. A truthful detail
  contract does not technically require that dependency. The planner retains
  responsibility for defect work and applicable completion gates.
- **Retain SINGLE_CRAFT with a qualified description:** minimizes a prospective
  enum change but leaves a misleading execution claim in the machine contract.
  The documented detail routes and browser integration do not yet exist, so a
  precise basis now has less compatibility cost than later reinterpretation.
- **Force requested identity in mapping or reconstruct its ingredients:** rejected
  because it would misrepresent sourcing, quantities and costs and create a second
  calculation authority. Hiding valid traces when identities differ likewise
  conceals the behavior users need explained.

## Consequences

Consumers must distinguish requested selection from actual root sourcing. Detail
can reveal a known defect in the row calculation; it must not claim to fix it.
No table shape or JavaFX call boundary changes. The planner owns bounded API/browser
integration, verification of differing identities and separate disposition of
CH-15. No milestone acceptance, runtime validation or performance result is claimed.

## References

- [Target architecture](../../TARGET_ARCHITECTURE.md), sections 13.2-13.5.
- [Current architecture](../../CURRENT_ARCHITECTURE.md), section 5.12.
- [Domain specification](../../DOMAIN_SPEC.md), sections 28 and 44.
- [Known problems](../../KNOWN_PROBLEMS.md), CH-15.
- [Original contract rationale](ADR-002-resolution-tree-http-contract.md).
- [Trace result](../../../agent/stories/STORY-DOM-020-semantic-resolution-trace.md).
- [Dispatched request](../../../agent/architect-requests/AR-003-resolution-root-identity.md).
