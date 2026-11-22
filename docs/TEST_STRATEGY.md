# GW2 Tool — Test Strategy

This document owns application testing methods, commands, coverage policy and verification limits.
[DOMAIN_SPEC.md](DOMAIN_SPEC.md) owns product rules and expected behavior, and
[ARCHITECTURE.md](ARCHITECTURE.md) owns boundaries and contracts; this document links to them rather
than restating them. Agent roles and delivery rules live in [AGENTS.md](../AGENTS.md) and the
[development workflow](../agent/DEVELOPMENT_WORKFLOW.md). Section numbers are stable reference
anchors used by source comments and CI; a number absent from the sequence was removed, not renumbered.

## GitHub issue and pull-request verification

Existing coverage can be reused; a low-risk change may need no new test when the PR states why.
Changed or weakened assertions need evidence-backed reasons. A green suite alone does not establish
that required behavior is correct, and CI and review evaluate the current head commit: an older green
result does not verify a newer revision.

## 1. Purpose

The strategy makes changes safer by detecting regressions early, especially in crafting and economic
logic where a fix in one area can easily break another ("fix A, break B, fix B, break C"). It prefers
fast domain tests, regression protection, characterization of existing behavior, small integration
tests around infrastructure and only a few end-to-end tests. Manual UI testing is not the primary
safety mechanism. Coverage does not replace regression confidence: a smaller set of high-value tests
beats many shallow ones, and the central calculation must be changeable without manual testing to find
unrelated breakage.

A failure found in review or CI is investigated against the issue and current code. A production
defect or an incorrect test is fixed with a reason visible in the diff; deleting, skipping or
weakening a test merely to make CI green is not a fix.

## 2. Testing Objectives

The suite answers: does domain logic follow `DOMAIN_SPEC.md`; did a change break working behavior;
do recursive calculations conserve inventory and quantities; do repositories read and write correctly;
does the GW2 adapter interpret external data correctly; do HTTP endpoints honor their contract; and can
the main user flows work end to end.

## 3. Test Pyramid

Prefer many small fast tests and only a few expensive broad tests. Most business rules are protected
at domain level.

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

## 4. Test Categories

### 4.1 Domain Unit Tests

The most important tests in the project. They test business rules with plain in-memory objects and
without PostgreSQL, HTTP, Docker, frontend code or live GW2 API calls, so they run in milliseconds and
suit execution after every relevant change. Typical cases: consume owned materials before buying, choose
the minimum total economic cost, reuse intermediate leftovers, handle output counts above one, prevent
inventory duplication, value non-tradable items, reject invalid purchase paths, detect cycles, respect
buying-disabled mode and budget limits, apply discovery and soulbound rules.

### 4.2 Characterization Tests

Characterization tests capture what the existing implementation actually does before refactoring; they
do not prove it is correct. Use them when a complex class is about to be refactored, behavior is poorly
understood, or the area previously produced regression chains. If the current result conflicts with
`DOMAIN_SPEC.md`, document the conflict instead of preserving the incorrect behavior: capture it, add a
test for the intended behavior, change the implementation, then update or remove the obsolete
characterization test.

## 5. Domain Spec as Test Source

[`DOMAIN_SPEC.md`](DOMAIN_SPEC.md) is the primary source of acceptance cases; each important stable
rule should have tests whose expected values are written independently of the implementation. For
example: craft path effective cost 80 and buy path 60 selects the buy path; an intermediate that outputs
6, consumed 5 by one branch, leaves 1 for a second branch that needs 4 and so needs only 3 more units.

## 6. Critical Domain Test Areas

High-risk areas need strong regression coverage. The rules are owned by
[`DOMAIN_SPEC.md`](DOMAIN_SPEC.md); the lists below name the cases a test set must include.

- **6.1 Inventory consumption:** full, partial, no and shared-across-requirements satisfaction.
  Invariants: remaining inventory is never negative and a quantity is never reused twice.
- **6.2 Recursive crafting:** one-level intermediates, multi-level chains, several branches sharing one
  intermediate, repeated material use, recursive leftovers and cycle detection. The planner terminates.
- **6.3 Output quantities:** recipes with `output_count > 1`; need 5 and output 3 means 2 crafts, 6
  produced, 5 consumed, 1 leftover.
- **6.4 Craft vs buy:** craft cheaper, buy cheaper, equal cost, crafting from owned materials, crafting
  that needs purchases, buying disabled, buy price unavailable. The valid path with the lowest total
  effective economic cost wins.
- **6.5 Opportunity cost:** owned tradable materials cost their value even when cash cost is zero.
- **6.6 Non-tradable materials:** the valuation fallback order (crafting cost, vendor value, zero plus
  `UNVALUED_NONTRADEABLE`) with recipe unlocked, recipe not unlocked, vendor value only, and neither
  (which must expose the explicit unvalued state).
- **6.7 Bound materials:** account-bound used by another eligible character, soulbound used by its
  character, soulbound rejected for another character, bound non-tradable valuation.
- **6.8 Multiple recipes:** one, several or none matching the parent discipline; same discipline first,
  then lowest effective cost, otherwise lowest across the remainder.
