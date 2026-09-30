# GW2 Tool — Target Architecture

## 1. Purpose

This document defines the intended structural boundaries of the GW2 Tool. Domain behavior belongs in `DOMAIN_SPEC.md`; detailed testing procedures belong in `TEST_STRATEGY.md`; migration sequencing belongs in `MIGRATION_PLAN.md` and the roadmap.

The target is a small, maintainable web application that is easy to test, safe to change, containerized, multi-user capable, and suitable for bounded AI-assisted development. Prefer simple boundaries over enterprise-style complexity.

---

# 2. Architectural Goals

1. Separate shared domain/application logic from UI, persistence, and external APIs.
2. Use the browser frontend as the canonical user interface; the legacy JavaFX UI is not part of the target architecture.
3. Keep PostgreSQL as persistent storage and isolate it behind repository/adaptor boundaries.
4. Keep Guild Wars 2 API communication in the backend.
5. Keep authoritative shared calculations in the backend unless an explicit feature-local exception is documented.
6. Support multiple Guild Wars 2 accounts in one hosted application instance and one shared PostgreSQL database while strictly isolating account-specific data.
7. Share global Guild Wars 2/economy data across users.
8. Avoid durable server-side storage of users' Guild Wars 2 API keys when the browser-held-key model satisfies identity and synchronization requirements.
9. Preserve portability and avoid unnecessary infrastructure or provider-specific dependencies.

---

# 3. High-Level Target Structure

```text
User Browser
    |
    v
Frontend Container
    |
    | HTTP / JSON
    v
Backend Container
    |             \
    | SQL          \ HTTPS
    v               v
PostgreSQL       Guild Wars 2 API
```

The deployed application consists primarily of frontend, backend, and PostgreSQL containers. The Guild Wars 2 API remains an external dependency.

---

# 4. Frontend

## 4.1 Responsibilities

The frontend:

- serves and renders the web UI,
- collects user input,
- calls backend APIs,
- presents backend-provided results and domain states,
- owns presentation state such as filtering, sorting, selection, loading, and error display,
- may own feature-local calculations only where this architecture explicitly defines an exception.

The frontend must not duplicate authoritative backend/domain calculations. Crafting Profit, Crafting Discovery, recursive recipe resolution, material valuation, opportunity cost, recipe eligibility, daily-item rules, and resolution-tree construction remain backend-owned. Crafting results supply total owned-material opportunity value and, for each required purchase, the selected acquisition unit price and purchase total; the frontend displays these values without scaling quantities or choosing between raw Trading Post quotes.

Reusable UX/UI requirements are owned by `FRONTEND_UX_GUIDELINES.md`. Detailed user-visible Crafting Profit and Discovery semantics are owned by `DOMAIN_SPEC.md`.

## 4.2 Ectoplasm Salvage exception

Ectoplasm Salvage is the explicit frontend-owned calculation exception. The browser loads the required item metadata, Ecto and Crystalline Dust Trading Post quotes, and account Luck through backend read APIs, then performs the Ecto-specific calculation locally according to `DOMAIN_SPEC.md` sections 2.3 and 45–47.

Changing Ecto amount, salvage method or exact tool, or Trading Post price modes recalculates locally. A Trading Post refresh rereads the required prices through the backend price boundary; it does not require a dedicated Ecto calculation request.

The former backend-owned Ecto calculation flow, including the old parameterless `GET /api/ecto/salvage` operation and client/contracts used exclusively by it, is not part of the target architecture and may be removed when no active consumer remains. The browser implementation is the canonical Ecto Salvage UI; JavaFX compatibility is not required.

This exception does not permit the frontend to recreate Crafting Profit, Discovery, recursive crafting, or other backend-owned domain logic.

## 4.3 Technology

**DECIDED:** Vue 3 with TypeScript and strict type checking.

Use Vue single-file components with the Composition API and `<script setup lang="ts">` as the default convention. Static frontend assets fit the frontend container without requiring a server-side JavaScript runtime. Framework dependencies remain frontend-only; backend/domain architecture and HTTP contracts must remain independent of Vue.

Decision rationale and alternatives belong in `architecture/decisions/ADR-001-frontend-framework-and-language.md`.

