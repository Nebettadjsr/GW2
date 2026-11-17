# ADR-006 — Backend ownership of Ectoplasm Salvage calculations

## Status

SUPERSEDED

This decision was superseded by the Product Owner confirmation recorded in
`STORY-WEB-022`'s Follow-up Findings: the Ectoplasm Salvage calculation remains
frontend-owned, and the former backend calculation route must not be restored.
The current exception is documented in `TARGET_ARCHITECTURE.md` section 4.2 and
`DOMAIN_SPEC.md` section 2.3. The proposal below is retained as history and is
not current architecture.

## Context

At the time this proposal was written, the Phase 5 objective and target
architecture were interpreted as requiring backend ownership of every
authoritative domain result. The Product Owner later confirmed the browser-local
calculation in `DOMAIN_SPEC.md` section 2.3 as an intentional exception. `STORY-WEB-013` delivered a
parameterless backend calculation route and browser consumer; the current
architecture records that route as removed and a browser-local calculator in
use. That old route cannot calculate the newer amount, tool, price-mode and
Magic Find target behavior.

## Constraints

- Preserve the product's automatic response to input changes, four Trading
  Post mode combinations, selective refresh of Ecto and Dust prices, expected
  yields, fee-inclusive costs and Magic Find target presentation.
- Keep authoritative economics in the backend and the domain independent of
  HTTP, JavaFX, persistence and GW2 transport models.
- Preserve the existing HTTP boundary and the JavaFX client during migration.

## Decision

The backend owns all Ectoplasm Salvage economics. Restore a calculation API
with a revised, input-bearing contract; do not restore the old parameterless
`GET /api/ecto/salvage` response as the browser contract. The browser requests
an updated calculation automatically when amount, method, tool or Trading Post
mode changes, and renders only the response for the current input set.

The API accepts the selected controls and an Ecto/Dust quote snapshot acquired
through backend price reads. It validates those inputs, calculates using
backend-owned yield, tool and fee rules, and returns the selected economics and
Magic Find target results together with the inputs/quote basis they used. The
backend obtains account Luck through its account boundary. A manual TP refresh
replaces only the two quote inputs and triggers a new calculation; metadata and
Luck do not need another browser read for that action. Loading, failure and
superseded-response handling must prevent older results from appearing current.
This contract makes the supplied quotes explicit calculation assumptions; it
does not claim they are a fresh market lookup on every input change.

Adapt or remove the unused `ectoApi.ts`, `useEctoSalvage.ts` and
`EctoSalvage*` browser transport types according to the revised contract. Do
not retain an unused client or types that describe the removed route.

## Rationale

Backend calculation follows the established target boundary and reuses the
existing Ectoplasm domain calculation instead of maintaining a second fee and
yield implementation in TypeScript. Reusing an explicit quote snapshot avoids
a live price fetch for every control change while preserving the selective TP
refresh behavior. In engineering judgment, this is a small, reversible HTTP
contract change for the existing backend and frontend, with no new service or
infrastructure. The browser can still update automatically, while presenting
the normal pending state of an HTTP calculation honestly.

## Alternatives Considered

- Keep browser-local calculation as an Ectoplasm exception. It provides
  immediate synchronous updates and preserves the current screen implementation,
  but contradicts the Phase 5 backend-authority exit criterion and duplicates
  domain assumptions and economics across Java and TypeScript.
- Restore the parameterless `STORY-WEB-013` route unchanged. It reuses completed
  work, but its four fixed scenarios do not accept the current controls or
  provide the Magic Find target calculations. The browser would still need
  independent economics for those results.
- Have every input change trigger a backend live TP lookup. It keeps quotes
  backend-fetched, but changes the specified price-only refresh behavior and
  couples ordinary control changes to upstream market latency.

## Consequences

The browser needs asynchronous calculation states and current-input response
matching. The backend API and application/domain calculation must accommodate
the screen's current inputs and outputs while preserving the existing desktop
flow. The planner must arrange correction of `DOMAIN_SPEC.md` §2.3: its
browser-local calculation and “without another calculation request” wording
describe a conflicting implementation mechanism; the user-visible automatic
update and selective TP refresh remain. The planner must reconcile the Phase 5
work and exit evidence, including `STORY-WEB-021`'s content checks. Current
architecture documentation continues to describe the implemented local screen
until that implementation changes.

## References

- `docs/DOMAIN_SPEC.md` §§2.3, 45–47
- `docs/TARGET_ARCHITECTURE.md` section 4.2 (current Ectoplasm exception)
- `docs/ROADMAP.md` §9
- `docs/CURRENT_ARCHITECTURE.md` §§5.3, 5.11, 5.15
- `agent/stories/STORY-WEB-013-ectoplasm-salvage-page.md`
- `agent/stories/STORY-WEB-022-align-ecto-page-label.md`