- **6.9 Daily items:** daily crafting is tested independently of inventory use and buying across the
  `useOwnMats` x `allowBuying` x `allowDailyCrafts` combinations: with `useOwnMats` owned daily items are
  used first and a shortfall is bought only when buying is enabled; without it owned daily items are
  ignored and at most one daily craft is allowed. Also: blocked when the item cannot be purchased,
  recursive daily ingredient to intermediate to parent paths, a disabled purchase never appears in nested
  missing-to-buy totals, and a speculative failure restores the daily allowance.
- **6.10 Buying budget:** unlimited, exact, below and above budget, and recursive purchases counting
  toward it; a feasible result under a positive budget satisfies `purchase_cost <= max_buy_copper`.
- **6.11 Trading Post price selection:** instant buy uses `sell_unit_price`, buy order uses
  `buy_unit_price`, instant sell uses `buy_unit_price`, listing sell uses `sell_unit_price`. Missing or
  invalid prices never become free purchases.
- **6.12 Crafting profit:** output revenue minus purchase cost minus owned-material opportunity cost, with
  no additional 15% Trading Post fee for general Crafting Profit; positive, zero and negative profit,
  output count above one, mixtures of owned and bought materials.
- **6.13 Discovery:** the selected character and discipline are also the sole owned-inventory scope; a
  candidate is one recipe attempt including outputs above one; no craft-count or duplicate
  total/per-attempt figures; a recipe unlocked account-wide is not a candidate and an unknown one is when
  valid; insufficient rating, wrong discipline and non-discoverable recipes are excluded; one character's
  unlock removes the recipe for all characters.

## 7. Property / Invariant Tests

Express rules as invariants across many scenarios: inventory never negative; no owned quantity consumed
twice; recursive resolution terminates; buying disabled means purchased quantity 0; a feasible plan under
a positive budget has purchase cost within it; an unknown TP price is not a zero-cost purchase; requested
quantities are conserved.

Critical rules must also hold over complete calculation results, not only inside one resolver or
allocator: for crafting, resolution, stock allocation, purchases, total costs, profitability, craftable
quantity and the aggregated shopping list agree for the same plan.

Property-based tests use jqwik where generated inputs add real breadth (recursive calculations, inventory
allocation, numeric boundaries, shared dependencies, setting combinations). Generated scenarios must be
reproducible: keep jqwik's reported seed in failure evidence and rerun the property with that seed before
diagnosing. Named production regressions and independently calculated results stay as deterministic
examples next to the properties.

## 8. Application Service Tests

Application tests verify orchestration with fake or in-memory repositories, price sources and GW2 API
ports. They verify the repository calls, the domain input construction, failure handling and the returned
application result, and they do not duplicate domain calculations. Coordination is verified by
controlled sequencing, not by sleeps.

## 9. Repository Integration Tests

Repository tests verify actual PostgreSQL behavior: SQL correctness, row/model mapping, inserts, updates,
deletes, uniqueness constraints, null handling and transactions. They run against isolated schemas on a
supplied PostgreSQL server (§29, §31.2) and never require the developer's normal database.

## 10. GW2 API Adapter Tests

Test external API integration with stored fixture responses or mocked HTTP, never the live API in normal
runs: successful parsing, missing fields, null values, batches, non-200 and rate-limit responses, invalid
data and the conversion to internal models. A small explicit live check may exist (§31.4) but is never
required for normal test execution.

## 11. Backend API Tests

Test HTTP contracts separately from domain logic: valid request to expected status and body, invalid input
to a validation error, missing resource to the expected error, domain failure to a mapped API error. They
verify transport behavior and are not where crafting mathematics is tested.

## 12. Frontend Tests

Frontend tests focus on rendering, user interaction, request construction, state transitions and the
presentation of backend results. They do not duplicate authoritative domain calculations: when the
backend returns `UNVALUED_NONTRADEABLE`, the test checks that a warning marker is displayed, not whether
the item should have that state.

### 12.1 How frontend tests are run

`npm test` in `frontend/` selects `src/**/*.spec.ts` and `scripts/**/*.spec.mjs` through `vite.config.ts`.
Component tests use jsdom with injected API stand-ins; transport and integrated interaction tests also
stub `fetch` to control responses and verify request wiring. They start no backend or database and make
no live GW2 calls. Five rules prevent failures that are otherwise silent:

1. **A stand-in must answer like the contract it stands in for.** The Profit route echoes the scope and
   settings it calculated with and the screen takes its control state from that echo; a fake that ignored
   the request and returned fixed settings would make the screen look right while the real backend made
   it wrong. The stand-in reproduces the echo.
2. **Fixtures must be able to expose a derived value.** Where a test checks that a backend-supplied figure
   is shown or sorted as supplied, the supplied total is deliberately not the product of its parts, so a
   frontend that recomputed it fails instead of agreeing by coincidence.
3. **A test must be able to reach the interaction it claims to cover.** A control disabled during a
   request cannot receive the event a test sends; assert the effect (a further request, changed rows), not
   only the absence of an error.
4. **A recursive payload is compared by walking it.** Flatten the response in its own child order and
   compare the whole sequence with what was rendered, so a dropped, reordered, merged or truncated
   occurrence fails. A repeated item appears as often as it was sent; nothing is recomputed in the test.
