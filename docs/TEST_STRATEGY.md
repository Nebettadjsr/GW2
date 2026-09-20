# GW2 Tool — Test Strategy

## 1. Purpose

This document defines the testing strategy for the GW2 Tool.

The goal is to make changes safer by detecting regressions early, especially in the crafting and economic logic where a fix in one area can easily affect another.

The strategy prioritizes:

- fast domain tests,
- regression protection,
- characterization of existing behavior,
- small integration tests around infrastructure,
- only a small number of end-to-end tests.

The application should not depend on manual UI testing as its primary safety mechanism.

---

# 2. Testing Objectives

The test suite should answer the following questions:

1. Does the domain logic follow `DOMAIN_SPEC.md`?
2. Did a code change break previously working behavior?
3. Do recursive crafting calculations conserve inventory and quantities?
4. Do database repositories read and write data correctly?
5. Does the GW2 API adapter interpret external data correctly?
6. Do backend API endpoints return the expected contract?
7. Can the main user flows work end-to-end?

---

# 3. Test Pyramid

The project should prefer many small fast tests and only a few expensive broad tests.

```text
            Few
      End-to-End Tests
          /       \
     API / Integration
        Tests
      /           \
   Application Tests
      /         \
   Domain Unit Tests
          Many
```

The majority of business rules should be protected by domain-level tests.

---

# 4. Test Categories

## 4.1 Domain Unit Tests

These are the most important tests in the project.

They test business rules without:

- PostgreSQL,
- HTTP,
- Docker,
- JavaFX,
- frontend code,
- live GW2 API calls.

Domain tests should use plain in-memory objects.

Examples:

- consume owned materials before buying,
- choose minimum total economic cost,
- reuse intermediate leftovers,
- handle recipe output counts greater than one,
- prevent inventory duplication,
- select same-discipline recipe where possible,
- choose cheapest valid fallback recipe,
- value non-tradable items correctly,
- reject invalid purchase paths,
- detect recursive cycles,
- respect buying-disabled mode,
- respect budget limits,
- apply discovery rules,
- respect soulbound restrictions.

These tests should run very quickly and be suitable for execution after every relevant change.

---

## 4.2 Characterization Tests

Characterization tests capture behavior of the existing implementation before refactoring.

Their purpose is not to prove that the behavior is correct.

Their purpose is to answer:

```text
What does the current code actually do?
```

Use characterization tests when:

- a complex existing class is about to be refactored,
- behavior is poorly understood,
- the same area previously produced regression chains,
- domain behavior is not yet isolated.

Example:

```text
Given:
specific inventory
specific recipes
specific TP prices

When:
existing planner evaluates recipe X

Then:
craftable count = 12
buy cost = 340
profit = 891
```

If the current result conflicts with `DOMAIN_SPEC.md`, document the conflict.

Do not preserve incorrect behavior permanently merely because a characterization test captured it.

In that case:

1. capture existing behavior,
2. add a test for the intended domain behavior,
3. change the implementation,
4. update or remove the obsolete characterization test.

---

# 5. Domain Spec as Test Source

`docs/DOMAIN_SPEC.md` is the primary source for domain acceptance cases.

Each important stable rule should eventually have one or more tests.

Example:

```text
DOMAIN RULE:
Choose the valid acquisition path with the lowest total effective economic cost.

TEST:
Craft path effective cost = 80
Buy path effective cost = 60

Expected:
Buy path selected
```

Another example:

```text
DOMAIN RULE:
Intermediate leftovers remain available in the same crafting simulation.

TEST:
Intermediate recipe outputs 6
First branch consumes 5
Second branch requires 4

Expected:
1 leftover reused
Only 3 additional units obtained
```

---

# 6. Critical Domain Test Areas

The following areas are considered high-risk and require strong regression coverage.

## 6.1 Inventory Consumption

Test:

- full inventory satisfaction,
- partial inventory satisfaction,
- no inventory,
- inventory across multiple requirements,
- inventory never becomes negative,
- the same quantity is never reused twice.

Invariant:

```text
remaining_inventory >= 0
```

---

## 6.2 Recursive Crafting

Test:

- one-level intermediate recipe,
- multi-level recipe chain,
- multiple branches sharing the same intermediate,
- repeated use of the same material,
- recursive leftovers,
- cycle detection.

The planner must always terminate.

---

## 6.3 Output Quantities

Test recipes where:

```text
output_count > 1
```

Important cases:

```text
need 5
recipe outputs 3
```

Expected:

```text
2 crafts
6 produced
5 consumed
1 leftover
```

---

## 6.4 Craft vs Buy

Test all important combinations:

- craft cheaper than buy,
- buy cheaper than craft,
- same effective cost,
- craft uses owned materials,
- craft requires additional purchases,
- buying disabled,
- buy price unavailable.

The selected valid path should minimize:

```text
total effective economic cost
```

---

## 6.5 Opportunity Cost

Test that owned tradable materials contribute economic cost even when cash cost is zero.

Example:

```text
owned material value = 100 copper
cash spent = 0

effective cost must include 100 copper
```

---

## 6.6 Non-Tradable Materials

Test the valuation fallback in this exact order:

```text
1. crafting cost
2. vendor sell value
3. zero + UNVALUED_NONTRADEABLE
```

Cases should include:

- recipe exists and is unlocked,
- recipe exists but is not unlocked,
- no recipe but vendor value exists,
- neither recipe nor vendor value exists.

The final case must expose the explicit unvalued state.

---

## 6.7 Bound Materials

Test:

- account-bound material used by another eligible character,
- soulbound material used by bound character,
- soulbound material rejected for another character,
- bound non-tradable material valuation.

---

## 6.8 Multiple Recipes

Test:

- same item has multiple recipes,
- one recipe matches parent discipline,
- several recipes match parent discipline,
- no recipe matches parent discipline.

Expected selection order:

```text
same discipline first
then lowest effective cost
otherwise lowest effective cost across remaining recipes
```

---

## 6.9 Daily Items

Test the configured buy-instead-of-craft behavior.

### useOwnMats = true

Expected:

```text
use owned daily items first
buy missing quantity
never craft
```

### useOwnMats = false

Expected:

```text
ignore owned daily items
buy required quantity
never craft
```

Also test blocked behavior when the required item cannot be purchased.

The system does not track whether today's daily craft has already been used.

---

## 6.10 Buying Budget

Test:

- unlimited budget representation,
- exact budget match,
- cost below budget,
- cost above budget,
- recursive purchases contributing to budget.

A result reported as feasible under a positive budget must satisfy:

```text
purchase_cost <= max_buy_copper
```

---

## 6.11 Trading Post Price Selection

Test price-direction rules explicitly.

### Buying

```text
instant buy -> sell_unit_price
buy order   -> buy_unit_price
```

### Selling

```text
instant sell -> buy_unit_price
listing sell -> sell_unit_price
```

Missing or invalid prices must never become free purchases.

---

## 6.12 Crafting Profit

Test:

```text
profit
=
output revenue
-
purchase cost
-
owned material opportunity cost
```

For general Crafting Profit:

```text
do not subtract an additional 15% Trading Post fee
```

Test:

- positive profit,
- zero profit,
- negative profit,
- output count greater than one,
- mixtures of owned and bought materials.

---

## 6.13 Discovery

Test:

- recipe unlocked account-wide -> not a discovery candidate,
- recipe unknown account-wide -> candidate when valid,
- insufficient crafting rating,
- wrong discipline,
- non-discoverable recipe,
- unlock by one character removes it for all characters.

---

# 7. Property / Invariant Tests

Some rules should be tested as invariants across many scenarios.

Important invariants include:

```text
inventory never negative
```

```text
no owned quantity consumed more than once
```

```text
recursive resolution terminates
```

```text
buying disabled => purchased quantity = 0
```

```text
positive budget => purchase cost <= budget for feasible plans
```

```text
unknown TP price != zero-cost purchase
```

```text
requested quantities are conserved
```

Property-based testing may be introduced later if useful, but it is not required for the first migration phase.

---

# 8. Application Service Tests

Application tests verify orchestration without using real infrastructure.

Use fake or in-memory implementations for:

- repositories,
- price sources,
- GW2 API ports.

Example use cases:

```text
CalculateCraftingProfit
GetCraftingDiscoveryCandidates
RefreshAccountData
RefreshTradingPostPrices
```

