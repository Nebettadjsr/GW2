# ADR-001 — Frontend framework and language

## Status

ACCEPTED — 2026-09-24. Class C: architecturally significant technical decision.
Requires Product Owner decision: NO.

## Context

AR-001 asks for the framework and language needed for Phase 5. No existing constraint
forces a particular framework. UD-008 is RESOLVED only as rerouted to this request;
it contains no Product Owner technology selection. This decision is architectural
engineering judgment, not a newly inferred product preference.

The project is a single-maintainer, single-user desktop-to-web migration. Its UI
needs tables, settings, recursive resolution-tree presentation, special/blocked
states, bank/materials, Ectoplasm Salvage and synchronization status. The existing
Spring Boot HTTP boundary carries backend-owned results. Choosing a framework does
not make missing endpoint contracts available.

## Constraints

- `TARGET_ARCHITECTURE.md` sections 4.1, 12–15 own presentation/domain and
  secret boundaries; frontend code neither calculates authoritative results nor
  calls the GW2 API or receives credentials.
- Sections 3–4 and 29 require a small deployment and low unnecessary complexity.
- Section 33 requires at most seven seconds from navigation to the complete,
  interactive Crafting Profit page on the real user database. Neither a shell nor
  backend-only timing is sufficient evidence.
- `ROADMAP.md` Phase 5 requires incremental coexistence. `CURRENT_ARCHITECTURE.md`
  sections 5.5–5.9 and UD-006/UD-007 establish the HTTP boundary and operation policy.
  JavaFX currently calls shared application services in-process; this decision does
  not require moving those calls through HTTP.
- `TEST_STRATEGY.md` section 12 owns rendering, interaction, request and state tests.

## Decision

Select Vue 3 and TypeScript. The normative frontend technology and component
conventions are owned by `TARGET_ARCHITECTURE.md` section 4.1. This ADR records why.

## Rationale

