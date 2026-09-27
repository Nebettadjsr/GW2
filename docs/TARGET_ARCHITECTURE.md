# GW2 Tool — Target Architecture

## 1. Purpose

This document defines the intended target architecture for the GW2 Tool.

It describes the structural boundaries the system should move toward while leaving implementation details open where no decision has yet been made.

The goal is to make the application:

- easier to understand,
- easier to test,
- safer to change,
- deployable as a web application,
- containerized,
- suitable for controlled work by Claude Code or other coding agents.

This document does not define domain behavior. Domain behavior is defined in `DOMAIN_SPEC.md`.

---

# 2. Architectural Goals

The target architecture should achieve the following:

1. Separate business/domain logic from UI, database, and external APIs.
2. Replace the current desktop-only UI with a web-based interface.
3. Run the application as a small set of independent containers.
4. Keep PostgreSQL as the persistent database.
5. Make the backend the single owner of business logic.
6. Make external integrations replaceable through adapters.
7. Allow domain logic to be tested without database, network, or UI dependencies.
8. Avoid unnecessary architectural complexity.
9. Support multiple Guild Wars 2 accounts in one hosted application instance and one shared PostgreSQL database.
10. Share global Guild Wars 2/economy data across users while strictly isolating account-specific data.
11. Avoid persisting users' Guild Wars 2 API keys on the server when the browser-held-key model can satisfy the required account identity and synchronization flows.

The target is a small maintainable application, not a distributed microservice platform.

---

# 3. High-Level Target Structure

```text
User Browser
     |
     | HTTP
     v
+----------------------+
| Frontend Container   |
|                      |
| Web UI               |
| Technology: see 4.1  |
+----------+-----------+
           |
           | HTTP / JSON
           v
+----------------------+
| Backend Container    |
|                      |
| API                  |
| Application Services |
| Domain Logic         |
| Adapters             |
+----+-------------+---+
     |             |
     |             | HTTPS
     |             v
     |       Guild Wars 2 API
     |
     | SQL
     v
+----------------------+
| PostgreSQL Container |
+----------------------+
```

---

# 4. Container Model

The target deployment consists of three primary application containers.

## 4.1 Frontend Container

Responsibilities:

- serve the web user interface,
- display data received from the backend,
- collect user input,
- call backend API endpoints,
- present calculation results and recipe trees.

The frontend must not contain authoritative business logic.

Examples of logic that must remain in the backend:

- crafting profitability calculations,
- recursive recipe resolution,
- opportunity-cost calculation,
- recipe eligibility,
- material valuation,
- daily-item rules.

### Technology

**Status:** DECIDED — Vue 3 with TypeScript, using strict type checking.

Use Vue single-file components with the Composition API and `<script setup lang="ts">`
as the default component convention. Build a browser-rendered client of the existing
backend HTTP API; static frontend assets fit the existing frontend container without
requiring a server-side JavaScript application runtime. Select compatible stable
dependency versions when implementing, lock them reproducibly, and include component
and TypeScript type checking in verification. Build tooling and optional UI libraries
are separate decisions, not selected here.

This is the simplest overall fit by architectural judgment for the small, single-maintainer
UI: consistent component conventions for tables, recursive tree presentation and
interaction state, with typed API contracts for bounded agent changes. React with
TypeScript remains viable but offers no required capability that outweighs the additional
application-convention choices here. Decision history, evidence and alternatives are in
[ADR-001](architecture/decisions/ADR-001-frontend-framework-and-language.md).

Framework dependencies stay inside the frontend. Backend/domain architecture and HTTP
contracts must remain independent of Vue; TypeScript types describe transport and
presentation data, not a second implementation of domain rules. Static types do not
validate received JSON or replace backend validation. Existing frontend responsibilities,
secret boundaries and the full-page performance requirement remain binding.

---

## 4.2 Backend Container

The backend is the core application runtime.

Responsibilities include:

- exposing the application API,
- executing use cases,
- enforcing domain rules,
- running crafting calculations,
- synchronizing data with the GW2 API,
- loading and storing data,
- coordinating repository access,
- providing explainable calculation results to the frontend.

The backend must be the single authoritative location for business behavior.

### Technology

