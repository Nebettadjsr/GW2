# GW2 Tool — Current State Specification

## 1. Purpose

The GW2 Tool is a desktop application for analyzing Guild Wars 2 account data, crafting recipes, materials, Trading Post prices, and selected game mechanics.

Its primary purpose is to help the user make economic decisions based on:

- owned account materials,
- known crafting recipes,
- character crafting capabilities,
- current Trading Post prices,
- crafting dependencies,
- expected crafting profit.

The application currently runs as a local Java desktop application.

---

## 2. Current Technology Stack

### Application

- Java 25
- JavaFX desktop UI
- PostgreSQL database
- Direct HTTP communication with the official Guild Wars 2 API
- Jackson for JSON processing
- PostgreSQL JDBC driver

### Dependency Management

The project is built with Maven (via the committed Maven Wrapper, `./mvnw`).

Jackson, the PostgreSQL JDBC driver, JavaFX, and JUnit are all managed as ordinary Maven dependencies in `pom.xml`. There is no `lib/` directory of manually-managed JAR files anymore.

Source, resources, and tests follow the standard Maven layout (`src/main/java`, `src/main/resources`, `src/test/java`).

---

## 3. External Systems

The application depends primarily on two external systems.

### 3.1 Guild Wars 2 API

The official ArenaNet Guild Wars 2 API provides:

- item information,
- recipes,
- Trading Post data,
- account bank contents,
- account material storage,
- account recipes,
- character information,
- character crafting disciplines.

Some API endpoints require a personal Guild Wars 2 API key.

### 3.2 PostgreSQL

PostgreSQL is used as the persistent local data store.

The database contains both:

- globally relevant GW2 data,
- user/account-specific data.

---

## 4. High-Level Runtime Model

The current application can conceptually be represented as:

```text
Guild Wars 2 API
       |
       v
 API / Sync Code
       |
       v
  PostgreSQL DB
       |
       v
Repositories / Crafting Logic
       |
       v
Controllers / JavaFX Views
       |
       v
      User
```

The boundaries between these areas currently exist in the source structure but are not consistently enforced.

---

## 5. Main Application

The JavaFX application entry point is:

```text
src/Gw2App.java
```

The home screen provides access to data synchronization and application features.

Current home-screen actions include:

### Data Operations

- First-time database setup
- Sync all tradeable items and recipes
- Rebuild the crafting graph
- Sync account data
- View account bank
- View account materials

### User Features

- Ectoplasm salvage / Luck calculator
- Crafting Profit Calculator
- Crafting Discovery Helper

Long-running synchronization operations are currently started from the JavaFX application using background threads.

UI status labels are updated when operations finish or fail.

---

## 6. Configuration

Application configuration currently includes:

- Guild Wars 2 API key
- PostgreSQL connection URL
- PostgreSQL username
- PostgreSQL password

Configuration is currently associated with:

```text
src/repo/AppConfig.java
```

The JavaFX start page contains an API-key input field, but persistence through this field is currently not implemented.

---

## 7. Database

Database creation currently uses a manually executed SQL script:

```text
src/PostgreSQL Query to create DB
```

There is currently no automated schema migration system.

### 7.1 Global Data Tables

#### `items`

Stores GW2 item metadata.

Relevant fields include:

- item ID
- name
- type
- rarity
- level
- vendor value
- icon information
- fetch timestamp

#### `recipes`

Stores GW2 crafting recipes.

Relevant fields include:

- recipe ID
- recipe type
- output item
- output quantity
- crafting duration
- minimum crafting rating
- crafting disciplines
- flags
- guild ingredients
- fetch timestamp

#### `recipe_ingredients`

Maps recipes to their required ingredients.

Each entry contains:

- recipe ID
- item ID
- required count

#### `tp_prices`

Stores Trading Post price information.

Relevant values include:

- buy quantity
- buy unit price
- sell quantity
- sell unit price
- fetch timestamp

#### `tp_tradeable_items`

Stores item IDs which are currently tradeable on the Trading Post.

---

## 8. Account Data

### 8.1 `account_materials`