5. **A late or superseded answer needs its own test, with the promise resolved by hand.** Resolve the
   first request only after a second has started and assert the first cannot reach the screen, including
   the A to B to A case where the stale answer carries the identity that is selected again.

### 12.2 Frontend verification levels

| Level | Command | Establishes | Does **not** establish |
|---|---|---|---|
| Type check | `npm run type-check` | strict typing across `.ts` and `.vue` | that received JSON matches those types |
| Component/unit | `npm test` | rendering, interaction, request construction, state transitions | that the real backend connection works |
| Production build | `npm run build` | the locked dependency set builds to static assets | runtime behavior |
| Automatic controlled browser journeys | `npm run smoke:ci` | a real browser drives the built app through public reading, connection, failure/recovery, account switching, narrow-screen notice/scrolling and reservations against an owned synthetic API | real backend/SQL/upstream behavior, production performance or Vitest/JaCoCo line coverage |
| Explicit real-backend browser smoke | `npm run smoke:browser`, `npm run smoke:account` | a real browser retrieves and renders real backend results, compared with the very response it received | performance (§34), states the live data did not contain, domain correctness of a compared value |
| Browser smoke, controlled boundary | `npm run smoke:sync` | a real browser drives controls whose live counterpart would mutate data | that a real operation ran, or anything about the backend |
| Focused controlled layout and accessibility | `npm run smoke:layout`, `npm run smoke:profit` | supported desktop layout, increased text size, focus, contrast, result/detail placement and sticky positioning where the script measures them | unvisited combinations, real data, performance |
| Controlled asynchronous lifecycle | `npm run smoke:profit:refresh` | a scripted sequence of cached, replaced and failed refresh answers in a real browser | that the backend produces those sequences, real refresh timing |
| Backend contract connection | `npm run check:profit:envelope` | that the shape a controlled-boundary check imitates is the shape the running backend sends | row economics, timing, sequences the backend did not produce |

The explicit real-backend commands need their backend and frontend already running and assert against
actual responses. `smoke:ci` instead serves built assets and synthetic API responses from owned loopback
origins and starts no backend or database. Neither establishes the performance claims in §34. Record the
environment and measurement scope without exposing credentials. A special state the live database does not
currently produce (an unavailable price) is covered at component level with a controlled response, and the
result says so.

A check that serves the production build must establish that the build is current, not merely present: it
compares the emitted document with every file the build reads and refuses to start, naming the file and
both timestamps, when an input is newer. After substantial integration changes a real-backend observation
needs identified current code: use isolated owned processes and ports, rebuild and restart only those, keep
the ordinary user application running, and never claim evidence for current source from an unidentified
older process.

### 12.3 Checking controls that would mutate data

A read-only screen can be smoke-checked against the live backend; a control that starts a synchronization
cannot, because it would spend the GW2 API budget and write to the user's database.

1. **Point the real browser at a controlled origin instead of dropping the level.** Serve the built assets
   and answer the trigger and status routes with scripted task lifecycles: click handling, polling and
   rendering are real, nothing is synchronized. It establishes interaction and presentation only.
2. **Never imply the operation ran.** Record a controlled-boundary pass as what it is; claim a live
   synchronization only when one was observed.
3. **Prove the browser is on the controlled origin before driving a control.** A successful bind is not that
   proof: an address the check did not take (an IPv6-only dev server on the same port) can still answer the
   name `localhost` and proxy to the real backend. Bind and navigate to `127.0.0.1`, fail on a `listen`
   error and assert the check's own server served the page.

Polling tests use fake timers and hand-resolved promises to verify request counts, serialization and
termination; browser journeys use bounded event/DOM waits against controlled responses, avoid arbitrary
sleeps and infer no performance budget from their duration.

### 12.4 Checking presentation and accessibility