A Java-based backend is currently preferred because the existing application and domain logic are already written in Java.

The exact framework is not yet decided.

Possible candidates may include:

- Spring Boot,
- Quarkus,
- another suitable Java web framework.

**Status:** TBD

The architecture must not rely on framework-specific behavior inside the domain layer.

---

## 4.3 PostgreSQL Container

PostgreSQL remains the persistent application database.

Responsibilities:

- global Guild Wars 2 item and recipe data,
- Trading Post data,
- synchronized account data,
- character data,
- application persistence.

### Global and account-scoped data

The multi-user deployment uses one shared PostgreSQL database. A separate database per user/account is not part of the target architecture.

Persistent data must distinguish between:

- global/shared data, such as Guild Wars 2 item/recipe metadata and Trading Post prices,
- account-scoped data, such as characters, inventories, material storage, recipe unlock/discovery state and other synchronized account information.

Account-scoped records must be associated with the stable Guild Wars 2 account identity, not with a particular API key. Replacing or recreating an API key for the same Guild Wars 2 account must therefore resolve to and reuse the existing account data rather than creating a new logical user dataset.

Account isolation must be enforced by backend/application/persistence boundaries. Domain calculations should remain account-agnostic where practical and receive already-scoped input data rather than owning tenancy concerns.

The database is infrastructure.

Domain logic must not directly depend on PostgreSQL-specific APIs or SQL.

Database access must occur through repository interfaces / persistence adapters.

---

# 5. External Guild Wars 2 API

The official Guild Wars 2 API remains an external dependency.

It is not part of the container stack.

```text
Backend
   |
   | HTTPS
   v
Guild Wars 2 API
```

The backend owns all GW2 API communication.

The frontend must never call the GW2 API directly.

### Per-user API credentials

In the multi-user web deployment, a user's Guild Wars 2 API key is supplied by that user's browser to the backend when an account-specific Guild Wars 2 API operation requires it.

The backend may use the key transiently to call the Guild Wars 2 API, including resolving the stable Guild Wars 2 account identity, but the target architecture does not persist per-user Guild Wars 2 API keys in PostgreSQL or other server-side durable storage.

The exact browser persistence mechanism (for example browser storage or an appropriate cookie design) is a Phase 6 implementation/security decision and must be explicitly selected rather than assumed here.

A replacement API key for the same Guild Wars 2 account must resolve to the same stable account identity and therefore the same persisted account-scoped dataset.

Reasons include:

- API-key handling,
- consistent synchronization behavior,
- caching,
- rate-limit handling,
- easier testing,
- separation of external API models from the UI.

---

# 6. Backend Internal Architecture

The backend should be organized into clear layers.

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
       |
       +------------------+
       |                  |
       v                  v
Persistence Adapter   GW2 API Adapter
       |                  |
       v                  v
PostgreSQL           Guild Wars 2 API
```

Dependencies should point inward toward the domain.

---

# 7. Domain Layer

The Domain Layer contains the core business rules.

Examples include:

- crafting resolution,
- crafting graph traversal,
- material consumption,
- craft-vs-buy decisions,
- opportunity-cost calculation,
- non-tradable item valuation,
- recipe selection,
- daily-item behavior,
- discovery eligibility,
- profit calculation.

The authoritative rules are defined in:

```text
DOMAIN_SPEC.md
```

## Domain Independence Rule

The Domain Layer must know nothing about:

- JavaFX,
- React,
- Vue,
- browsers,
- HTTP,
- REST,
- JSON transport objects,
- PostgreSQL,
- JDBC,
- SQL,
- Docker,
- GW2 API response JSON,
- filesystem paths.

Domain logic should operate on domain objects and interfaces.

This is one of the most important rules of the target architecture.

---

# 8. Application Layer

The Application Layer coordinates use cases.

Examples:

```text
CalculateCraftingProfit
GetCraftingDiscoveryCandidates
RefreshAccountData
RefreshTradingPostPrices
RebuildCraftingGraph
GetBankContents
GetMaterialStorage
CalculateEctoLuckCost
```

An application service may:

1. request data from repositories,
2. construct a domain request,
3. execute domain logic,
4. persist required state,
5. return a result.

The Application Layer may coordinate infrastructure, but it should not contain complex domain calculations itself.

---

# 9. API / HTTP Layer

The API layer translates web requests into application use cases.

Responsibilities include:

- HTTP routing,
- request validation,
- authentication if introduced later,
- converting transport DTOs,
- returning HTTP responses.

Example endpoints may eventually resemble:

```text
GET  /api/account/materials
GET  /api/account/bank