Application tests should verify:

- correct repository calls,
- correct domain input construction,
- correct handling of failures,
- correct returned application result.

They should not duplicate domain calculations already covered by domain tests.

---

# 9. Repository Integration Tests

Repository tests verify actual PostgreSQL behavior.

These tests should cover:

- SQL correctness,
- mapping between rows and internal models,
- inserts,
- updates,
- deletes where relevant,
- uniqueness constraints,
- null handling,
- transactional behavior where relevant.

They may use a disposable test PostgreSQL instance.

Preferred future options may include:

- Docker-based PostgreSQL during tests,
- Testcontainers,
- another isolated test database approach.

Exact tooling is not yet decided.

Repository integration tests should not require the developer's normal database.

---

# 10. GW2 API Adapter Tests

Tests for external GW2 API integration should usually use stored fixture responses or mocked HTTP responses.

Do not use the live Guild Wars 2 API for normal automated test runs.

Test:

- successful response parsing,
- missing fields,
- null values,
- batch handling,
- non-200 responses,
- rate-limit responses,
- invalid data,
- conversion from external API models to internal models.

A small explicit live integration check may exist later, but it must not be required for normal test execution.

---

# 11. Backend API Tests

Once a web backend exists, test HTTP contracts separately from domain logic.

Test examples:

```text
valid request -> expected status and response
invalid input -> validation error
missing resource -> expected error
domain failure -> mapped API error
```

API tests should verify transport behavior.

They should not be the primary place where crafting mathematics is tested.

---

# 12. Frontend Tests

The future frontend should focus tests on:

- rendering,
- user interaction,
- request construction,
- state transitions,
- presentation of backend results.

Do not duplicate authoritative domain calculations in frontend tests.

Example:

```text
backend returns UNVALUED_NONTRADEABLE
frontend displays visible warning marker
```

The frontend test does not recalculate whether the item should have that domain state.

---

# 13. End-to-End Tests

Keep end-to-end tests limited to a few critical user flows.

Possible future flows:

```text
sync/load test data
→ open crafting profit
→ submit settings
→ receive calculation
→ display result
```

and:

```text
select character + discipline
→ load discovery candidates
→ verify expected recipe is visible
```

End-to-end tests are slower and more fragile.

Do not use them to cover every domain rule.

---

# 14. No Live External Dependencies in Normal Tests

Normal automated tests must not depend on:

- live Guild Wars 2 API availability,
- production databases,
- internet access,
- real user API keys.

Tests must be repeatable.

Given the same inputs, they should produce the same outputs.

---

# 15. Test Data

Prefer small, purpose-built test fixtures.

Avoid loading the full production crafting graph for every unit test.

Example:

```text
3 recipes
5 items
small inventory
small TP price map
```

is preferable to:

```text
entire GW2 item database
entire crafting graph
```

Large realistic fixtures may be used for selected integration/regression tests only.

---

# 16. Named Regression Cases

When a real bug is found, preserve it as a named regression test.

Example naming:

```text
shouldReuseIntermediateLeftoverAcrossSiblingBranches
```

```text
shouldNotUseSoulboundMaterialFromAnotherCharacter
```

```text
shouldPreferCheaperBuyOverOwnedMaterialCraftPath
```

```text
shouldMarkNonTradableItemWithoutRecipeOrVendorValueAsUnvalued
```

This creates a permanent record of previously broken behavior.

---

# 17. Bug-Fix Workflow

For a bug fix, prefer this workflow:

```text
1. reproduce bug
2. write failing regression test
3. confirm test fails for expected reason
4. make smallest code change
5. confirm test passes
6. run nearby relevant tests
7. run broader suite if impact warrants it
```

Avoid changing multiple unrelated areas while fixing one bug.

---

# 18. Refactoring Workflow

Before refactoring a complex existing area:

```text
1. identify current behavior
2. add characterization tests where needed
3. add domain-spec tests for intended behavior
4. refactor in small steps
5. keep tests passing between steps
```

If behavior intentionally changes during refactoring, that change must be explicit and backed by the Domain Spec.

---

# 19. Migration Testing

During migration from JavaFX desktop to web architecture:

- keep domain tests independent of UI technology,
- preserve existing validated calculation behavior,
- add backend API tests when endpoints appear,
- add frontend tests only for presentation/interaction,
- do not rewrite domain tests just because the UI changes.

The same domain test suite should survive the removal of JavaFX.

---

# 20. Test Execution Scope

Claude should run the smallest relevant test scope first.

Preferred order:

```text
1. exact affected test
2. affected test class/module
3. related domain suite
4. full test suite
```

Do not run expensive broad test suites repeatedly when a focused test is sufficient during iteration.

Before declaring a significant change complete, run an appropriately broad regression set.

---

# 21. Test Failure Handling

A failing test must not automatically be changed to match new code.

First determine whether:

- the implementation is wrong,
- the test is wrong,
- the specification changed,
- the test captures obsolete behavior.

If the test reflects a defined rule in `DOMAIN_SPEC.md`, the implementation should normally be fixed.

---

# 22. Tests Must Be Understandable

Tests should clearly express:

```text
Given
When
Then
```

or an equivalent structure.

Avoid tests that depend on large amounts of hidden setup.

A developer or coding agent should be able to understand the protected rule from the test itself.

---

# 23. Test Naming

Prefer descriptive behavior-oriented test names.

Good:

```text
shouldUseVendorValueWhenNonTradableItemHasNoRecipe
```

Bad:

```text
testCase7
```

Test names should describe the business behavior being protected.

---

# 24. Initial Test Priorities

The first testing phase should focus on the existing crafting engine before broad infrastructure testing.

Priority order:

```text
1. inventory consumption
2. recursive crafting
3. output quantities and leftovers
4. craft-vs-buy selection
5. opportunity cost
6. multiple recipe selection
7. non-tradable valuation
8. bound materials
9. daily-item behavior
10. discovery rules
11. budget handling
12. Trading Post price semantics
```

These areas have the highest likelihood of causing hidden regressions.

---

# 25. Initial Testing Goal

The first milestone is not "100% test coverage."

The first milestone is:

> The central crafting calculation can be changed without relying on manual testing to discover unrelated breakage.

A smaller set of high-value tests is preferable to a large number of shallow tests.

---

# 26. Coverage Metrics

Code coverage may be used as an informational metric later.

Coverage percentage is not a success criterion by itself.

The project should prioritize:

- domain rule coverage,
- regression coverage,
- meaningful edge-case coverage.

Do not create meaningless tests solely to increase coverage percentage.

---

# 27. Claude Code Testing Rules

For tasks involving behavior changes, Claude should:

1. read `docs/DOMAIN_SPEC.md`,
2. read this document,
3. inspect existing relevant tests,
4. add or update tests before or together with the code change,
5. run the smallest relevant test scope,
6. report the exact tests run.

For pure UI styling or documentation-only changes, domain test execution is not automatically required.

Claude must not:

- delete failing tests merely to make a build green,
- weaken assertions without justification,
- introduce live external API dependencies into normal tests,
- rely solely on manual verification for domain behavior.

---

# 28. Current-State Reality

The project now has a standard build system (Maven, via `./mvnw`) and a test framework (JUnit 5, run through Surefire), but still lacks:

- clean architectural boundaries (the domain layer currently depends on repository types — see `docs/KNOWN_PROBLEMS.md`),
- easy dependency injection,
- isolated domain classes,
- broad test coverage (current test count/status lives in `agent/PROJECT_STATE.md`, not here).

That setup should remain minimal.

Do not introduce a large testing platform beyond what the current testing need actually requires.

---

# 29. Tooling Decisions

JUnit 5 (`org.junit.jupiter:junit-jupiter`), managed as a Maven dependency and run via `maven-surefire-plugin`, is now the project's test framework.

Not yet decided:

```text
Mockito or simple handwritten fakes
Testcontainers for PostgreSQL integration
```

These remain candidates, not mandatory decisions.

The selected tooling should favor:

- simplicity,
- fast execution,
- good IDE support,
- good Claude Code usability,
- minimal configuration overhead.

---

# 30. Status

This document defines the target testing strategy for the GW2 Tool.

Its primary role is to prevent the previous failure mode where:

```text
fix bug A
→ accidentally break B
→ fix B
→ accidentally break C
```

The initial implementation effort should focus on high-value domain and characterization tests around the crafting engine before major refactoring or web migration begins.

---

# 31. Persistence & External-API Test Layers

This section formalizes, as four explicit layers, how §4.1, §9, and §10 above combine to cover persistence and Guild Wars 2 API behavior specifically. `docs/ROADMAP.md` references these layer names directly in its exit criteria; this section is what defines them. `docs/ROADMAP.md` decides *when* a layer's coverage must exist by phase — it does not redefine *how* the layer works.

## 31.1 Layer 1 — Unit / Domain Tests

Defined in full at §4.1. Summary of the properties that matter for the other layers' contrast:

- deterministic, no network, no real database,
- small hand-built fixtures (§15), not the production crafting graph,
- run as part of the normal fast `./mvnw test` suite.

## 31.2 Layer 2 — PostgreSQL Integration Tests

Extends §9. A test in this layer must:

- run against a real PostgreSQL engine — never an in-memory or mocked substitute,
- use an isolated, disposable test database — never the normal development database, and never require the developer's own local DB state or content,
- apply the real schema/migrations/setup that production code actually runs against,
- exercise actual SQL behavior: constraints, transactions, upserts, NULL semantics, stale-row deletion, and repository-to-model mappings,
- be able to recreate its required test data from scratch, reproducibly, with no dependency on data left over from a previous run.

Exact tooling (Testcontainers, another Docker-based approach, or another isolated-database mechanism) remains an open decision — see §29. This document intentionally requires the *property* ("isolated real PostgreSQL") rather than mandating one specific technology ahead of that decision.

## 31.3 Layer 3 — GW2 API Contract/Fixture Tests

Extends §10. A test in this layer must:

- use captured real Guild Wars 2 API JSON responses as test resources, not hand-typed approximations that may drift from what the live API actually returns,
- exercise parser/sync behavior against those realistic payloads,
- remain deterministic and fully offline as part of the normal `./mvnw test` suite,
- keep fixtures as small as practical while still preserving the real API's relevant structure (trim an irrelevant field or array size before committing a fixture, but do not hand-write a payload shape from memory).

## 31.4 Layer 4 — Optional Live GW2 API Smoke Tests

Formalizes §10's closing line into its own layer:

- covers a small number of selected, critical endpoints only,
- is not part of the normal deterministic `./mvnw test` run (excluded from the default Surefire execution — e.g. a separate Maven profile, JUnit tag, or naming convention that keeps it out of the default `test` goal; the exact mechanism is left to the implementing story),
- is explicitly invoked, never run automatically as part of local or CI `mvn test`,
- is allowed to fail because of external network/API unavailability without ever making the normal unit/integration suite flaky,
- must never be the only coverage for parser/sync behavior — Layer 3's fixture-based tests remain the authoritative, always-run coverage; Layer 4 only adds a manual reality-check against the live API.

## 31.5 General Principles

- Choose the lowest layer that proves the behavior being tested; do not reach for an integration or live test when a unit test already proves the rule.
- Domain behavior remains primarily unit-tested (§4.1, Layer 1); persistence behavior that depends on PostgreSQL-specific semantics (constraint enforcement, upsert conflict behavior, NULL-uniqueness quirks, transactional visibility) must be integration-tested against real PostgreSQL (Layer 2), not asserted from application code alone.
- External API parsing should primarily be verified using captured real responses (Layer 3), not hand-typed minimal JSON.
- Avoid testing implementation details (private helper structure, incidental call counts) when the same confidence can come from testing observable behavior.
- Regression tests should accompany bug fixes where practical (§17).
- Tests must be repeatable and independent: one test's outcome must never depend on another test's side effects or execution order.
- No test may depend on the developer's normal local database state, content, or credentials — this extends §9's rule for repository tests to the whole suite.
- Introducing new test tooling (e.g. Testcontainers, a fixture-generation library) is allowed when it is the practical way to satisfy a layer's requirements above, but the story that introduces it must document the choice and its reasoning, and must keep both local-developer and CI reproducibility in mind. §29 tracks which tooling decisions remain open; this section does not resolve them ahead of that decision.