Layout, focus and contrast claims are measured in a real browser, never inferred from the stylesheet.
Apply the [supported-tool viewport policy](DOMAIN_SPEC.md#supported-browser-viewport): verify the notice at
390 and 1023 CSS pixels, normal tool content and measurable horizontal scrolling; at 1024 and wider tools
keep the desktop layout without the notice. Public reading pages may be checked at narrow widths. Keep
desktop keyboard/focus, short-height and zoom/text-enlargement assertions. Six rules keep a check honest:

1. **Measure the rendered combination, not the declared value.** Compute contrast from the
   `getComputedStyle` foreground and nearest opaque background of elements actually on the page, with the
   WCAG large-text threshold applied by measured size and weight. A tone the page does not show is measured
   by applying the shared class to a probe element.
2. **Reflow and zoom are numbers.** Horizontal overflow is `documentElement.scrollWidth` against
   `clientWidth` at each width; increased text size is the root font size raised and the comparison
   repeated. A screenshot is not a measurement.
3. **Start each observation from a fresh load.** A fragment-only navigation keeps the previous page's
   focus, scroll and state and silently invalidates a focus-order or first-load assertion. Reload first
   and assert focus order from an actual sequence of Tab presses.
4. **Record what was not established.** A pass over the combinations a script visited is not conformance;
   the result names the widths, interactions and states checked and the accessibility questions still open.
5. **Set up the condition a positional claim needs.** A `position: sticky` element only travels inside its
   containing block, so check pinning where the sibling column is measurably taller, assert the declared
   offset and separately that the element left its flow position. Component-scoped styles cannot be
   measured on a probe element; sample a real instance.
6. **Name what overflowed.** A failing reflow assertion lists the elements whose right edge passes the
   viewport, not only the width difference.

### 12.5 Automatic desktop user journeys

`npm run smoke:ci` runs `node scripts/run-desktop-journeys.mjs`, a curated set of current journeys run
sequentially, including the reservation journey. It is not a blanket invocation of every `smoke:*` script.
It uses the built app, an owned `127.0.0.1` origin, synthetic account proof and data, strict API
method/route/context checks and blocked real upstream traffic. Occupied ports, stale or incomplete bundles,
missing browsers, unexpected requests and failed assertions are failures, never skipped successes. No real
API key, backend or database is required; fixtures supply backend results and the browser checks implement
no economic rule. From `frontend/`, after `npm ci` and `npm run build`:

```bash
node node_modules/playwright-core/cli.js install --with-deps --no-shell chromium
export GW2_BROWSER_PATH="$(node --input-type=module -e "import { chromium } from 'playwright-core'; process.stdout.write(chromium.executablePath())")"
npm run smoke:ci
```

The CLI comes from the locked `playwright-core`; do not use an unpinned `npx` download or add another test
framework. On Windows, installed Chrome or Edge is found by `resolveBrowserPath`, or the managed Chromium is
installed without the Linux dependency flag.

Each child process is bounded to 180 seconds plus a 10-second owned-resource teardown, with no retries, and
CI bounds the step to eight minutes. Close owned pages, contexts, browser processes and stub servers on
success and failure, never attach to the ordinary user application, and never kill a process merely because
it holds an expected test port. Results go to `frontend/quality-reports/desktop-journeys/` (override
`GW2_SMOKE_OUTPUT`) with named journey/step outcomes, revision, browser scope and bounded failure details;
CI uploads them as `desktop-browser-journeys` with `if: always()` and seven-day retention. An absent or
incomplete result is never a passing journey. Screenshots and traces do not replace the owner's visual
acceptance of a changed layout, and journey counts are separate from the Vitest/JaCoCo percentages (§26).

## 13. End-to-End Tests

Keep end-to-end tests to a few critical flows and never use them to cover every domain rule; they are
slower and more fragile. The controlled browser journeys (§12.5) are the current implementation.

## 14. No Live External Dependencies in Normal Tests

Normal automated tests must not depend on live GW2 API availability, production databases, internet access
or real user API keys. Tests are repeatable: the same inputs produce the same outputs.

## 15. Test Data

Prefer small, purpose-built fixtures (3 recipes, 5 items, a small inventory and price map) over the full
item database or crafting graph. Large realistic fixtures are for selected integration and regression tests.

## 16. Named Regression Cases

When a real bug is found, keep it as a named regression test that states the behavior, for example
`shouldReuseIntermediateLeftoverAcrossSiblingBranches` or `shouldNotUseSoulboundMaterialFromAnotherCharacter`.

## 17. Bug-Fix Workflow

Reproduce the bug, write a failing regression test and confirm it fails for the expected reason, make the
smallest code change, confirm it passes, run nearby tests and a broader suite when the impact warrants. Do
not change unrelated areas in the same fix.

## 18. Refactoring Workflow

Before refactoring a complex area: identify current behavior, add characterization tests where needed, add
domain-spec tests for the intended behavior, refactor in small steps and keep tests passing between steps.
An intentional behavior change must be explicit and backed by the Domain Spec.

## 19. Boundary Refactoring

When refactoring an application or transport boundary keep domain tests independent of UI technology,
preserve validated calculation behavior, add backend API tests where a changed endpoint needs them, add
frontend tests only for presentation and interaction, and do not rewrite domain tests because the UI
changes. The domain suite survives changes of presentation and transport technology.

## 20. Test Execution Scope

Local execution and full-regression execution have different owners (§36). Locally, run the smallest scope
that proves the change: the exact test, then the affected class or module, then the related domain suite
only when the change spans it. Do not run the complete backend, frontend or workflow-helper suite as routine
verification or repeatedly while iterating; GitHub CI runs the full regression against the pushed commit and
is the authoritative verdict. A local broad run is justified when the blast radius is genuinely unbounded (a
shared fixture, a build or dependency change, a rename across packages) or when explicitly requested. When
production behavior changes, search the tests for assertions of the old behavior and run those complete test
classes, not only new tests.

## 21. Test Failure Handling

A failing test is not changed to match new code automatically. First determine whether the implementation is
wrong, the test is wrong, the specification changed or the test captures obsolete behavior. If the test
reflects a rule in `DOMAIN_SPEC.md`, fix the implementation. Missing product intent needs a human decision,
not an invented expectation.

## 22. Tests Must Be Understandable

A test expresses Given, When, Then (or an equivalent structure), avoids large amounts of hidden setup, and
lets a reader understand the protected rule from the test itself.

## 23. Test Naming

Names describe the business behavior protected (`shouldUseVendorValueWhenNonTradableItemHasNoRecipe`), not
`testCase7`.

## 24. Regression Priorities

Preserve the crafting engine's core behavior when choosing regression checks, in this order: inventory
consumption, recursive crafting, output quantities and leftovers, craft-vs-buy selection, opportunity cost,
multiple recipe selection, non-tradable valuation, bound materials, daily-item behavior, discovery rules,
budget handling, Trading Post price semantics.

## 26. Coverage KPI and Reports

Test coverage is a permanent quality KPI for the Java backend and the TypeScript frontend. The long-term
target is at least **90% line and branch coverage**; below target is a warning and does not fail the build.
Coverage is measured by the CI test jobs and summarized by the `Coverage KPI report` job (overall and
per-module/package line and branch rates; the `coverage-reports` artifact holds HTML and machine-readable
results). Missing measurements are labeled unavailable and never counted as zero. Combined rates are a
tracking aid only: review each module and package so one well-covered area cannot hide a critical gap.

Reports can be generated locally:

- Java: `./mvnw test jacoco:report` in `backend/` (HTML at `backend/target/site/jacoco/index.html`).
- Frontend: `cd frontend && npm ci && npm run test:coverage` (HTML at `frontend/coverage/index.html`). Only
  application source under `src/` is measured; specs and test-only helpers are excluded.
- Combined: `python .github/scripts/publish_coverage_kpi.py` writes `quality-reports/coverage-kpi.md` and
  `.json`.

Use the identified CI revision and artifact as the baseline, distinguish measured, estimated and unavailable
values, and record comparisons in the issue or PR, not in another tracked ledger. New or changed behavior
aims at 90% in the affected areas with attention to business-critical branches and edge cases. Coverage is a
minimum indicator, not proof of correctness: meaningful assertions come before percentage growth, and never
add artificial tests or exclude legitimate production code to raise a number. Generated code may be excluded
only when the generator and the exclusion are documented; build scripts, deployment configuration, static
assets and external-system code that cannot reasonably be isolated may be recorded as unavailable with a
rationale. Business logic, adapters and error paths are not excluded because they are hard to test.

### 26.1 Test quality rules for implementation work

Tests are written with the behavior they protect unless an issue assigns that ownership elsewhere; review
checks that they establish the acceptance behavior, especially at integration boundaries.

- Review the applicable domain rules and express critical cross-component rules as invariants over complete
  outputs.
- Add meaningful tests for new or changed behavior. Every confirmed production defect gets a regression test
  that reproduces the observed failure and verifies the requirement-based expectation.
- Derive expected values independently of the implementation under test: documented rules, independently
  calculated values or stable reference fixtures.
- Verify integration boundaries where correctness depends on their interaction; unit tests alone do not
  establish an end-to-end workflow.
- Keep stable external-system fixtures separate from opt-in live checks.
- Run the relevant focused tests, inspect affected-area coverage where practical and report unresolved
  testing or coverage gaps.

### 26.2 Confirmed defect evidence

Confirmed defects are reported through the GitHub bug issue form, which owns the observed behavior,
reproduction steps, inputs, severity and evidence. A fix keeps the observed failure as a named regression
test (§17, §26.1) whose fixtures live with the application tests, are compact and secret-free and record
provenance when derived from an external system. A known defect is not closed until that test passes unless
an approved exception is documented on the issue. No per-defect Markdown records are created.

## 28. Test Selection

Surefire selects `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java` and
`**/*Properties.java` (`backend/pom.xml`); there is no explicit exclusion element. `*IT` classes (for example
`Gw2ApiLiveSmokeIT`) match none of those patterns, so they stay outside the default run and need an explicit
invocation. Current coverage comes from successful CI at an identified revision; open findings belong in
[Known problems](KNOWN_PROBLEMS.md), and test counts are no evidence for the current commit.

## 29. Tooling Decisions

JUnit Jupiter (managed by the Spring Boot BOM, run by `maven-surefire-plugin`) is the backend test framework.
Tests use hand-written fakes and stubs where needed; jqwik covers properties (§7). Real PostgreSQL tests use
uniquely named isolated schemas on a supplied server: the CI `postgres:17` service container (§36.3) or the
developer's own server locally. No Testcontainers requirement is selected; a test-managed container remains
a candidate for making both environments identical. New tooling needs a concrete need, documented reasoning
and review, and favors simplicity, fast execution and minimal configuration.

## 31. Persistence & External-API Test Layers

Four layers combine the domain, persistence and external-API methods (§4.1, §9, §10). Select the layers that
establish the behavior the change affects.

### 31.1 Layer 1 — Unit / Domain Tests

Defined at §4.1: deterministic, no network, no real database, small hand-built fixtures (§15), part of the
fast `./mvnw test` suite.

### 31.2 Layer 2 — PostgreSQL Integration Tests

Extends §9. A test in this layer must:

- run against a real PostgreSQL engine, never an in-memory or mocked substitute,
- use an isolated, disposable test database, never the normal development database or its content,
- apply the real schema and migrations that production code runs against,
- exercise actual SQL behavior: constraints, transactions, upserts, NULL semantics, stale-row deletion and
  repository-to-model mappings,
- recreate its test data from scratch, with no dependency on data left by a previous run.

The required property is "isolated real PostgreSQL"; the mechanism (uniquely named schemas on a supplied
server) and the open container question are in §29. Controlled synthetic payloads establish application and
SQL behavior, not a captured ArenaNet response contract.

### 31.3 Layer 3 — GW2 API Contract/Fixture Tests

Extends §10. A test in this layer must use captured real GW2 API JSON as test resources (not hand-typed
approximations that may drift), exercise parser and sync behavior against those payloads, stay deterministic
and offline in the normal `./mvnw test` suite, and keep fixtures as small as practical while preserving the
relevant real structure (trim an irrelevant field or array, never write a payload shape from memory).

### 31.4 Layer 4 — Optional Live GW2 API Smoke Tests

- Covers a small number of critical endpoints only.
- Is not part of the normal run: the `*IT` naming matches no Surefire include pattern (§28).
- May fail from external network or API unavailability without making the normal suite flaky.
- Is never the only coverage for parser or sync behavior; Layer 3 remains the authoritative always-run
  coverage.

`ApiCompatibilityLiveIT` runs the production owner API compatibility checker against the real public
endpoints with no account key or database. It is outside the default selection, but CI runs it as its own
bounded step ("Live public GW2 API compatibility gate", six-minute timeout) in the required backend job. It
prints every probe and fails unless the completed overall result is OK with all seven probes, so a mismatch,
an unreviewed newer schema or an unavailable probe fails and names the probe; a temporary ArenaNet outage
can therefore block a release. It never hardcodes volatile values. Run it locally with
`./mvnw test -Dtest=ApiCompatibilityLiveIT`. The checker, its schedule and its UI card are covered offline
with fake transports and clocks.

### 31.5 General Principles

- Choose the lowest layer that proves the behavior; this holds for browser tests too: a change confined to
  background logic needs none merely because a UI-adjacent story exists nearby.
- Domain behavior is unit-tested (Layer 1). Persistence behavior that depends on PostgreSQL semantics
  (constraints, upsert conflicts, NULL uniqueness, transactional visibility) is integration-tested against
  real PostgreSQL (Layer 2), not asserted from application code alone. External API parsing is verified with
  captured real responses (Layer 3).
- Do not test implementation details (private helper structure, incidental call counts) when observable
  behavior gives the same confidence. Regression tests accompany bug fixes where practical (§17).
- Tests are repeatable and independent: no test depends on another's side effects or on execution order, and
  none depends on the developer's local database state, content or credentials (this extends §9).
- New test tooling is allowed when it is the practical way to satisfy a layer; the change that introduces it
  documents the choice and keeps local and CI reproducibility in mind (§29).

## 33. Test Effort Proportionality

Layered coverage is the goal, not a checklist every change must exhaust. Test effort stays proportional to the
size and risk of the change. For a source or documentation audit, verification supplies evidence for the
finding and does not automatically require broad suites or new tests.

- A change confined to background logic (domain, application service, repository, sync, parser) needs no UI or
  browser test; add one only when the change alters UI-observable behavior (a view, a controller's wiring, a
  displayed value, an interactive control).
- A change that alters UI-observable behavior does not need re-verification at every other layer; browser tests
  check that the UI reflects a result, not that the result is right (§12).
- Prefer one clear, well-named test over several overlapping ones asserting the same fact (§16, §22, §23).
- Reach for heavier layers (Layer 2, Layer 4) only when the change's own risk needs their guarantee (real SQL
  semantics, real external API shape, real UI wiring).