POST /api/sync/account
POST /api/sync/global
POST /api/prices/refresh

POST /api/crafting/profit
POST /api/crafting/discovery

GET  /api/items/{id}
GET  /api/recipes/{id}
```

These endpoint names are examples, not yet a fixed API contract.

Business rules must not be implemented in controllers.

---

# 10. Persistence Boundary

The domain/application layers should depend on repository interfaces rather than concrete PostgreSQL classes.

Conceptually:

```text
Application / Domain
        |
        v
RecipeRepository interface
        |
        v
PostgresRecipeRepository
        |
        v
PostgreSQL
```

This enables:

- unit testing without PostgreSQL,
- easier database migrations,
- clearer ownership,
- reduced coupling.

---

# 11. External API Boundary

GW2 API access should follow the same pattern.

Conceptually:

```text
Application
    |
    v
Gw2ApiPort
    |
    v
Gw2HttpApiClient
    |
    v
ArenaNet API
```

External GW2 API response models should be converted into internal application/domain models at this boundary.

GW2 JSON structures must not leak into domain logic.

---

# 12. Frontend Responsibilities

The frontend is responsible for presentation and interaction.

Reusable web UX/UI requirements are owned by
[`FRONTEND_UX_GUIDELINES.md`](FRONTEND_UX_GUIDELINES.md). Frontend implementation
stories must reference its relevant sections. Phase 5's initial frontend must
be brought into compliance before extending its presentation patterns to
additional pages; presentation correction preserves the backend contracts below.

It may:

- display tables,
- provide filters,
- display money values,
- display recipe trees,
- trigger sync operations,
- show loading/error states,
- submit crafting settings,
- provide search and sorting,
- visually flag special states.

The frontend must not independently reproduce business calculations.

For example, it may display:

```text
profitCopper = 12345
```

but must not independently recalculate crafting profit from raw materials and prices.

---

## 12.1 Web presentation assets and product requirements

Detailed Crafting Profit result content and interactions are owned by `DOMAIN_SPEC.md` §2.1.1. The frontend presents those results; it does not become the authority for their calculation.

### Item and recipe icon delivery

Item/recipe icons are presentation assets and must not alter domain behavior.

Target rules:

- The browser obtains icons through the application/backend boundary rather than depending directly on ArenaNet asset URLs.
- The backend owns icon resolution and may maintain a persistent filesystem cache so repeatedly requested icons do not require repeated external downloads.
- Icon binaries do not belong in PostgreSQL.
- Browser/HTTP caching should be used so unchanged icons are not transferred unnecessarily.
- Missing or failed icon retrieval must degrade to a stable placeholder/fallback and must not break Crafting Profit/Discovery results.
- Containerized/deployed environments that rely on the backend icon cache must provide suitable persistent storage for that cache.
- Cache implementation details, HTTP cache headers, hashing/keying, concurrency behavior, migration mechanics and exact error/status handling belong in the relevant ADR/feature specification and tests, not in this architecture document.

---

# 13. Resolution Tree

The resolution tree is an authoritative backend/domain result used to explain how a crafting result is obtained and costed. The frontend renders the tree; it must not independently reconstruct or recalculate it.

Architectural rules:

- Resolution-tree calculation uses explicit request/use-case inputs and request-local calculation state.
- The API exposes transport DTOs rather than domain or persistence objects directly.
- The tree and the summary result shown to the user must be based on the same authoritative calculation rules and compatible data snapshot/context.
- Tree generation must preserve special domain states and the selected craft-vs-buy decisions rather than inventing presentation-only alternatives.
- Browser requests must associate returned detail data with the result/request that initiated them so stale asynchronous responses cannot overwrite a newer selection.
- Missing/unavailable detail data must be represented explicitly and must not cause the frontend to fabricate a result.
- Exact DTO fields, nullability, HTTP status mapping, request identifiers, migration sequencing and detailed verification cases belong in the API/feature specification and associated ADRs/tests.

---

# 14. Special Domain States

The backend/domain may expose meaningful states such as:

```text
INVENTORY
CRAFT
BUY
BLOCKED
PRICE_UNAVAILABLE
DAILY_LIMIT
UNVALUED_NONTRADEABLE
```

These are domain meanings.

The frontend decides how those states are visually represented.

Example:

```text
UNVALUED_NONTRADEABLE
```

may eventually be shown in blue, yellow, or another visual style.

That color is not part of the domain contract.

---

# 15. Configuration and Credentials

Runtime configuration must not be hard-coded into source files.

Server-owned configuration should be supplied through runtime configuration such as environment variables or equivalent deployment configuration.

Examples include:

```text
DATABASE_URL
DATABASE_USER
DATABASE_PASSWORD
```

Server-owned secrets and infrastructure credentials must remain backend-only and must never be exposed to the frontend.

## Guild Wars 2 API keys

The current local/single-user application may continue using `GW2_API_KEY` from local environment configuration during migration.

The target public multi-user deployment does not use one deployment-wide Guild Wars 2 API key for all users.

Each user supplies their own Guild Wars 2 API key through the browser. The browser may retain that key for the user's convenience; the backend receives it transiently when required for account-specific Guild Wars 2 API operations and does not persist it in the application database or other durable server-side storage.

The frontend must not expose one user's key to another user. Backend logs, errors and diagnostics must not unnecessarily record API keys.

The exact browser-side storage mechanism is decided during the multi-user implementation phase.

---

# 16. Database Schema Management

The current manually executed SQL setup should eventually be replaced by a repeatable database migration mechanism.

The exact migration technology is TBD.

Possible solutions depend on the selected backend framework.

Required properties:

- schema creation is reproducible,
- migrations are versioned,
- migrations are stored in the repository,
- a fresh database can be created without manually copying SQL into PostgreSQL,
- application upgrades can migrate existing databases safely.

---

# 17. Container Orchestration

For local development and small-scale deployment, Docker Compose is the preferred initial orchestration mechanism.

Conceptually:

```text
docker compose up
```

should start:

```text
frontend
backend
postgres
```

Possible future supporting services should only be added when clearly necessary.

The project should not introduce Kubernetes or a microservice platform without a concrete requirement.

---

# 18. Container Networking

Containers communicate over an internal Docker network.

Conceptually:

```text
Browser
   |
   v
