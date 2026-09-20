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
| Framework: TBD       |
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

**Status:** TBD

Possible candidates include:

- React,
- Vue,
- another modern web framework.

TypeScript is a possible choice but is not yet required.

The architecture must not depend on a specific frontend framework.

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

# 13. Resolution Tree

The backend should return structured resolution information rather than UI-specific tree widgets.

Conceptually:

```json
{
  "itemId": 123,
  "quantity": 5,
  "method": "CRAFT",
  "children": []
}
```

The exact transport schema is TBD.

The important architectural rule is:

```text
Backend decides what happened.
Frontend decides how to display it.
```

UI concerns such as:

- tree colors,
- icons,
- expansion state,
- layout,

must not exist in the domain layer.

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

# 15. Configuration and Secrets

Runtime configuration must not be hard-coded into source files.

Configuration should be supplied through runtime configuration such as environment variables or equivalent deployment configuration.

Examples include:

```text
GW2_API_KEY
DATABASE_URL
DATABASE_USER
DATABASE_PASSWORD
```

Secrets must remain backend-only.

The frontend must not receive the GW2 API key or database credentials.

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

The architecture should avoid relying on provider-specific services unless intentionally introduced later.

---

# 20. Authentication

Authentication is currently not a defined requirement.

**Status:** TBD

If the application remains a private single-user tool, full user-account infrastructure may not be necessary.

Authentication should not be added merely because the application is web-based.

If public or multi-user access becomes a requirement, authentication and account isolation must be designed separately.

---

# 21. User / Account Scope

The current intended deployment should initially assume one configured Guild Wars 2 account per application instance unless a future requirement explicitly introduces multi-user or multi-account support.

This keeps the initial architecture small.

Multi-tenancy must not be introduced speculatively.

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

---

# 23. Long-Running Operations

Some synchronization operations may take longer than a normal HTTP request.

The first implementation should remain as simple as practical.

Potential approaches include:

- synchronous request where acceptable,
- backend task with status endpoint for longer operations.

A full queue system or distributed job platform is not currently required.

**Status:** implementation approach TBD

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

---

# 25. Testing Architecture

The architecture must support multiple test levels.

## Existing JavaFX UI Verification Capability

The implementation workflow must support repeatable automated verification of the existing JavaFX application. This is intended capability, not a claim that tooling is already installed or verified. Its immediate scope is the Phase 1 crafting views and the verification gaps recorded in STORY-DOM-013 through STORY-DOM-015.

The capability must launch the application, detect successful startup, interact with relevant controls (including ComboBox selection and button clicks), inspect TableView contents and displayed state, verify selection-driven result changes and important empty/error states, capture screenshots when useful, and shut down cleanly. Prefer control-based deterministic regression tests over ad-hoc desktop interaction; fixed screen coordinates are not an acceptable foundation.

Evaluate TestFX or an equivalent maintained approach against the actual JavaFX version and existing Maven setup before adopting a substantial framework. Compatibility and reliability must be demonstrated rather than assumed. Keep reusable capability setup separate from the behavior coverage owned by the existing crafting stories: character-dependent results, All characters selection, refresh preservation of sorting/filtering, initial defaults, and zero-character/empty-data handling.

Windows PowerShell may be used within the repository/development workflow for interim automation, including investigation of System.Windows.Automation where useful. This is not a requirement to broaden agent permissions and does not replace practical automated regression tests. Preserve application correctness and retain manual verification as a fallback when automation is genuinely impractical. Once tooling is established, document its permanent test methodology, invocation and limitations in TEST_STRATEGY.md; this section owns the intended capability, while that document owns how it is tested.

## Domain Tests

Run without:

- database,
- HTTP,
- Docker,
- GW2 API.

These tests protect the rules in `DOMAIN_SPEC.md`.

## Application Tests

Test use-case orchestration using fake or in-memory adapters.

## Persistence Integration Tests

Verify PostgreSQL repository behavior.

## External API Adapter Tests

Verify conversion and handling of GW2 API responses.

## API Tests

Verify backend HTTP contracts.

## End-to-End Tests

A small number of tests may cover:

```text
browser/API
→ backend
→ database
```

The majority of business behavior should not require end-to-end tests.

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

## Decided

The following target decisions are currently established:

```text
Application type:
Web application

Deployment:
Containerized

Primary containers:
Frontend
Backend
PostgreSQL

Database:
PostgreSQL

Business logic owner:
Backend

External GW2 API access:
Backend only

Communication:
Frontend → Backend through HTTP API

Domain:
Independent from UI, database, HTTP, and external API formats

Local/small deployment:
Docker Compose preferred
```

---

## To Be Decided

The following decisions intentionally remain open:

```text
Frontend framework:
TBD
Possible: React / Vue / other

Frontend language:
TBD
TypeScript possible, not yet mandatory

Backend web framework:
TBD
Java preferred
Possible: Spring Boot / Quarkus / other

Database migration tool:
TBD

Reverse proxy:
TBD

Hosting provider:
TBD

Authentication:
TBD, only if required

Long-running job implementation:
TBD
```

An implementation agent must not choose one of these technologies merely because it is familiar without an explicit project decision.

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
6. The frontend must not receive secrets.
7. The frontend must not duplicate authoritative calculations.
8. New dependencies must respect inward dependency direction.
9. Technology choices marked `TBD` must not be silently finalized.
10. Major architecture changes should be explicit and documented.

---

# 33. Deferred crafting calculation performance requirement

Performance investigation for Crafting Profit and Crafting Discovery is future planned work, blocked until intended calculation behavior is implemented and known functional defects are resolved. It is not executable work in the current planning pass.

Begin with reproducible baseline measurements using representative real application data where practical: opening/refreshing each view until usable results, individual recipe resolution/evaluation where meaningful, and total time in major pipeline stages. Identify actual bottlenecks rather than assume a solution. Investigation areas may include database access and repeated queries, recipe loading, graph traversal and resolution, repeated calculations and simulation, price lookups, recomputation, and UI refreshes; caching, batching, reuse, indexing, parallelism, or avoiding work are possibilities only.

Before major optimization, record the baseline, bottlenecks and measured time attribution, materially different viable approaches, their advantages/disadvantages, and correctness, maintainability, memory, database-load and complexity risks. Prefer multiple viable proposals where they exist. A clearly superior approach without meaningful tradeoffs may proceed normally; meaningful product, architecture, complexity, resource-use or maintainability tradeoffs require a User Decision. Large architectural changes require measured justification.

Correctness takes priority: preserve DOMAIN_SPEC.md behavior and intentional limits, never silently skip valid calculations or reduce correctness. Avoid unrepresentative synthetic targets. Temporary instrumentation must not clutter production behavior unless it retains diagnostic value. Repeat the same measurements after optimization, verify automated tests still pass, and document before/after evidence of substantial improvement in time to usable results. Independent measured bottlenecks may be addressed separately.


# 34. Status

This document defines the initial target architecture.

It intentionally specifies architectural boundaries more strongly than implementation technology.

The target architecture is considered stable enough to guide:

- migration planning,
- test strategy,
- repository restructuring,
- Claude Code instructions.

Technology choices marked `TBD` remain open and should be resolved only when implementation work reaches the point where the decision is necessary.