- This relaxes no minimum: domain behavior still needs appropriate automated verification and confirmed
  defects a named regression test (§16).

## 34. Scoped performance evidence

The owner ended the pre-release capacity and resource programme. Ordinary maintenance requires no VM, load test,
extra real-user keys, broad benchmark matrix or owner-database run. Existing cached-page responsiveness and
bounded-recovery requirements remain in [Architecture](ARCHITECTURE.md); removing the programme does not
establish measured compliance with them.

When a separately released issue addresses a concrete performance problem, choose a targeted before/after check
that can answer its question. Record source revision, environment, scope and settings, data scale, cache state,
individual observations and limitations, and verify that economic results, ordering, ownership and quantity
limits stay equivalent. Use isolated fixtures for tests that write data and never repurpose the owner's or
production database.

Distinguish server time, initial complete usable page, later refreshed replacement and resource use; a first row
or loading state is not a complete page. A unit-test duration, synthetic fixture or backend-only timer cannot
establish a real-user browser budget or supported-user capacity. Include cold and repeat behavior when the claim
depends on them and report unavailable evidence without inventing a result. Keep account-derived comparisons
private; a live-user check needs its own explicit scope and is no prerequisite for coverage work or documentation
cleanup.

## 35. Layer 6 — Backend HTTP boundary (Spring Boot)