Frontend : public

Frontend
   |
   v
Backend : internal/API

Backend
   |
   v
Postgres : internal only
```

PostgreSQL does not need to be publicly exposed in a production deployment.

---

# 19. Deployment Model

The initial target is a small single-host deployment.

Possible hosts may include:

- a home server,
- VPS,
- NAS/container host,
- another Docker-capable server.

The hosting provider is not yet decided.

Oracle Cloud Free Tier / Always Free is currently identified as a potential initial hosting candidate because a suitable Docker-capable VM may support the intended small single-host deployment without requiring provider-specific application architecture.

This is not a hosting-provider decision. Availability, resource limits, pricing/free-tier conditions and suitability must be re-evaluated when deployment work is actually reached.

The application must remain portable to a normal Docker-capable host and must not depend on Oracle-specific services merely to take advantage of a currently available free hosting option.

The architecture should avoid relying on provider-specific services unless intentionally introduced later.

---

# 20. Authentication and Account Identity

Public multi-user access is now an explicit target requirement.

The application must establish which stable Guild Wars 2 account an account-scoped request belongs to and must prevent access to another account's persisted data.

The initial preferred direction is to investigate whether possession of a valid Guild Wars 2 API key, validated by the backend against the Guild Wars 2 API and resolved to the stable Guild Wars 2 account identity, is sufficient as the application's account identity mechanism.

Whether this is sufficient or whether separate application authentication is required must be explicitly decided during the multi-user phase.

Do not introduce username/password accounts, email registration, password reset infrastructure or provider-specific authentication merely because the application is public. Add separate authentication only if the required account isolation or product requirements cannot be satisfied cleanly without it.

---

# 21. User / Account Scope

The target hosted application is multi-user.

One application instance and one shared PostgreSQL database may serve multiple Guild Wars 2 accounts.

The stable Guild Wars 2 account identity returned by the Guild Wars 2 API is the durable identity for account-scoped persistence. An API key is a credential used to establish/access that identity; it is not itself the persistent account identity.

Conceptually:

```text
API Key A1 ──┐
             ├──> GW2 Account A ──> persisted Account A data
