## Status

RESOLVED

## Architecture Question

How should the Phase 5 resolution-detail contract represent root recipe identity when the existing authoritative resolver satisfies the requested recipe's output using owned finished stock or another recipe? TARGET_ARCHITECTURE section 13.3 defines node recipeId as the selected producing recipe (nullable without crafting), but also says the root identifies the requested recipe and describes SINGLE_CRAFT as execution of that selected recipe. Decide whether the HTTP contract must distinguish requested identity from actual root sourcing while preserving current domain behavior, or whether fixing requested-root execution is a prerequisite to shipping detail. Record any required clarification at the authoritative owner; do not permit a mapper to mislabel an actual trace.

## Context and Constraints

STORY-DOM-020 is DONE and deliberately preserves existing economics. Its Result and CURRENT_ARCHITECTURE section 5.12 document a separate requested recipe identity on the explanation and actual selected identity on the node. KNOWN_PROBLEMS CH-15 confirms that owned finished stock or an alternative recipe can satisfy the root. This is therefore a concrete integration conflict, not a hypothetical new feature. The planner cannot silently reinterpret AR-002's wire contract or authorize a domain change through transport mapping.

Preserve backend authority, truthful quantities/costs, request-local captured inputs, table compatibility and JavaFX coexistence. No competing resolver, synthesized recipe identity or frontend correction is acceptable. Product/domain intent remains owned by DOMAIN_SPEC; if resolving the architectural prerequisite requires a genuine human decision, use the architect's escalation process.

## Authoritative References

- Supplied docs/ROADMAP.md Phase 5: resolution tree and special states from backend values.
- docs/TARGET_ARCHITECTURE.md sections 13.2, 13.3 and 13.5, established by resolved AR-002: fresh inputs, root identity, SINGLE_CRAFT basis and compatibility.
- docs/CURRENT_ARCHITECTURE.md section 5.12, "Single-craft resolution explanation": current trace representation and CH-15 limitation.
- agent/stories/STORY-DOM-020-semantic-resolution-trace.md, Result: requested recipe on explanation, actually selected recipe on node, domain behavior intentionally unchanged.
- docs/KNOWN_PROBLEMS.md CH-15: requested recipe can be counted without executing it; references DOMAIN_SPEC section 28.

## Blocked Work

Final planning of the resolution HTTP root-identity mapping and dependent browser tree integration is blocked pending a truthful, consistent contract and disposition of any domain prerequisite. STORY-APP-012's captured-input orchestration can proceed independently while preserving both existing identities; STORY-WEB-005's existing-payload layout correction is unaffected. No Phase 5 completion can be claimed from either prerequisite alone.

## Blocking User Decision

None.

## Architect Decision

Classification: **Class C - architecturally significant technical decision**.

Decide **separate requested identity and actual root sourcing**, with a truthful
single-output-requirement basis. TARGET_ARCHITECTURE section 13.3 is the contract
owner: response recipeId identifies the request, while each node, including the
root, retains the actual selected producing/attempted recipe or null. Replace
the prospective SINGLE_CRAFT literal with SINGLE_OUTPUT_REQUIREMENT. A mapper
must never manufacture requested-recipe execution or adjust economic facts.

Observed evidence: CURRENT_ARCHITECTURE section 5.12 and STORY-DOM-020's Result
already separate these identities; CH-15 records the resolver behavior.
DOMAIN_SPEC sections 28 and 44 establish intended execution counts and the need
to explain calculation choices. This decision preserves the latter without
redefining the former or treating current behavior as normative.

Architectural judgment: CH-15 correction is not a prerequisite to shipping
truthful detail. Requiring that correction first is the runner-up, rejected as
an unnecessary technical coupling between representation and shared simulation
economics. The defect remains open and must be addressed in the domain, not in
a detail-only resolver, mapper or frontend. Keeping SINGLE_CRAFT with caveats
was rejected because its execution claim would remain misleading.

No Product Owner decision is required: no new product rule is selected, and no
existing domain requirement or phase gate is waived. ADR-003 records the rationale.

## Resolution

Updated TARGET_ARCHITECTURE sections 13.3-13.5 (and the section 13 decision
reference) to remove the requested-root assertion, define the precise tree basis,
require truthful identity mapping and browser presentation, and cover divergent
root sourcing in verification. Added ADR-003-resolution-root-identity.md for the
significant alternatives and trade-offs. Existing current-state and defect
records remain authoritative and unchanged.

Planner follow-up: incorporate the revised contract into bounded HTTP/browser
integration and verification, preserving captured-input orchestration, existing
table contracts and JavaFX coexistence. Verify inventory-only roots, alternative
recipes, requested-recipe crafting, mixed sourcing and blocked selected paths;
response association must use requested identity. Track CH-15 separately under
DOMAIN_SPEC section 28 and KNOWN_PROBLEMS; this request neither fixes nor closes
it. Reconcile any planning references that still promise SINGLE_CRAFT execution.
STORY-APP-012 can retain both identities and STORY-WEB-005 remains unaffected.
No implementation, test execution, timing evidence or Phase 5 completion is
claimed; the planner retains all existing review, correctness and performance
gates. No additional architecture problem was investigated in this run.