§11 owns what to test at an HTTP contract; this section owns the methodology in this repository. API tests verify
transport behavior and are not where crafting mathematics is tested. Two points apply to every route: a route's
own defaults are part of its contract (assert each route's own values and say why they differ from a comparable
route), and when a boundary change is shared, re-run the existing routes' real-database HTTP parity checks
(§35.2), not only their contract tests, because only the comparison with the real scoped calculation proves the
values.

### 35.1 Contract tests (default `./mvnw test` run)

- Build the controller with Spring's standalone MockMvc setup
  (`MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(...)`), not a full context: real routing,
  argument resolution, message conversion and exception mapping without a server, context or database.
- Substitute collaborators through the controller's production constructor seams with hand-written recording
  stubs, matching the fake-adapter style of the application tests (§8, §31.2). Keep the real scoped signature.
- Cover per §11: a valid request including defaults, each validation rule, applicable missing resources and
  mapped failure statuses. A rejected request starts no calculation; for a factory-backed route also assert
  validation never invoked the factory.
- Assert on the serialized JSON (`jsonPath`), not on a deserialized copy of the response record: field names are
  contract, and round-tripping through the same record cannot detect a rename.
- Where a boundary introduces per-request state, issue successive and concurrent requests whose stubbed results
  derive from each request's own input and assert each response carries only its own result; a fixed canned
  response cannot expose leakage.