API Key A2 ──┘

API Key B  ─────> GW2 Account B ──> persisted Account B data
```

Creating a replacement API key must therefore not create duplicate persisted account data.

Global application data is shared across accounts. Account-specific data must be explicitly scoped and isolated.

The architecture must not require a separate PostgreSQL database, backend instance or container stack for each user.

---

# 22. Synchronization

Synchronization should become an application-level use case rather than UI-owned behavior.

Examples:

```text
Refresh account
Refresh global item data
Refresh recipes
Refresh Trading Post prices
Rebuild crafting graph
```

The frontend may trigger these operations.

The backend controls:

- execution order,
- persistence,
- error handling,
- synchronization state.

## Global versus account-specific synchronization

Synchronization must distinguish between global/shared work and account-specific work.

Account-specific synchronization, such as characters, inventories, material storage and account recipe knowledge, runs in the scope of one resolved Guild Wars 2 account.

Global data must not be redundantly refreshed because multiple users request the same operation.

Trading Post price refresh is backend-owned global work. In the multi-user deployment it must run automatically on a backend-controlled schedule rather than exposing a per-user frontend refresh action. The initial target cadence is approximately five minutes, subject to verification against actual Guild Wars 2 API behavior, rate limits and application requirements.

Other synchronization operations must be classified similarly during the multi-user migration. Where data is global, concurrent user activity should reuse, coalesce or schedule the shared work instead of causing equivalent external API operations once per user.

The frontend may expose status/freshness information where useful, but ordinary users must not independently trigger redundant global refresh work.

---

# 23. Long-Running Operations

Some synchronization operations may take longer than a normal HTTP request.

The first implementation should remain as simple as practical.

Per resolved `agent/user-decisions/UD-007-http-long-running-sync-approach.md`, use a mixed approach:

- GW2 API synchronization operations use asynchronous backend tasks with status reporting.
- For other endpoints, obtain representative real-world runtime measurements during implementation. Prefer synchronous requests for consistently short operations; use backend tasks with a status endpoint when execution takes materially longer or risks interruption/connection timeout. Do not assume durations or assign the split speculatively.

A full queue system or distributed job platform is not currently required.

**Status:** approach decided in UD-007; per-endpoint choices outside GW2 synchronization follow measured behavior. Routine task implementation details remain implementation work.

---

# 24. Crafting Graph

The Crafting Graph remains an internal backend/domain concern.

The frontend should not construct or maintain the crafting graph.

The target architecture should determine later whether the graph is:

- rebuilt in memory,
- cached in the database,
- cached in a file,
- generated on startup,
- generated after recipe synchronization.

This is an implementation decision as long as domain results remain correct.

## 24.1 Account Data Retention

Account-scoped persisted data should record sufficient activity information to identify accounts that have not used or synchronized with the application for an extended period, for example through `last_seen_at` and/or `last_successful_sync_at`.

A returning user who presents a new API key for the same stable Guild Wars 2 account must reuse the existing account dataset while it still exists.

Inactive account data may eventually be removed to control PostgreSQL/storage growth. Automatic cleanup must not be introduced merely because inactive records exist. First establish that stale account data causes meaningful storage, database-performance or operational cost.

When such evidence exists, explicitly decide:

- the inactivity threshold,
- which account-scoped records are removed,
- whether any minimal account identity/tombstone is retained,
- cleanup frequency,
- and how concurrent/returning-user activity is protected from deletion.

Cleanup must never delete global/shared Guild Wars 2 data merely because an individual account is inactive.

If measured storage impact remains insignificant, retaining inactive account data is acceptable and no automatic cleaner is required.

---

# 25. Testing Architecture

Testing follows `docs/TEST_STRATEGY.md`; this section records only architecture-level expectations.

- Domain tests verify business rules without database, HTTP, JavaFX or GW2 API dependencies.
- Application tests verify use-case orchestration across domain and ports.
- Persistence integration tests use real PostgreSQL behavior where schema/query semantics matter.
- External API adapter tests use controlled/captured GW2 payloads; optional live smoke tests remain outside the deterministic default suite.
- API tests verify transport mapping, validation and mapped failures without duplicating domain-rule tests.
- End-to-end tests cover selected critical user flows across the deployed boundaries.
- During migration, JavaFX may remain a verification surface until web parity is established; detailed JavaFX verification procedures belong in `TEST_STRATEGY.md` and migration stories.

---

# 26. Dependency Direction

The intended dependency direction is:

```text
Frontend
    |
    v