## 4.4 Presentation assets

Item/recipe icons are presentation assets, not domain behavior.

- The browser obtains icons through the application/backend boundary rather than directly from ArenaNet asset URLs.
- The backend may maintain a persistent filesystem cache; icon binaries do not belong in PostgreSQL or source control.
- Browser/HTTP caching should avoid unnecessary transfers.
- Missing or failed retrieval degrades to a stable fallback without invalidating calculation results.
- Deployed environments that rely on the cache must provide suitable persistent storage.
- Detailed cache mechanics and failure/status behavior belong in the relevant feature specification/tests.

---

# 5. Backend

The backend is the core application runtime and authoritative owner of shared domain/application behavior.

Responsibilities include:

- exposing the HTTP API,
- executing application use cases,
- enforcing shared domain rules,
- running backend-owned calculations,
- synchronizing with the Guild Wars 2 API,
- loading and storing data,
- coordinating repositories/adapters,
- returning explainable results to the frontend.

A Java backend is retained because the existing application/domain implementation is Java-based. Framework-specific behavior must not leak into the domain layer. The exact web framework is decided separately when required by implementation work.

---

# 6. Backend Internal Boundaries

```text
HTTP / API Layer
      |
      v
Application Layer
      |
      v
Domain Layer
      |
      v
Ports / Interfaces
   /          \
  v            v
Persistence   GW2 API
Adapter        Adapter
  |             |
  v             v
PostgreSQL    Guild Wars 2 API
```

Dependencies point inward toward application/domain abstractions.

## 6.1 Domain Layer

The domain layer contains shared business rules such as crafting resolution, graph traversal, material consumption, craft-vs-buy decisions, opportunity cost, non-tradable valuation, recipe selection, daily-item behavior, discovery eligibility, and backend-owned profit calculation.

Authoritative behavior is defined in `DOMAIN_SPEC.md`.

The domain layer must not depend on UI frameworks, browsers, HTTP/REST/JSON transport types, PostgreSQL/JDBC/SQL, Docker, filesystem paths, or raw Guild Wars 2 API response models.

## 6.2 Application Layer

The application layer coordinates use cases and infrastructure without becoming a second home for complex domain calculations.

Examples include:

```text
CalculateCraftingProfit
GetCraftingDiscoveryCandidates
RefreshAccountData
RefreshTradingPostPrices
RebuildCraftingGraph
GetBankContents
GetMaterialStorage
```

An application service may load data through ports, construct domain input, execute domain behavior, persist required state, and return a result.

## 6.3 HTTP / API Layer

The API layer owns routing, request validation, transport DTO mapping, authentication/account context when applicable, and HTTP response/error mapping. Controllers must remain thin and must not implement business rules.

Endpoint names and DTO details belong to API/feature specifications rather than this architecture document.

## 6.4 Persistence and external API adapters

Application/domain code depends on repository and external-service interfaces, not concrete PostgreSQL or HTTP clients. Adapters implement those interfaces and translate infrastructure-specific models at the boundary.

Guild Wars 2 JSON structures and SQL/JDBC concerns must not leak into domain logic.

---

# 7. PostgreSQL and Data Scope

One shared PostgreSQL database serves the hosted application. A separate database per user/account is not part of the target architecture.

Persistent data distinguishes:

- global/shared data, including Guild Wars 2 item/recipe metadata and Trading Post data,
- account-scoped data, including characters, inventories, material storage, recipe knowledge, and other synchronized account information.

Account-scoped records are keyed by the stable Guild Wars 2 account identity, not by a particular API key. Replacing an API key for the same account must reuse the existing account dataset.

Account isolation is enforced by backend/application/persistence boundaries. Domain calculations should remain account-agnostic where practical and receive already-scoped input data.

Schema creation and upgrades must eventually use a repeatable, versioned migration mechanism. The specific migration technology is selected with the backend framework.

## 7.1 Account data retention

Persist enough activity information to identify long-inactive account data. Do not introduce automatic deletion without evidence that stale data causes meaningful storage, performance, or operational cost.

If cleanup becomes justified, explicitly decide the inactivity threshold, affected records, cleanup frequency, retained identity data, and concurrency protection. Cleanup must never remove global/shared data merely because one account is inactive.