Stores materials contained in the account material storage.

Relevant values include:

- item ID
- count
- material category
- binding information

### 8.2 `account_bank`

Stores items contained in the account bank.

Relevant values include:

- bank slot
- item ID
- quantity
- binding data
- charges
- stats

### 8.3 `account_recipes`

Stores recipes unlocked on the account.

---

## 9. Character Data

### 9.1 `characters`

Stores basic character information such as:

- name
- profession
- race
- gender
- level
- creation date

### 9.2 `character_crafting`

Stores crafting disciplines for each character.

Each entry contains:

- character
- discipline
- crafting rating
- whether the discipline is active

### 9.3 `character_recipes`

Stores recipe knowledge associated with individual characters.

### 9.4 `character_items`

Stores character-held items.

The table can represent:

- inventory/bag items,
- equipment.

Relevant location information includes:

- character,
- bag,
- slot,
- equipment slot,
- item,
- quantity.

---

## 10. Synchronization

Synchronization code exists as a separate source area.

Current synchronization responsibilities include:

### Initial Setup

The initial database setup imports the base data required for the application.

This includes global GW2 information and account-related information.

### Account Synchronization

The application can synchronize:

- account bank,
- material storage,
- account recipes,
- characters,
- character crafting disciplines,
- character recipe information.

### Global Data Synchronization

The application can synchronize:

- tradeable Trading Post items,
- recipe information.

After a global recipe/item refresh, the Crafting Graph can be rebuilt.

---

## 11. API Layer

GW2 API communication is located primarily under:

```text
src/api/
```

Known responsibilities include:

- HTTP access to Guild Wars 2 API endpoints,
- batching API requests,
- retrieving item price information,
- handling HTTP status results,
- Trading Post related communication.

Representative classes include:

```text
Gw2ApiClient
Gw2PriceFetch
BatchUtils
HttpStatus
```

---

## 12. Repository Layer

Database access is primarily located under:

```text
src/repo/
```

Representative repositories include:

```text
CharacterRepository
InventoryRepository
ItemRepository
RecipeRepository
```

Additional Trading Post persistence functionality exists under:

```text
src/repo/tp/
```

The repositories provide persistence access used by application and crafting logic.

---

## 13. Crafting Domain

Crafting-related logic is primarily located under:

```text
src/craft/
```

This is one of the most complex parts of the current application.

Important concepts currently include:

```text
CraftingGraph
CraftingGraphCache
CraftingPlanner
CraftingResolver
CostEvaluator
CraftResult
CostEvaluationResult
PlanState
CraftingSettings
DailyCrafts
Node
```

The crafting subsystem must reason about recursive crafting dependencies.

An item may:

- already exist in the user's inventory,
- be purchased,
- be crafted,
- require another craftable item,
- be unavailable under the current constraints.

Therefore crafting calculations form a dependency graph rather than a single-level recipe lookup.

---

## 14. Crafting Graph

The application maintains a Crafting Graph representing relationships between:

```text
output item
    |
    +-- ingredient
    |
    +-- ingredient
           |
           +-- ingredient
           +-- ingredient
```

This allows calculations involving intermediate crafting steps.

The graph can be cached.

A repository-level JSON file currently exists:

```text
crafting_graph_cache.json
```

The cache contains several megabytes of generated crafting graph data.

The graph may be rebuilt after recipe/global item synchronization.

---

## 15. Crafting Profit Feature

The Crafting Profit feature attempts to answer:

> What can the user craft with available resources, and is doing so more valuable than selling the underlying materials?

The feature uses combinations of:

- owned materials,
- recipes,
- crafting capabilities,
- Trading Post prices,
- recursive crafting dependencies.

Relevant outputs include:

- item sell price,
- material value,
- profit per craft,
- number of possible crafts,
- total potential profit.

Conceptually:

```text
profit_per_craft =
    crafted_item_value
    - trading_post_fee
    - material_value
```

and:

```text
total_profit =
    craftable_count
    * profit_per_craft
```