Backend API
    |
    v
Application
    |
    v
Domain
```

Infrastructure implements interfaces required by the application/domain.

The following dependency directions are forbidden:

```text
Domain → PostgreSQL
Domain → HTTP
Domain → Frontend
Domain → JavaFX
Domain → Docker
```

---

# 27. Migration Principle

The current application should not be rewritten all at once.

Migration should preserve working behavior while gradually establishing the target boundaries.

Preferred strategy:

```text
existing application
      |
      v
protect behavior with tests
      |
      v
isolate domain logic
      |
      v
isolate persistence/API adapters
      |
      v
introduce backend API
      |
      v
introduce web frontend
      |
      v
introduce multi-user / account isolation
      |
      v
containerize final runtime
```

The exact migration plan belongs in `MIGRATION_PLAN.md`.

---

# 28. Reuse of Existing Code

Existing Java code should be reused when it correctly implements defined domain behavior and can reasonably be isolated.

A rewrite is not automatically preferred.

Code should be:

- retained when sound,
- refactored when coupling prevents safe use,
- replaced when behavior is incorrect or isolation would be more expensive than replacement.

The target architecture describes boundaries, not a requirement to throw away the current implementation.

---

# 29. Architecture Simplicity Rule

When several designs can satisfy the requirements, prefer the simplest design that:

- preserves domain correctness,
- is testable,
- has clear responsibility boundaries,
- is understandable by a small project team,
- can be maintained with Claude-assisted development.

Avoid adding abstractions solely because they are common in large enterprise applications.

---

# 30. Technology Decisions

This section is a decision index. Detailed rationale belongs in the referenced ADR/user-decision documents rather than being repeated here.

## Decided

- **Primary database:** PostgreSQL.
- **Target runtime packaging:** separate frontend, backend and PostgreSQL containers, orchestrated locally with Docker Compose.
- **Business-logic ownership:** backend/domain/application layers; the frontend is not an independent calculation engine.
- **GW2 API ownership:** backend adapter boundary; browsers do not directly own synchronization logic.
- **Multi-user persistence:** one shared PostgreSQL database with shared global data and account-scoped data keyed by stable GW2 account identity.
- **Per-user GW2 API keys:** browser-held in the target multi-user model and supplied transiently to the backend when required; not persisted as durable server-side application data.
- **Global Trading Post refresh:** backend-owned scheduled work rather than a per-user refresh action; initial target cadence approximately five minutes, subject to operational verification.
- **Hosting portability:** provider-specific services must not become unnecessary architectural dependencies.

## To Be Decided

- Frontend framework/language, if not already resolved by the active implementation milestone.
- Database migration tool.
- Reverse proxy, if required by the selected deployment target.
- Browser-side GW2 API-key storage mechanism.
- Whether validated GW2 API-key possession plus stable account identity is sufficient application identity or separate authentication is required.
- Inactive-account retention threshold, only when measured storage/operational impact justifies automatic cleanup.
- Hosting provider. Oracle Cloud Free Tier / Always Free is a current candidate, not a decision; availability and limits must be re-evaluated at deployment time.

---

# 31. Intended Repository Direction

A possible future repository structure may resemble:

```text
/
├── docs/
│   ├── DOMAIN_SPEC.md
│   ├── CURRENT_STATE_SPEC.md
│   ├── TARGET_ARCHITECTURE.md
│   ├── MIGRATION_PLAN.md
│   └── TEST_STRATEGY.md
│
├── backend/
│   ├── domain/
│   ├── application/
│   ├── api/
│   └── infrastructure/
│
├── frontend/
│
├── docker-compose.yml
│
└── ...
```

This is an architectural illustration, not yet a mandatory physical directory structure.

The actual structure should be chosen when the migration begins.

---

# 32. Relationship to Claude Code

Claude should use this document to determine architectural boundaries.

When modifying or creating code, Claude must preserve these principles:

1. Domain logic belongs in the backend domain layer.
2. UI code must not own business rules.
3. Controllers/API endpoints must remain thin.
4. SQL/JDBC must not leak into domain logic.
5. GW2 HTTP/JSON models must not leak into domain logic.
6. Server-owned secrets must not reach the frontend; per-user GW2 API keys follow the browser-held credential model defined in sections 5 and 15.
7. The frontend must not duplicate authoritative calculations.
8. New dependencies must respect inward dependency direction.
9. Technology choices marked `TBD` must not be silently finalized.
10. Major architecture changes should be explicit and documented.

---

# 33. Crafting Calculation Performance

Crafting Profit is performance-sensitive. The complete user-visible Crafting Profit page must remain within the accepted project performance budget on representative real data; the current accepted target is at most 7 seconds from navigation/request initiation to complete usable page content.

This budget spans backend calculation, persistence, transport and frontend rendering. Optimizing one boundary does not by itself satisfy the complete-page requirement.

Detailed measurement procedure, historical baselines, milestone acceptance evidence and regression-test mechanics belong in `TEST_STRATEGY.md`, performance stories and their recorded results rather than this target architecture.

---

# 34. Repository Quality and Project-Health Reviews

Architecture quality must be reviewed near milestone completion so implementation drift, obsolete paths and documentation mismatches do not accumulate across phases.

At architecture level, reviews should check:

- dependency direction and boundary violations,
- duplicate or obsolete implementations,
- repository/documentation alignment,
- test coverage appropriate to changed boundaries,
- performance regressions where relevant,
- security/configuration mistakes that conflict with this target architecture,
- and whether temporary migration structures can now be removed.

The exact review workflow, agent responsibilities, report format, story creation/disposition and milestone-closing procedure belong in the agent/planning documentation rather than this architecture document.

---

# 35. Human-readable crafting documentation

Maintain a small Markdown guide for readers with little Guild Wars 2 knowledge. Its entry point is `docs/crafting/README.md`; split into a few files only when readability benefits. Explain the end-to-end Profit and Discovery calculation, inputs, assumptions and why rules matter, with compact glossary/FAQ and worked economic examples rather than class/method walkthroughs. Cover the applicable rules in `DOMAIN_SPEC.md`: recipe choice/unlocks, discipline/rating and character eligibility, coordinated crafting and transferable intermediates, binding, account/bank/character inventory, opportunity cost, buying and craft-versus-buy, price modes, feature-specific fees, unavailable prices and blocked results, daily restrictions, discovery, the intentional simulation cap, and total/per-output-item profit. Distinguish game mechanics, project choices and documented implementation gaps; do not invent rules.

This guide is an explicit exception to the normal prohibition on documentation duplication: a self-contained explanatory summary is permitted; authoritative documents remain the source of truth. Every change materially affecting user-visible crafting rules, character/binding handling, pricing, fees, profit, blocked states or limits must update the relevant guide alongside the authoritative owner. Development/implementation documentation must link this maintenance requirement. Add only concise links near the top of root `README.md` to `agent/agent_README_experimental.md` (AI-assisted development/orchestration) and the crafting guide. Do not rewrite `agent/agent_README_experimental.md` or duplicate it in the root README.

# 36. Development-Orchestration Boundary

The repository's AI/agent orchestration is development infrastructure, not part of the deployed GW2 application architecture. Model availability, fallback behavior, planner/executor routing and similar concerns must be documented in the agent/runtime documentation and must not influence the application's runtime dependency structure.

---

# 37. Status

This document defines the initial target architecture.

It intentionally specifies architectural boundaries more strongly than implementation technology.

The target architecture is considered stable enough to guide:

- migration planning,
- test strategy,
- repository restructuring,
- Claude Code instructions.

Technology choices marked `TBD` remain open and should be resolved only when implementation work reaches the point where the decision is necessary.