---

# 8. Guild Wars 2 API, Credentials, and Account Identity

All Guild Wars 2 API communication is backend-owned. The frontend does not call ArenaNet directly.

For the target multi-user deployment:

- each user supplies their own Guild Wars 2 API key through the browser,
- the backend receives the key transiently when an account-specific API operation requires it,
- the backend resolves the stable Guild Wars 2 account identity and uses that identity for persisted account scope,
- API keys are not persisted in PostgreSQL or other durable server-side application storage,
- replacing a key for the same account must not create duplicate account data,
- logs/errors must not unnecessarily record API keys.

The exact browser-side key-storage mechanism is a later security/implementation decision.

The initial preferred identity direction is to determine whether possession of a valid Guild Wars 2 API key plus its resolved stable account identity is sufficient. Separate username/password or third-party authentication must not be added without a concrete requirement.

The current local/single-user application may continue using `GW2_API_KEY` from local environment configuration during migration.

---

# 9. Synchronization and Long-Running Operations

Synchronization is an application-level/backend responsibility. The frontend may trigger permitted operations and display status/freshness; the backend owns execution order, persistence, error handling, and synchronization state.

Account refreshes run for the account in the current single-user installation. Crafting Profit's explicit **Refresh data & results** workflow refreshes account bank, materials, recipe knowledge, Luck, character crafting ratings, and character recipes before recalculating; these are the datasets the current account-aware calculation can consume. Account state is intended to become user/account scoped. Account API state is not yet multi-tenant.

Trading Post prices are a globally shared cache. A calculation supplies its feature's relevant item set to the shared refresh/cache pipeline; stored quotes younger than ten minutes are reused, and only missing/stale required IDs are fetched in existing GW2 API batches. Profit and Discovery may select different required IDs, but do not own separate price caches or fetch mechanisms. Prices are refreshed on demand rather than by a full-catalog timer.

Global GW2 metadata and the crafting graph are shared. The backend checks cheap recipe and TP-tradeable ID lists every six hours by default (initial delay one minute), compares them with stored IDs, and downloads detailed recipe/item data only when those IDs changed. Recipe additions/removals trigger the necessary detail persistence; the graph is rebuilt only after changed recipe data has been persisted. The interval is configurable using `GW2_GLOBAL_REFRESH_INTERVAL_MS` and `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`. Manual System Status execution and the scheduler submit the same application service and share in-process duplicate-task protection.

The System Status page is an administrative diagnostics/maintenance area, not a prerequisite for normal feature use. It groups account refresh time/scope, global check/change/recipe/graph timestamps, and shared TP cache counts/fetch time into health cards with clear OK, Problem, Running, or not-run states. Manual actions are limited to account refresh and global-data check; TP prices refresh on demand through feature workflows. It shows currently tracked task states and last global failure. Refresh timestamps/task history are not durable telemetry. This is not a general monitoring platform.

The current runtime is single-user and single-backend-instance. In-process duplicate-job prevention is sufficient. Future account data will be scoped/refreshed per account/user; GW2 global metadata, TP prices, and the crafting graph remain shared. If multiple backend replicas are introduced, global scheduled work and refresh deduplication will then require shared coordination, for example PostgreSQL-backed leases or advisory locks. That coordination is explicitly future work; do not introduce Redis, distributed locks, or a job queue for the current deployment.

Per resolved `agent/user-decisions/UD-007-http-long-running-sync-approach.md`:

- Guild Wars 2 API synchronization uses asynchronous backend tasks with status reporting.
- Other endpoints should be measured with representative real data; keep consistently short operations synchronous and use backend tasks when runtime materially risks interruption or connection timeout.

Do not introduce a distributed queue/job platform without a concrete need.

---

# 10. Authoritative Calculation and Result Boundaries

## 10.1 Crafting graph and calculations

The crafting graph and Crafting Profit/Discovery calculations remain backend/domain concerns. The frontend must not construct the graph or independently recalculate their economic results.

Internal graph caching/rebuild strategy is an implementation decision as long as domain results remain correct.

## 10.2 Resolution tree