- One `@SpringBootTest` context-startup test belongs in the default goal as evidence that the entry point boots
  and binds a port; keep it away from the database.

Account access has a database-free MVC fixture, `AccountAccessHttpFixture` (`backend/src/test/java/web/access/`):
it imports the production guard registration and session controller with real access and session services and
controlled proof and time. Tests assert refusal before dispatch, verified scope, grants, origins, cookies and
absolute expiry; existing controller tests still own payload contracts.

### 35.2 Real-database API checks

**Default controlled parity (`*Test`, runs in CI).** `support.fixture.ScopedCalculationHttpParityTest` and
`support.fixture.AccountReadHttpParityTest` boot the real application on a random port over a disposable Flyway
schema with synthetic fixtures for two accounts with overlapping ids, using the real controllers, mappers and
account/session guard with offline proof and upstream ports. Each HTTP payload is compared with the in-process
scoped calculation of the same verified account (the application service, never a production DTO mapper as the
oracle) together with hand-written fixture outcomes, so a result that is wrong on both sides is caught. They
cover row count and order, every authoritative value of a shared row assertion that protects table rows and
`detail.row` alike, both missing-material lists, strictly typed JSON (an absent or null required field never
equals zero), JSON null versus zero on nullable fields, the complete recursive resolution tree (fields, child
order and count), the `RECIPE_NOT_IN_CALCULATION` 404 including a recipe only the other account has, and the
Bank and Materials read models. They assert positive results and a minimum tree depth so the comparisons are not
vacuous. Isolated controller contract tests (§35.1) are stubbed-service transport checks and do not replace
these guarantees.

**Owner-data checks (explicit runs only).** A check against the developer's real database (§34) is scoped
separately, named `*IT` (selected by no default Surefire pattern, §28, §36.2) and never counts as automatic
acceptance. It boots the real application on a random port with no schema override, so it reads that database
exactly as the running application does. What such a run must do:

- **Timing, when the issue requires it (§34):** measure from issuing the request to holding the complete body,
  and report startup versus repeat scope, row count, response size, settings, data scale and environment. A
  backend-only observation does not prove a browser navigation-to-complete-page requirement.
- **Result equivalence:** per settings combination, call the endpoint over HTTP and the same application-service
  method in process with identical inputs, then compare every row field by field (count, order, authoritative
  values, blocked state, both missing-material maps). Both runs are read-only with no synchronization between.
- **Compare a nullable field's JSON null against the source null explicitly.** `asInt()` and `asText()` on a JSON
  null return `0` and `""`, so a naive comparison agrees with a mapping that substituted zero for an absent
  value. Read such fields through a null-returning helper and print how many rows carried a null.
- **Compare a recursive payload recursively, in order, or not at all:** walk domain structure and JSON together,
  comparing each node's fields, child count and child order; a root-only comparison passes while children are
  reordered, merged, dropped or truncated.
- **Choose a measured case by probing, not by assuming it is representative:** where payload size depends on a
  domain decision, run a few plausible inputs, keep the one that produced the larger structure and print how
  many were tried; keep that heuristic outside every assertion.

### 35.3 Asynchronous trigger and task-status routes

For routes whose work outlives the request (any sync or refresh trigger); §35.1 still governs transport
assertions.

- **Control the work with latches, never sleeps.** The substituted service signals a latch on entry and blocks on
  a second latch the test releases, so "the trigger returned while the work was running" is an observation at a
  chosen point, not a race against a guessed duration.
- **Prove non-blocking acceptance by what is true between the latches:** the response has been read, the service
  has been entered and the status route already answers running with no finish time. A 202 alone does not
  distinguish an asynchronous trigger from a fast synchronous one.
- **Wait for a terminal state with a bounded poll that fails loudly** (a deadline plus an `AssertionError` naming
  the task), through the status route where it is under test.
- **Use the real task facility in the trigger's own tests;** the asynchrony, identifier and admission rule are
  the contract and a same-thread fake would prove none of them. The application service stays substituted.
- **Assert exactly-once delegation per accepted task and zero for a rejected one,** and that a rejected request
  admitted no task (visible when the next trigger is accepted rather than refused as running).
- **Assert every documented lifetime property of the facility,** including the retention bound and the answer
  for an unknown id.
- **Assert absences in a failure body:** no successful-completion wording and none of the exception's own text
  (hosts, endpoints, JDBC URLs).
- **Do not require live account mutation to test a task protocol.** Validation, the 404, the 405 and routing can
  be verified against a running server without starting a synchronization; record whether the accepted path was
  exercised live as a limitation.
- **Where triggers share the facility, test the operation key's independence, not only its exclusion:** hold the
  first operation at its latch and submit the other; a 409 on a repeat of the same operation cannot tell "one
  unfinished task per operation" from "one unfinished task". Await the second task's terminal state before
  reading its delegation count.