The Trading Post fee is 15% of the same gross crafted item value, deducted once, by the backend
domain, and only from these two profit figures. Item sell price, total sell value and the displayed
instant buy / instant sell quotes remain the gross Trading Post values.

The implementation may involve additional acquisition and recursive crafting rules beyond this simplified formula.

---

## 16. Account Material Pool

For crafting calculations, available materials may be aggregated across several account locations.

Current intended behavior includes materials stored in:

- account material storage,
- bank,
- character inventory.

The application therefore treats relevant account-owned materials as a combined logical resource pool for calculations.

This logical pool does not necessarily correspond exactly to what the in-game crafting interface can access at a specific moment.

---

## 17. Crafting Discovery Feature

The Crafting Discovery Helper is intended to help identify recipes that a character can potentially discover.

The feature considers information including:

- selected character,
- selected crafting discipline,
- crafting level/rating,
- recipes already known,
- recipes eligible for discovery,
- recipe material requirements,
- item prices.

The feature can be used to find recipes useful for:

- crafting progression,
- recipe discovery,
- economic evaluation.

---

## 18. Trading Post Prices

Trading Post prices are not necessarily refreshed globally for every item during normal application use.

The system attempts to limit API requests to relevant item sets.

Different views may therefore request different Trading Post datasets.

Price refresh behavior is feature-dependent.

The application currently uses manual price refresh operations rather than a centralized continuously updated price service.

---

## 19. Ectoplasm / Luck Calculator

The application contains a separate economic calculator for salvaging Glob of Ectoplasm.

It evaluates the relationship between:

- ectoplasm purchase cost,
- Crystalline Dust value,
- Trading Post selling fees,
- expected Luck obtained from salvaging.

A legacy/simple implementation also exists in:

```text
src/Main.java
```

Current calculations include the Guild Wars 2 Trading Post selling fee:

```text
15%
```

The calculator estimates values such as:

- effective loss/profit per salvaged ectoplasm,
- cost per 1000 Luck.

---

## 20. JavaFX Views

The current interface is implemented directly in JavaFX.

Major views include:

```text
Gw2App
BankView
MaterialsView
EctoView
CraftingProfitView
CraftingDiscoveryView
```

Crafting-related views are relatively large classes.

The application currently contains UI construction, UI event handling, synchronization invocation, and application coordination within or close to the JavaFX layer.

---

## 21. Controllers

Dedicated controllers currently exist for at least the major crafting features.

Examples:

```text
CraftingProfitController
CraftingDiscoveryController
```

The controllers coordinate parts of the feature behavior between:

- persistence,
- crafting calculations,
- price data,
- UI-facing results.

The exact separation between controller, domain, persistence, and UI responsibilities is not currently treated as a strict architectural boundary.

---

## 22. Current Initialization Model

Initial setup is user-triggered from the desktop application.

The process performs the base population required by the database.

After initial setup, normal operation primarily consists of:

```text
sync global data when necessary
        +
sync account data
        +
refresh relevant prices
        +
run calculations
```

---

## 23. Current Generated / Cached Data

The repository currently contains a generated Crafting Graph cache:

```text
crafting_graph_cache.json
```

Its size is approximately several megabytes.

This data is derived from recipe information and can be rebuilt by application logic.

---

## 24. Current Error Handling

Error handling currently varies by subsystem.

In JavaFX-triggered background operations, exceptions are generally:

- caught,
- printed,
- converted into UI status/error messages.

There is currently no documented centralized error model.

There is also no documented global retry/backoff policy covering all Guild Wars 2 API operations.

---

## 25. Current Concurrency Model

Long-running operations launched by the JavaFX UI are executed using manually created Java threads.

JavaFX UI updates are returned to the JavaFX application thread using:

```text
Platform.runLater(...)
```

There is currently no centralized job or task execution system.

---

## 26. Current Build / Runtime Setup

The application is built with Maven (`./mvnw test`, `./mvnw javafx:run`). Java 25, JavaFX, Jackson, and the PostgreSQL driver are resolved automatically as Maven dependencies; no manual JAR management is required.

A developer/user still needs, outside of what Maven manages:

```text
PostgreSQL (running instance)
+
database schema
+
GW2 API key and database credentials (via .env, see .env.example)
```

The application is not currently packaged as a containerized service.

It is not currently exposed as a web application.

---

## 27. Current Testing State

Automated test infrastructure now exists: JUnit 5 (managed via Maven, `org.junit.jupiter:junit-jupiter`), run through `maven-surefire-plugin` via `./mvnw test`. Tests live under `src/test/java`.

Coverage is still minimal — currently a single focused regression test for the craft-vs-buy cost selection rule (`craft.CraftingResolverCraftVsBuyTest`). The project does not yet have a comprehensive automated regression suite for the central crafting calculations.

This means behavior changes in interconnected crafting logic can still be difficult to detect automatically outside of the one covered rule. See `docs/TEST_STRATEGY.md` for the intended coverage priorities.

---

## 28. Current Architectural Areas

The existing project already contains the beginnings of several architectural areas:

```text
UI
Controllers
Sync
API access
Repositories
Models
Crafting domain logic
Database
```

These areas are physically recognizable in the source tree.

However, the current application does not yet define a documented architectural contract describing:

- allowed dependencies,
- forbidden dependencies,
- ownership of business rules,
- ownership of synchronization,
- ownership of transactions,
- boundaries between UI and domain logic.

---

## 29. Important Current-State Characteristic

The application is not large in the sense of having many independent product features.

Its complexity primarily comes from the interaction between:

```text
external API state
+
persistent database state
+
user/account state
+
recursive crafting dependencies
+
price data
+
character-specific constraints
+
UI state
```

The crafting subsystem is therefore logically complex despite the relatively small overall application size.

---

## 30. Current User Workflow

A typical current workflow can be summarized as:

```text
Start application
       |
       v
Initialize DB if required
       |
       v
Synchronize global GW2 data
       |
       v
Synchronize account data
       |
       v
Refresh relevant Trading Post prices
       |
       v
Open analysis feature
       |
       v
Run calculation
       |
       v
Inspect result in JavaFX UI
```

---

## 31. Known Incomplete Areas

Known or visible incomplete areas include:

- API key saving from the home UI is currently a placeholder.
- Database setup is manual.
- Application packaging/deployment is manual.
- Architectural boundaries are not formally documented.
- Regression protection for complex business logic is currently limited or undocumented.
- The application remains tied to the JavaFX desktop runtime.

These statements describe the present implementation and do not imply that a particular redesign has already been selected.

---

## 32. Scope of This Document

This document describes the observed current state.

It deliberately does **not** define:

- the future architecture,
- whether Java should be retained,
- whether Spring Boot should be used,
- whether JavaFX should be removed,
- the future web frontend technology,
- Docker/container architecture,
- Claude/agent workflows,
- coding standards,
- refactoring priorities.

Those topics belong in separate documents.

---

## 33. Planned Companion Documents

The current-state specification should eventually be accompanied by:

```text
docs/
├── CURRENT_STATE_SPEC.md
├── DOMAIN_SPEC.md
├── CURRENT_ARCHITECTURE.md
├── KNOWN_PROBLEMS.md
├── TARGET_ARCHITECTURE.md
└── AGENTS.md
```

Their intended responsibilities are:

### `CURRENT_STATE_SPEC.md`

What the application currently does.

### `DOMAIN_SPEC.md`

The actual GW2 and economic rules the software must implement.

### `CURRENT_ARCHITECTURE.md`

How the existing implementation is structured and how components depend on each other.

### `KNOWN_PROBLEMS.md`

Known defects, architectural risks, ambiguities, technical debt, and incomplete functionality.

### `TARGET_ARCHITECTURE.md`

The architecture we intentionally want to move toward.

### `AGENTS.md`

Instructions and constraints for Claude or other coding agents working in the repository.

---

## 34. Status

This is a reverse-engineered current-state specification.

It should be refined as individual source areas are inspected.

Where implementation behavior and previous assumptions differ, the implementation must first be documented as the current state before a desired behavior is defined separately.