Vue supplies declarative templates, reactivity and single-file components, and
supports both small embedded interfaces and complete browser applications.
Its guide recommends Composition API with single-file components for full
applications. These are capabilities documented in the
[Vue introduction](https://vuejs.org/guide/introduction.html).

**Architectural judgment:** this project's forms, result tables and nested tree
views benefit from those conventions without needing a larger application platform.
Choosing one component style gives coding agents a consistent unit for bounded
changes. Typed props/events and small presentation components can separate a tree
node, result row and operation state without mirroring backend services in the UI.
HTTP request/state handling still needs explicit ownership; Vue does not solve
request races or stale responses automatically.

TypeScript can express nullable fields and discriminate variants by literal tags,
including exhaustive handling through `never`, as described in the
[TypeScript narrowing handbook](https://www.typescriptlang.org/docs/handbook/2/narrowing.html).
Here that helps check unavailable results, blocked reasons and task states rather
than confusing a missing value with zero or task acceptance with success. Types
must follow the actual API schema, not invent a richer wire contract.

Vue ships type declarations and documents component-aware command-line checking
with `vue-tsc`. Its documented Vite setup transpiles without type checking, so a
successful bundle alone would not establish type correctness.
[Vue TypeScript guide](https://vuejs.org/guide/typescript/overview.html).
The extra compiler/tooling and API-type maintenance are worthwhile by judgment
for changes made one story at a time. Static checking is not runtime JSON validation
and cannot prove domain correctness.

### Maintenance and operations

Vue documents semantic versioning with exceptions for TypeScript definitions,
no fixed release cycle, and typical minor releases every three to six months.
Its policy does not promise a fixed multi-year Vue 3 support end date. Minor type
changes therefore require deliberate upgrades and verification, not an assumption
of indefinite compatibility. [Vue release policy](https://vuejs.org/about/releases.html).
The reviewed guide provides component, TypeScript and testing guidance in one place;
judgment: that is sufficient documentation for this project's needed surface.

Build-time JavaScript tooling and component compilation are added maintenance costs.
A browser client served as static assets needs no additional application service,
paid platform or server-rendering runtime. Exact build tools, router, state library,
table library, test runner and asset-serving implementation are not selected here.
Introduce optional dependencies only for concrete needs. Server rendering and
full-stack frameworks solve problems not established for this backend-owned UI;
their additional integration surface has no demonstrated benefit here.

## Alternatives Considered

| Option | Fit and trade-off for this project | Disposition |
| --- | --- | --- |
| React + TypeScript | Equally capable of typed tables, nested components and API interaction. React's own client-only setup leaves routing and data-fetching choices to the application; its flexibility is useful for bespoke application stacks. | Runner-up. With no existing React UI investment or special integration requirement in the reviewed evidence, Vue's selected component conventions provide the simpler starting point by judgment. React is viable at this size and does not require server rendering. |
| Svelte + TypeScript | Compiler-based declarative components suit small interactive UIs; official documentation supports TypeScript and standalone use. | Viable, but its compilation model solves no evidenced bottleneck here. No measured performance advantage is asserted. Vue's documented application conventions are the preferred tie-break by judgment. |
| Angular + TypeScript | A coordinated application platform can help teams standardize larger applications. Its explicit support schedule is a maintenance advantage. | The broader platform is unnecessary for this small presentation client; no requirement justifies carrying that additional conceptual surface. |
| Vue + JavaScript | Same UI capabilities with less type-tool configuration. | Loses checked API/component contracts across independent changes. Maintaining annotations/checking would erode that simplicity advantage; strict TypeScript is preferable here. |

React documents the client-only assembly choices in
[Build a React app from scratch](https://react.dev/learn/build-a-react-app-from-scratch).
Its stable releases follow semantic versioning; the policy emphasizes gradual
upgrades and security-fix backports, without a fixed support duration. Thus churn
is not a claim that React is unstable.
[React versioning policy](https://react.dev/community/versioning-policy).

Svelte's compiler and component approach are documented in its
[overview](https://svelte.dev/docs/svelte/overview). No Svelte support lifetime or
release-cadence advantage was established in this review. Angular's reviewed
policy lists annual majors and a typical 24-month support window (12 active,
12 LTS), with older releases subject to the earlier cadence.
[Angular releases](https://angular.dev/reference/releases).
These are current policy observations, not guarantees about future versions.

## Consequences

- Framework-dependent planning can proceed. The planner owns executable work and
  reconciliation of the roadmap's now-stale frontend TBD wording.
- The choice provides no performance acceptance evidence. Browser asset loading,
  HTTP transport and complete rendering must be measured under the existing
  section 33 procedure; optimize measured bottlenecks without omitting results.
- Replacing Vue later would require rewriting components and interaction tests.
  Framework-independent API modules/types may survive; backend logic and the HTTP
  boundary remain reusable. Reversal is affordable early, increasingly costly as
  screens accumulate, and is not a cost-free swap.
- No source code, build, runtime, hosting or product behavior is changed by this
  record. The selected technology is target intent, not current implementation.

## References

- [Target architecture](../../TARGET_ARCHITECTURE.md), sections cited above.
- [Current architecture](../../CURRENT_ARCHITECTURE.md), sections 5.5–5.9.
- [Roadmap](../../ROADMAP.md), Phase 5; [test strategy](../../TEST_STRATEGY.md), section 12.
- [UD-006](../../../agent/user-decisions/UD-006-backend-web-framework.md),
  [UD-007](../../../agent/user-decisions/UD-007-http-long-running-sync-approach.md),
  [UD-008](../../../agent/user-decisions/UD-008-frontend-framework-and-language.md).
- [AR-001](../../../agent/architect-requests/AR-001-frontend-framework-and-language.md).
- Official technology sources are linked next to the supported claims; consulted
  2026-09-24. Comparative fit and simplicity conclusions are architectural judgment,
  not benchmarks, adoption statistics or measured coding-agent performance.