The resolution tree is an authoritative backend/domain result explaining how a crafting result was obtained and costed. The frontend renders it rather than reconstructing it.

The tree and summary result must use compatible calculation context/snapshots, preserve domain states and selected sourcing decisions, and associate asynchronous detail responses with the request/result that initiated them so stale responses cannot overwrite newer selections.

For Crafting Profit, the tree explains the full output quantity represented by
the fresh detail response's selected row. `craftableCount` is recipe executions;
`outputCount` is output items per execution, so the requested root quantity is
the row's counted executions multiplied by the recipe output count. The backend
must resolve those executions through the ordinary resolver so inventory,
storage, buying limits, daily operations, recipe eligibility and recursive
requirements are applied normally. It must stop at the counted execution
quantity rather than making an additional speculative craft. A zero-count row
may return the first blocked attempt, explicitly identified as such. Discovery
may retain its one-output-batch basis. The response's row, tree and `treeBasis`
must make these distinctions explicit. No tree quantity or cost may be derived
or scaled in the frontend.

Missing/unavailable detail data must remain explicit; the frontend must not fabricate a result. Exact DTO fields and transport mechanics belong in feature/API specifications.

## 10.3 Domain states

Domain states such as `INVENTORY`, `CRAFT`, `BUY`, `BLOCKED`, `PRICE_UNAVAILABLE`, `DAILY_LIMIT`, and `UNVALUED_NONTRADEABLE` originate from backend/domain behavior. The frontend owns their visual representation, not their meaning.

---

# 11. Configuration and Secrets

Runtime configuration must not be hard-coded into source files. Server-owned configuration and secrets are supplied through environment variables or equivalent deployment configuration and must never be exposed to the frontend.

Per-user Guild Wars 2 API keys follow section 8's browser-held/transient-backend model and are not server-owned persistent configuration.

---

# 12. Deployment and Containers

The initial target is a small single-host Docker deployment using Docker Compose.

```text
Browser
   |
   v
Frontend (public)
   |
   v
Backend (application API)
   |
   v
PostgreSQL (internal)
```

The backend also communicates with the external Guild Wars 2 API over HTTPS. PostgreSQL does not need public exposure.

The application must remain portable to a normal Docker-capable host. A home server, VPS, NAS/container host, or suitable cloud VM may be used. Oracle Cloud Free Tier / Always Free is only a deployment candidate and must be re-evaluated when deployment work is reached; application architecture must not depend on Oracle-specific services.

Do not introduce Kubernetes, microservices, or additional supporting services without a concrete requirement.

---

# 13. Testing Architecture

Detailed testing strategy belongs in `TEST_STRATEGY.md`. Architecture-level expectations are:

- domain tests verify shared business rules without database, HTTP, UI, or Guild Wars 2 API dependencies,
- application tests verify use-case orchestration across domain and ports,
- persistence integration tests use real PostgreSQL where schema/query semantics matter,
- external API adapter tests use controlled/captured payloads; optional live smoke tests stay outside the deterministic default suite,
- API tests verify transport mapping, validation, and mapped failures without duplicating domain-rule tests,
- frontend/component/browser tests verify presentation, interaction, frontend-owned state, and the explicitly frontend-owned Ecto calculation,
- end-to-end tests cover selected critical deployed flows.

The browser frontend is the canonical UI. JavaFX-specific tests may be removed with obsolete JavaFX production code they exclusively verify. Shared backend/domain behavior must retain appropriate non-JavaFX coverage.

---

# 14. Migration and Code Reuse

Migration should preserve working behavior while establishing the target boundaries incrementally. The detailed sequence belongs in `MIGRATION_PLAN.md` and the roadmap.

Existing code should be:

- retained when it correctly implements required behavior and fits the target boundaries,
- refactored when coupling prevents safe reuse,
- replaced or removed when obsolete, incorrect, or more expensive to isolate than replace.

The browser frontend is now the canonical user interface. Obsolete JavaFX UI code, tests, resources, dependencies, and support code with no remaining consumer may be removed. Shared domain/application/backend functionality must not be removed merely because JavaFX also used it.

The obsolete backend-owned Ecto calculation path may likewise be removed while preserving the frontend-owned Ecto behavior defined in section 4.2.