- **A reused boundary is verified by the reused tests passing unchanged,** not by copying their assertions.
- **Where a trigger selects between use cases, assert the one that must not have run,** with separate counters
  and latches per entry point.
- **Sweep the rejected values of an enumerated request field in one test,** including the plausible but
  unsupported one (a combined "all"), a mistyped case, a blank and a wrong JSON type.
- **Subclassing a real application service to substitute it is acceptable only when its constructor is inert;**
  where a constructor would connect or fetch, use the collaborator seam.

## 36. The CI gate — who runs which tests, and when

This section owns the division of test execution between the local implementation loop and GitHub Actions.
§20 states the local scope rule; this states what the gate is, what it runs, what it cannot run, and what
"verified" therefore means.

### 36.1 Division of responsibility

| Where | Runs | Authoritative for |
|---|---|---|
| Local, during implementation | the narrowest scope covering the change: one test, one class, at most the directly related suite (§20) | that the change under construction behaves as intended |
| GitHub Actions (`.github/workflows/ci.yml`) | complete default suites with coverage, type/build, workflow checks, the controlled browser selection after build, the container smoke and the public API compatibility gate (§31.4) | that required deterministic checks, named synthetic journeys and the bounded live API check pass on the PR head |
| Local, explicitly invoked | live GW2 API smoke (§31.4), real-backend and full-stack browser checks, additional controlled journeys, owner-data `*IT` checks (§34, §35.2) | the explicitly observed scope that default CI does not establish |

CI runs the complete default selection at the identified commit; local checks stay focused on the change. Push,
pull-request and explicit dispatch events can produce separate runs.

### 36.2 What the gate runs

- **Backend:** `./mvnw -B -ntp test jacoco:report` on `ubuntu-latest` against a `postgres:17` service container.
  Surefire's patterns (§28) do not match `*IT`, so those stay out of the default run exactly as locally. A
  separate required step runs `./mvnw -B -ntp test -Dtest=ApiCompatibilityLiveIT -Djacoco.skip=true` against the
  public ArenaNet endpoints (§31.4); a missing selected test fails the step. The same run executes Checkstyle,
  Error Prone and the ArchUnit rules.
- **Frontend:** `npm ci`, `npm run lint`, Vitest coverage with
  `npm run test:coverage -- --reporter=default --reporter=github-actions`, the type-checking `npm run build`, then
  the locked Chromium installation and `npm run smoke:ci` (§12.5) as a separately named step of the same required
  job. Its failure fails the job; journey outcomes stay separate from coverage percentages.
- **Workflow checks:** actionlint validates the workflows and `python -m unittest discover -s .github/scripts` runs
  the helper and configuration tests, with native Nginx where required.
- **Container smoke:** `scripts/compose-smoke.sh` builds both images as Compose does, starts the stack and checks
  health, boundaries and a restart on the managed schema.
- **Coverage KPI report:** summarizes the product reports.

### 36.3 Required services and configuration

- **PostgreSQL:** a `postgres:17` service container with an empty database. It satisfies §31.2 better than a
  developer machine: isolated and disposable by construction, and each Layer 2 test creates and drops its own
  uniquely named schema. Nothing is pre-provisioned.
- **`GW2_API_KEY`:** not read by the backend (browser account operations supply a transient key); the workflow may
  carry a literal placeholder. No default test makes a live GW2 call (§14, §31.4). A real key never appears in
  the workflow, a log or a CI repository variable; a test that genuinely needs one belongs in Layer 4, outside
  this gate.
- **Database credentials** are the service container's own, reachable only from that job; they are configuration,
  not secrets. The gate requires no application secret.
- **Controlled browser runtime:** the locked `playwright-core` CLI installs its Chromium and Linux dependencies and
  `GW2_BROWSER_PATH` names the executable. The journey process owns its origins, needs no backend or database and
  blocks real upstream traffic (§12.5).

### 36.4 What the gate deliberately cannot verify

- Additional Layer 4 live smoke (§31.4) stays outside; the seven-probe compatibility check is the named exception,
  not full API coverage.
- Owner-data `*IT` checks and performance measurements (§34, §35.2) need a populated real database and stay local
  and explicit. Controlled PostgreSQL HTTP parity (§35.2) is not in this category: it runs in the default suite.
- Real-backend and full-stack browser checks (§12.2) need prepared application and data environments. Installing
  Chromium does not make a controlled fixture equivalent to a live backend, database or deployment.

A green gate means the default deterministic suites, controlled journeys, container smoke and the named public
API check pass, not that every layer or live integration passed. Browser evidence is limited to the curated
scenarios and the supported desktop boundary. A local browser pass or an older green run cannot replace required
checks on the current PR head.

### 36.5 What "verified" means for a story

The PR head must have green required CI checks; a red or missing check holds the PR and failures are fixed on the
same branch so CI reruns on the new head. Local-only checks required by the issue need recorded evidence; their
absence cannot be inferred from green CI. Review and merge rules are in the
[development workflow](../agent/DEVELOPMENT_WORKFLOW.md). A story's Definition of Done does not demand a full
local regression run: it names the tests that must exist and pass, and the gate proves the rest still passes.