---

# 15. Architecture Simplicity and Quality

When several designs satisfy the requirements, prefer the simplest design that preserves correctness, is testable, has clear ownership, is understandable by a small project team, and supports bounded AI-assisted development.

Near milestone completion, project-health reviews should check for:

- dependency/boundary violations,
- duplicate or obsolete implementations,
- repository/documentation drift,
- test coverage appropriate to changed boundaries,
- relevant performance regressions,
- security/configuration conflicts,
- temporary migration structures that can now be removed.

Review workflow and story disposition belong in the agent/planning documentation.

---

# 16. Technology and Architecture Decisions

## Decided

- **Frontend:** Vue 3 + TypeScript, strict type checking; see ADR-001.
- **Backend:** Java-based; exact web framework selected separately.
- **Database:** PostgreSQL.
- **Runtime packaging:** frontend, backend, and PostgreSQL containers; Docker Compose for the initial small deployment.
- **Shared business-logic ownership:** backend/domain/application layers, with the explicit frontend-owned Ecto Salvage exception in section 4.2.
- **Canonical UI:** browser frontend; JavaFX is obsolete and removable.
- **GW2 API ownership:** backend adapter boundary; browsers do not call ArenaNet directly.
- **Multi-user persistence:** one shared database with shared global data and account-scoped data keyed by stable Guild Wars 2 account identity.
- **Per-user GW2 API keys:** browser-held and supplied transiently when required; not durable server-side application data.
- **Global metadata checks:** backend-owned scheduled ID-list checks; implementation default six hours, configurable by environment.
- **Trading Post prices:** globally shared on-demand freshness cache, ten-minute TTL; no full-catalog timer.
- **Hosting portability:** no unnecessary provider-specific dependency.

## To Be Decided

- Backend web framework.
- Database migration tool.
- Reverse proxy, if required by deployment.
- Browser-side GW2 API-key storage mechanism.
- Whether API-key possession plus stable GW2 account identity is sufficient application identity or separate authentication is required.
- Inactive-account retention threshold, only if measured impact justifies automatic cleanup.
- Hosting provider.

---

# 17. Performance Requirement

Crafting Profit is performance-sensitive. The complete usable browser page must remain within the accepted project budget on representative real data; the current target is at most 7 seconds from navigation/request initiation to complete usable page content.

The budget spans backend calculation, persistence, transport, and frontend rendering. Detailed measurement procedure and historical evidence belong in `TEST_STRATEGY.md` and performance stories.

---

# 18. Documentation Boundaries

- `DOMAIN_SPEC.md` owns authoritative business/domain behavior.
- `FRONTEND_UX_GUIDELINES.md` owns reusable browser UX/UI requirements.
- `TEST_STRATEGY.md` owns detailed verification procedures.
- `MIGRATION_PLAN.md` and `ROADMAP.md` own migration sequencing and milestones.
- `docs/crafting/README.md` is the permitted human-readable explanation of crafting rules and must remain aligned with `DOMAIN_SPEC.md` when user-visible crafting behavior changes.
- Agent/runtime orchestration is development infrastructure and belongs in agent/runtime documentation, not deployed application architecture.

---

# 19. Rules for AI-Assisted Implementation

When modifying or creating code:

1. Follow the ownership/boundary rules in this document rather than recreating them in individual stories.
2. Follow `DOMAIN_SPEC.md` for business behavior.
3. Keep controllers/API endpoints thin and infrastructure concerns outside the domain.
4. Do not duplicate backend-owned calculations in the frontend; respect the explicit Ecto exception in section 4.2.
5. Keep server-owned secrets out of the frontend and follow section 8 for per-user Guild Wars 2 API keys.
6. Do not silently finalize decisions listed as TBD.
7. Document major architecture changes explicitly and remove obsolete paths when a migration decision makes them unnecessary.

---

# 20. Status

This document defines the current target architecture and is stable enough to guide migration planning, testing, repository restructuring, and AI-assisted implementation.

Implementation details not fixed here remain open until they are required. Domain behavior must not be inferred from this document when `DOMAIN_SPEC.md` is the authoritative owner.
