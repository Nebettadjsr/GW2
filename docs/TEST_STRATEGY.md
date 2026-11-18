# GW2 Tool — Test Strategy

## GitHub issue and pull-request verification

Existing coverage can be reused; a low-risk change may need no new test when the PR states
why. Explain changed or weakened assertions with evidence. CI runs the default suites;
a green suite alone does not establish that the required behavior is correct. Agent roles
and delivery rules are defined in [AGENTS.md](../AGENTS.md) and
[Development workflow](../agent/DEVELOPMENT_WORKFLOW.md).

## 1. Purpose

Application failures discovered in review or CI are investigated against the issue and current code. Fix production defects and incorrect tests on the same PR branch, with reasons visible in the diff or PR discussion. CI and review must evaluate the current head commit; an older green result does not verify a newer revision.

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

Test daily crafting independently from inventory use and buying.

### useOwnMats = true

Expected:

```text
use owned daily items first
do not perform a daily craft
buy a shortfall only when buying is enabled
```

### useOwnMats = false

Expected:

```text
ignore owned daily items
allow at most one daily craft operation when enabled
buy any additional requirement only when buying is enabled
```

Also test blocked behavior when the required item cannot be purchased.

Exercise the `useOwnMats` × `allowBuying` × `allowDailyCrafts` combinations, including recursive
daily ingredient → craftable intermediate → parent paths. Assert that a disabled purchase cannot
appear in nested missing-to-buy totals, and that speculative failure restores the daily allowance.

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

- the selected character/discipline is also the sole owned-inventory scope at the application and API boundaries;
- the Discovery contract cannot select another inventory character or specify a max-buy budget;
- one candidate is simulated as one recipe attempt, including output quantities greater than one;
- Discovery controls/details/table show no craft-count or duplicate total/per-attempt figures, while pricing modes, owned materials, buying, filtering/sorting and blocked states remain functional;
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

Critical business rules must also be expressed as invariants over complete calculation results,
not only as assertions inside one resolver, allocator, cost calculator, or shopping-list builder.
For crafting, verify that resolution, stock allocation, purchases, total costs, profitability,
craftable quantity, and the aggregated shopping list agree for the same plan.

Property-based tests use jqwik for Java where generated inputs provide meaningful breadth. They
are particularly appropriate for recursive calculations, inventory allocation, numerical
boundaries, shared dependencies, and combinations of calculation settings. Generated scenarios
must be reproducible: retain jqwik's reported seed in failure evidence and rerun the same
property with that seed before diagnosing a failure. Use deterministic examples alongside
properties for named production regressions and independently calculated expected results.

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

The frontend should focus tests on:

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

## 12.1 How frontend tests are run

Frontend tests live beside the code they cover, under `frontend/src/**/__tests__/`, and run with
`npm test` in `frontend/`. They execute components in jsdom against a hand-written stand-in for the
API client interface; the global `fetch` is not stubbed, and no test starts a backend or a database.

Five rules follow from §12 and are worth stating explicitly, because the failure they prevent is
silent:

1. **A stand-in must answer like the contract it stands in for.** The Profit route echoes back the
   scope and settings it calculated with, and the screen takes its control state from that echo. A
   fake that ignored the request and always returned fixed settings would make the screen look
   correct while the real backend made it wrong — so the stand-in reproduces the echo.
2. **Fixtures must be able to expose a derived value.** Where a test checks that a
   backend-supplied figure is displayed or sorted as supplied, the fixture's supplied total is
   deliberately *not* the product of its own parts, so a frontend that recomputed it would fail the
   test instead of agreeing with it by coincidence.
3. **A test must be able to reach the interaction it claims to cover.** A control disabled while a
   request is in flight cannot receive the event a test sends it, and the test then passes against
   an interaction that never happened. Assert the effect (a further request was sent, the rows
   changed), not only the absence of an error.
4. **A recursive payload is compared by walking it, not by spot-checking a node.** Where the browser
   renders a supplied tree, the check flattens the *response* in its own child order and compares the
   whole sequence against what was rendered, so a dropped, reordered, merged or silently truncated
   occurrence fails. The expectation is the supplied structure itself; nothing about it is recomputed
   in the test, and a repeated item is expected to appear as many times as it was sent.
5. **A late or superseded answer needs its own test, with the promise resolved by hand.** Request
   isolation is a property of which answers are *refused*, so a fixture resolves a first request only
   after a second has started, and asserts that the first cannot reach the screen — including the
   A → B → A case, where the stale answer carries the identity that is once again selected.

## 12.2 Frontend verification levels

| Level | Command | Establishes | Does **not** establish |
|---|---|---|---|
| Type check | `npm run type-check` | strict typing across `.ts` and `.vue` | that received JSON matches those types |
| Component/unit | `npm test` | rendering, interaction, request construction, state transitions | that the real backend connection works |
| Production build | `npm run build` | the locked dependency set builds to static assets | runtime behavior |
| Automatic controlled browser journeys (STORY-COV-006) | `npm run smoke:ci` | a real browser drives the built app through public reading, connection, failure/recovery, account switching, narrow-screen notice/scrolling and reservations against an owned synthetic API | real backend/SQL/upstream behavior, production performance or additional Vitest/JaCoCo line coverage |
| Explicit real-backend browser smoke | `npm run smoke:browser`, `npm run smoke:account` | a real browser retrieves and renders real backend results, compared against the very response it received | performance (§34), any state the live data did not contain, and whether a compared value is domain-correct |
| Browser smoke, controlled boundary | `npm run smoke:sync` | a real browser drives controls whose live counterpart would mutate data, and presents the answers | that a real operation ran, or anything about the backend's own behavior |
| Focused controlled layout and accessibility | `npm run smoke:layout`, `npm run smoke:profit` | supported desktop layout, increased text size, keyboard focus and contrast, URLs/titles, result/detail placement, selection and sticky positioning where the script explicitly measures them | automatic acceptance of legacy phone-tool scenarios, unvisited combinations, real data or performance |
| Browser asynchronous lifecycle, controlled boundary | `npm run smoke:profit:refresh` | a real browser rendering a scripted sequence of cached, replaced and failed refresh answers: the indicator's measured border and cycling animation, preserved controls and selection across an automatic replacement, a detail answered from newer inputs than its row, and termination with a retry | that the backend produces any of those sequences, and anything about refresh timing or a recovery budget measured on a real clock |
| Backend contract connection | `npm run check:profit:envelope` | that the shape a controlled-boundary check imitates is the shape the running backend sends, read out of the response the browser itself received, with the page's state asserted against it | row economics, timing, and any sequence the backend did not happen to produce in that visit |

The explicit real-backend smoke commands need their backend and frontend already running.
They assert against actual received responses; controlled-browser journeys cannot establish
that integration. The automatic `smoke:ci` selection instead serves built assets and synthetic
API responses in owned loopback origins: it starts no application backend or database. Neither
kind establishes the performance claims in §34. Record the environment and measurement scope
without exposing credentials.

A special state that the live database does not currently produce (for example an unavailable
price) is covered at the component level with a controlled response, and the Result says so rather
than implying the browser run exercised it.

A check that serves the production build itself must establish that the build it serves is the
current source, not merely that a build exists: it compares the emitted document against every
file the build reads and refuses to start — naming the file and both timestamps — when any input is
newer. Requiring the build rather than performing it keeps the check a report on what the developer
has. Asserting only that the output directory is present turns any additive source change into
browser evidence for source the browser never loaded.

### Fresh-runtime prerequisite after substantial changes

After substantial integration changes, a real-backend observation needs identified current
frontend/backend code. Use isolated owned processes and ports, rebuild/restart only those
processes when needed, and preserve the ordinary user application. A browser refresh alone
does not update an old Java process. Controlled journeys build the current frontend first and
start their own static/API origins; no Java restart is needed for them. Record revision and
runtime limits, and never claim evidence for current source from an unidentified older process.

## 12.3 Checking controls that would mutate data

A read-only screen can be smoke-checked against the live backend; a control that starts a
synchronization cannot, because running it to see the button work would spend the GW2 API budget
and write to the user's database. Three rules follow:

1. **Point the real browser at a controlled origin instead of dropping the level.** The check serves
   the built assets and answers the trigger and status routes itself, with scripted task lifecycles
   — so the browser, the click handling, the polling and the rendering are all real while nothing
   is synchronized. It establishes interaction and presentation only; it says nothing about the
   backend, which is covered by that route's own backend tests.
2. **Never imply the operation ran.** A passing controlled-boundary run is recorded as what it is,
   and a live synchronization is claimed only when one was actually observed.
3. **Prove the browser is on the controlled origin before driving any control.** A successful bind
   is not that proof: an address the check did not take (an IPv6-only dev server on the same port)
   can still answer the name `localhost` and proxy to the real backend, turning a "nothing real was
   touched" check into a live synchronization. Bind and navigate to `127.0.0.1` explicitly, fail on
   a `listen` error rather than ignoring it, and assert the check's own server served the page.

Unit/component polling tests use fake timers and hand-resolved promises to verify request
counts, serialization and termination. Browser journeys use bounded event/DOM waits against
controlled responses; avoid arbitrary sleeps and do not infer a real performance budget from
their execution time.

## 12.4 Checking presentation and accessibility

Layout, focus and contrast claims are *measured in a real browser*, never inferred from the stylesheet.
Apply the [supported-tool viewport policy](DOMAIN_SPEC.md#supported-browser-viewport): verify the notice at
390/1023 CSS pixels, plus normal tool content and measurable horizontal scrolling. At 1024
and wider, tools retain the desktop layout without the notice. These are warning/canvas checks,
not a requirement to optimize phone layouts. Home, guides and legal reading pages remain exempt and may be checked at narrow
widths. Keep desktop keyboard/focus, short-height and zoom/text-enlargement assertions; zoom
that takes the effective CSS viewport below the threshold should produce the notice.
Six rules keep such a check honest:

1. **Measure the rendered combination, not the declared value.** Contrast is computed from the
   `getComputedStyle` foreground and the nearest opaque background of elements that are actually on the
   page, with the WCAG large-text threshold applied by the measured font size and weight. A palette
   table establishes nothing on its own. A tone the current page does not happen to show is measured by
   applying the shared class to a probe element, so it is still the real treatment being measured.
2. **Reflow and zoom are numbers.** Horizontal overflow is `documentElement.scrollWidth` against
   `clientWidth` at each checked viewport width; increased text size is the root font size raised and
   the same comparison repeated. "It looked fine" is not a result, and a screenshot is not a
   measurement.
3. **Start each observation from a fresh load.** A navigation that only changes the URL fragment is a
   same-document navigation: the previous page's focus, scroll position and component state survive it,
   which silently invalidates a focus-order or first-load assertion. Reload before observing, and assert
   the focus order from an actual sequence of Tab presses rather than from the DOM order.
4. **Record what was not established.** An automated pass over the combinations a script happened to
   visit is not conformance; the implementing story's Result names the widths, the interactions and the
   states that were checked, and the accessibility questions that remain open.
5. **Set up the condition a positional claim needs, or the measurement proves nothing.** A
   `position: sticky` element can only travel inside its own containing block, so a panel beside a
   *shorter* column has nowhere to stick and scrolls away with the page exactly as a non-sticky one
   would. Check pinning at a viewport where the sibling column is measurably the taller one, assert
   the offset the stylesheet declares, and assert separately that the element had left its flow
   position — otherwise the check either fails against correct behavior or passes against none.
   Component-scoped styles cannot be measured on a probe element, because the scope attribute is what
   selects them; sample a real instance that the page is actually showing.
6. **Name what overflowed.** A failing reflow assertion that reports only a width difference sends
   the next reader guessing. Collect the elements whose right edge passes the viewport and report
   them with the failure — a single unbreakable label is a common cause, and it is invisible in a
   scroll-width number alone.

## 12.5 Automatic desktop user journeys

[Issue #243, STORY-COV-006](https://github.com/Nebettadjsr/GW2/issues/243) implements the
required controlled-browser selection. Current-head CI and required owner visual acceptance
remain separate from a local pass. From `frontend/`, after `npm ci` and `npm run build`:

```bash
node node_modules/playwright-core/cli.js install --with-deps --no-shell chromium
export GW2_BROWSER_PATH="$(node --input-type=module -e "import { chromium } from 'playwright-core'; process.stdout.write(chromium.executablePath())")"
npm run smoke:ci
```

The CLI comes from the locked `playwright-core` package. Do not use an unpinned `npx` package
download or add a second test framework. CI exports `GW2_BROWSER_PATH` in the same shell step
that runs `npm run smoke:ci`. Windows may use installed Chrome/Edge through `resolveBrowserPath`,
or install the matching managed Chromium without Linux system-dependency setup:

```powershell
node node_modules/playwright-core/cli.js install --no-shell chromium
$env:GW2_BROWSER_PATH = node --input-type=module -e "import { chromium } from 'playwright-core'; process.stdout.write(chromium.executablePath())"
npm run smoke:ci
```

`smoke:ci` invokes `node scripts/run-desktop-journeys.mjs`. It runs the curated current journeys
sequentially, including the existing reservation journey. It is not a blanket invocation of
every historical `smoke:*` script. The runner uses the built app, an explicitly owned
`127.0.0.1` origin, synthetic account proof/data, strict API method/route/context checks and
blocked real upstream traffic. Occupied ports, stale/incomplete bundles, missing browsers,
unexpected requests and failed assertions are failures, not skipped success. Public document
build inputs/output must be current too. No real API key, application backend or database is
required. Fixtures provide backend results; browser checks do not reimplement economic rules.

Viewport regressions include an unfinished Profit answer held across narrowing and released
while narrow: it must render without a second calculation. Ecto quantity, salvage/tool
and price modes survive width-only changes without refetching, while actual navigation retains
its normal reset/refetch. Initial/reached narrow tools restore/load normally with visible content;
width is not an access or account-work gate. Test actual horizontal scroll movement and reaching
the desktop canvas, rather than only declared CSS width.
Real-Chromium regressions open Help and Delete confirmation before narrowing: their normal modal
interaction remains available, then explicit Close/Escape permits navigation. Width alone must
not close/reopen them, clear the form draft/key or trigger refetch. Never confirm deletion.
The 18 November owner decision explicitly replaces earlier blocking, no-request-at-narrow and
forced-modal-close assertions. Preserve the unaffected account/desktop user-story regressions.

The runner bounds each of its two sequential child processes to 180 seconds, with a further
10-second owned-resource teardown allowance and no retries. CI bounds the browser run step to
eight minutes. Close owned pages, contexts, browser processes and stub servers on success and
failure, including launch failure. Do not attach to the ordinary user application or kill a
process merely because it owns an expected test port.

Results go to `frontend/quality-reports/desktop-journeys/` by default, overridable with
`GW2_SMOKE_OUTPUT`. Record named journey/step outcomes, the revision/build/browser scope,
and bounded failure details. Capture relevant screenshots/traces before closing failed pages.
Use synthetic inputs only; avoid secrets and unbounded body/log dumps. CI uploads journey
results and available failure artifacts as `desktop-browser-journeys` with `if: always()` and
seven-day retention. An absent or incomplete result
must not be interpreted as a passing journey. Screenshot/trace evidence does not replace the
owner's visual acceptance for a changed UI layout.

Browser outcome counts are separate from the Vitest/JaCoCo percentages in §26.
A controlled pass establishes the named journeys and
viewport combinations, not exhaustive end-to-end coverage or a production latency claim.

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

# 19. Boundary Refactoring

When refactoring an application or transport boundary:

- keep domain tests independent of UI technology,
- preserve existing validated calculation behavior,
- add backend API tests when a changed endpoint needs them,
- add frontend tests only for presentation/interaction,
- do not rewrite domain tests just because the UI changes.

The domain suite should survive changes to presentation and transport technology.

---

# 20. Test Execution Scope

Local execution and full-regression execution are two different jobs with two different owners (§36).

**Locally, Claude runs the smallest scope that actually proves the change it just made**, and stops there:

```text
1. exact affected test
2. affected test class/module
3. related domain suite (only when the change genuinely spans it)
```

There is no fourth step. Do not run the complete backend, frontend or harness suite locally, and never re-run a broad suite repeatedly while iterating: **GitHub CI owns the full regression run** against the pushed commit, and it is the authoritative verdict for it (§36). A local full-suite run costs a model session minutes of output for information the gate produces anyway.

Two exceptions, where a local broad run is the cheaper answer:

- a change whose blast radius is genuinely unbounded (a shared fixture, a build/dependency change, a rename across packages) — run the affected suite locally before pushing rather than discovering it three CI cycles later;
- a human explicitly asks for it.

"An appropriately broad regression set before declaring a change complete" is therefore satisfied by the CI gate, not by a local run. Story completion is not final until that gate is green (§36).

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

# 24. Regression Priorities

Preserve the existing crafting engine's core behavior when selecting regression checks.

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

# 25. Testing Goal

Coverage does not replace meaningful regression confidence.

The continuing goal is:

> The central crafting calculation can be changed without relying on manual testing to discover unrelated breakage.

A smaller set of high-value tests is preferable to a large number of shallow tests.

---

# 26. Coverage KPI and Reports

Test coverage is a permanent software-quality KPI for the Java backend and TypeScript frontend.
The long-term target is at least **90% line and branch coverage**,
maintained as consistently as possible. Coverage below target is a warning and does not fail
the build.

Coverage is measured by the test jobs in GitHub Actions and summarized by the `Coverage KPI
report` job. The job summary contains overall and per-module/package line and branch rates; the
`coverage-reports` artifact contains the HTML reports and machine-readable inputs/results.
Missing measurements are labeled unavailable and are never treated as zero. Combined rates are
weighted over measured executable lines/branches and are only a project-wide tracking aid; each
module/package must also be reviewed so one well-covered area cannot hide a critical gap.

Developers and AI agents can generate reports locally:

- Java: `./mvnw test jacoco:report` (Windows: `mvnw.cmd test jacoco:report`); HTML at
  `backend/target/site/jacoco/index.html`, XML at `backend/target/site/jacoco/jacoco.xml` (Maven runs in `backend/`).
- Frontend: `cd frontend && npm ci && npm run test:coverage`; HTML at
  `frontend/coverage/index.html`, machine-readable summary at
  `frontend/coverage/coverage-summary.json`.
- Combined local KPI: `python .github/scripts/publish_coverage_kpi.py`; this writes
  `quality-reports/coverage-kpi.md` and `quality-reports/coverage-kpi.json`.

Use the identified CI revision and coverage artifact as the baseline. Distinguish measured
results, estimates and unavailable values. Refresh it when measurement scope changes;
record comparisons in the issue/PR rather than another tracked coverage ledger.

New or changed behavior should aim to maintain at least 90% coverage in affected areas, with
special attention to business-critical branches and edge cases. Coverage is a minimum quality
indicator, not proof of correctness; meaningful assertions and behavioral verification take
precedence over percentage growth. Never add artificial tests, exclude legitimate production
code, or otherwise manipulate the measurement to increase a percentage.

Generated code may be excluded only when the generator and exclusion are documented. Build
scripts, deployment configuration, static assets, and external-system code that cannot
reasonably be isolated for unit testing may be recorded as unavailable or excluded with a
specific rationale. Business logic, adapters, and error paths must not be excluded merely
because they are difficult to test. Java line and branch coverage, frontend line and branch
coverage are reported where each tool supports them. The retired Python harness is outside the current application coverage scope; workflow helpers have their own deterministic tests.

Do not create meaningless tests solely to increase coverage percentage.

## 26.1 Test quality rules for implementation work

The implementing agent owns test design and authorship for its issue. The reviewer checks
whether the tests establish the acceptance behavior, especially at integration boundaries.

- Review the applicable domain rules and express critical cross-component business rules as
  invariants over complete outputs.
- Add meaningful tests for new or changed behavior. Every confirmed production defect requires
  a regression test that reproduces the observed failure and verifies the requirement-based
  expected behavior.
- Derive expected values independently from the implementation under test, using documented
  rules, independently calculated values, or stable reference fixtures where appropriate.
- Verify relevant integration boundaries when correctness depends on their interaction;
  isolated unit tests alone do not establish an end-to-end workflow.
- Keep stable external-system fixtures separate from opt-in live integration checks.
- Run the relevant automated tests and builds, inspect affected-area and package coverage, and
  report any unresolved testing/coverage gap. A known defect is not closed until its regression
  test passes unless a documented exception is approved and linked from the bug record.

## 26.2 Permanent bug registry

Confirmed defects are recorded under `docs/bugs/` using the report template and lifecycle in
that directory's README. Reproduction data that must remain version-controlled belongs under
`docs/bugs/fixtures/` or an application test fixture directory referenced by the bug record.
Do not create retrospective records for historical defects as part of unrelated work.

---

# 27. Implementing Agent Testing Rules

For tasks involving behavior changes, the implementing agent must:

1. read `docs/DOMAIN_SPEC.md`,
2. read this document,
3. read the issue's acceptance criteria and relevant tests,
4. implement production behavior and meaningful tests together,
5. run focused checks (§20), leaving the complete regression suite to CI,
6. report exact commands, real results, and any unresolved verification gap in the PR.

For pure UI styling or documentation-only changes, domain test execution is not automatically required.

The implementing agent must not:

- delete failing tests merely to make a build green,
- weaken assertions without justification,
- introduce live external API dependencies into normal tests,
- rely solely on manual verification for domain behavior,
- run the complete regression suite locally as routine verification — that is the CI gate's job (§20, §36),
- treat a green targeted run as proof that nothing else broke; the gate decides that.

A CI failure is investigated on the current PR branch. The implementing agent fixes production defects and maintains incorrect tests or fixtures with an evidence-backed explanation. Missing product intent still requires a human decision. Required CI checks must pass on the PR head; deleting, skipping, or weakening a test merely to make CI green is not an acceptable fix.

---

# 28. Current-State Reality

The Java 25 backend uses Maven, JUnit Jupiter and Surefire, with JaCoCo reports. The Vue/TypeScript frontend uses Vitest, jsdom, type checking and the production Vite build. Domain tests exercise independent crafting types; repository and HTTP tests supply their required real PostgreSQL or fake upstream boundaries.

Current coverage comes from successful CI at an identified revision and artifact. Open
structural or behavior findings belong in [Known problems](KNOWN_PROBLEMS.md). Neither
test counts nor old harness state are evidence for the current commit.

Keep new tooling proportional to the check it enables. Do not introduce a testing platform solely to reproduce a retired migration or measurement programme.

---

# 29. Tooling Decisions

JUnit Jupiter (`org.junit.jupiter:junit-jupiter`), managed by the Spring Boot BOM and run via `maven-surefire-plugin`, is the backend test framework.

Current tests use hand-written fakes and stubs where needed. Real PostgreSQL tests use isolated schemas on a supplied PostgreSQL server, including the CI service container. No Testcontainers requirement is selected.

The CI gate (§36) does not resolve the Testcontainers question: it supplies an
isolated, disposable PostgreSQL *service container* to the pipeline, which is how
§31.2's property is satisfied there, while a local run still uses the developer's
own server. A test-managed container remains a candidate for making the two
environments identical.

Future tooling remains a scoped implementation choice requiring a concrete need and review; it is not a prerequisite for the current suite.

The selected tooling should favor:

- simplicity,
- fast execution,
- good IDE support,
- good Claude Code usability,
- minimal configuration overhead.

---

# 30. Status

This document defines the maintained testing strategy for the GW2 Tool.

Its primary role is to prevent the previous failure mode where:

```text
fix bug A
→ accidentally break B
→ fix B
→ accidentally break C
```

Prioritize uncovered active behavior and regression risks in the existing application. The desktop-to-web migration is complete; retired phase gates do not define today's test queue.

---

# 31. Persistence & External-API Test Layers

These four layers combine the domain, persistence and external-API methods in §4.1, §9
and §10. Select the layers that establish the behavior affected by the change.

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

`MaterialCategoryPublicationTest` exercises the active public-array fetch-to-publication
flow on production Flyway schemas: literal ordered replacement/scoped readback, unchanged
account rows/markers, real insertion/cleanup faults, rollback and explicit retry.
`GlobalDataRefreshServiceTest` covers early category-step failure status and recovery.
These controlled synthetic payloads establish application/SQL behavior, not a captured
ArenaNet response contract. No live API or owner database is required. The injected
`PublicArrayFetcher` overload reuses the production transaction rather than copying it.

`AccountItemMetadataRepairTest` uses the existing injected metadata source with production
Flyway schemas. It checks incomplete/unknown facts, warm and retry selection, a real late
flag-update fault in both connection modes, per-batch commit/rollback, continuation after
ordinary failure and cancellation before later batches. Separate-connection readback checks
committed facts and unchanged account rows/markers; interrupt state is cleared before cleanup.
`ScopedFetchOwnershipTest` checks that ordinary metadata outage still permits valid Bank
publication, interruption preserves the previous generation, and an explicit retry recovers
without changing the other account. The 201/401-ID fixtures cross fixed batch boundaries;
they are bounded behavior cases, not load measurements. No production change or live call
is required by this test-only slice (#247).

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

- Choose the lowest layer that proves the behavior being tested; do not reach for an integration or live test when a unit test already proves the rule. This applies to browser tests too: a change confined to background/non-UI logic never needs one merely because a UI-adjacent story exists nearby.
- Domain behavior remains primarily unit-tested (§4.1, Layer 1); persistence behavior that depends on PostgreSQL-specific semantics (constraint enforcement, upsert conflict behavior, NULL-uniqueness quirks, transactional visibility) must be integration-tested against real PostgreSQL (Layer 2), not asserted from application code alone.
- External API parsing should primarily be verified using captured real responses (Layer 3), not hand-typed minimal JSON.
- Avoid testing implementation details (private helper structure, incidental call counts) when the same confidence can come from testing observable behavior.
- Regression tests should accompany bug fixes where practical (§17).
- Tests must be repeatable and independent: one test's outcome must never depend on another test's side effects or execution order.
- No test may depend on the developer's normal local database state, content, or credentials — this extends §9's rule for repository tests to the whole suite.
- Introducing new test tooling (e.g. Testcontainers, a fixture-generation library) is allowed when it is the practical way to satisfy a layer's requirements above, but the story that introduces it must document the choice and its reasoning, and must keep both local-developer and CI reproducibility in mind. §29 tracks which tooling decisions remain open; this section does not resolve them ahead of that decision.

---

# 32. Browser UI verification

UI-observable behavior is verified by Vitest component/unit tests, the type-checking build and focused real-browser checks (§12). Backend behavior behind the UI is covered by Layers 1–4 and the Spring Boot HTTP boundary (§35). There is no desktop UI or TestFX verification requirement. The section number is retained for existing references.

---

# 33. Test Effort Proportionality

For a source or documentation audit, verification supplies evidence for the specific finding; it does not automatically require broad suites or new tests. Implementation changes follow the rules below.

Broad, layered coverage (§3's pyramid, Layers 1–4 and 6) is the project's goal, not a checklist every change must exhaust. Test effort — which layers are touched, how many tests are written, how heavy each one is — must stay proportional to the size and risk of the actual change, never padded out for thoroughness alone.

Concretely:

- A change confined to background/non-UI logic (domain, application-service, repository, sync, parser code) does not require browser-layer coverage, or any other UI-facing test, merely because it happens to sit near a UI-adjacent story or feature. Add a UI-layer test only when the change itself alters UI-observable behavior: a view, a controller's wiring to the domain/application layer, a displayed value, or an interactive control.
- Conversely, a change that does alter UI-observable behavior does not need it re-verified at every other layer beyond what already covers the underlying calculation — Browser tests exist to check that the UI correctly reflects a result, not to re-prove the result itself (§12 already establishes this for the frontend generally).
- §31.5's "choose the lowest layer that proves the behavior" already governs which layer to use; this section governs how much test volume is appropriate once that layer is chosen. Prefer one clear, well-named test (§16, §22, §23) over several overlapping ones asserting the same fact.
- Heavier layers (Layer 2 PostgreSQL integration, Layer 4 live smoke) cost real setup/runtime/review effort disproportionate to most changes; reach for them only when the change's own risk genuinely requires that layer's specific guarantee (real SQL semantics, real external API shape, real UI wiring), not as a default upgrade from a cheaper layer that already proves the point.
- This does not relax any existing minimum: the implementing agent provides appropriate automated verification for domain behavior and named regression tests for confirmed defects (§16). Keep test volume proportional to risk.

---

# 34. Scoped performance evidence

The owner ended the pre-release capacity/resource programme. Ordinary maintenance does not require a VM, load test, extra real-user keys, a broad benchmark matrix or an owner-database run. Existing cached-page responsiveness and bounded recovery requirements remain in [Architecture](ARCHITECTURE.md); removing the programme does not establish measured compliance with them.

When a separately released issue addresses a concrete performance problem, choose a targeted before/after check that can answer its question. Record source revision, environment, scope/settings, data scale, cache state, individual observations and limitations. Verify that relevant economic results, ordering, ownership and quantity limits remain equivalent. Use isolated fixtures for tests that write data; never repurpose the owner's or production database for setup/teardown.

Distinguish server time, initial complete usable page, later refreshed replacement and resource use. A first row or loading state is not a complete usable page. A unit-test duration, synthetic fixture or backend-only timer cannot establish a real-user browser budget or supported-user capacity. Include cold and repeat behavior when the scoped claim depends on them, and report unavailable evidence without inventing a result.

Keep account-derived comparisons private. A live-user check requires its own explicit scope; it is not an automatic prerequisite for coverage work or documentation cleanup. Historical observations remain in the archive and are not fresh acceptance evidence.

---

# 35. Layer 6 — Backend HTTP boundary (Spring Boot)

`STORY-API-001` established this layer; §11 owns *what* to test at an HTTP contract, this section owns the *methodology* for doing it in this repository. The rule from §11 still governs: API tests verify transport behavior and are not where crafting mathematics is tested.

The methodology below applies unchanged to every route added afterwards — `STORY-API-002` reused it for Crafting Discovery without extending it. Two points it already implies are worth stating explicitly, because that second route is what made them visible:

- **A route's own defaults are part of its contract.** Where two routes over comparable use cases open with *different* defaults, assert each route's own values rather than assuming the earlier route's, and say in the test why they differ.
- **When a boundary change is shared, re-run the existing routes' controlled real-database HTTP parity checks (§35.2), not only their contract tests.** A contract test proves the JSON field names survived; only the comparison with the real scoped calculation over database rows proves the values did.

## 35.1 Contract tests (default `./mvnw test` run)

- Build the controller directly with Spring's standalone MockMvc setup (`MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(...)`) rather than a full application context. It exercises the real routing, argument resolution, message conversion and exception-handler mapping without starting a server, a context or a database, so these stay fast enough to belong in the default goal.
- Substitute the application service through the controller's own constructor seam — the `Supplier<CraftingProfitService>` the production wiring also uses — with a hand-written recording stub rather than a mocking framework, matching the fake-adapter style of the application-layer suites (§8, §31.2).
- Cover, per §11: a valid request (including the all-defaults request), each validation rule, the applicable missing-resource behavior, and each mapped failure status. A validation test must additionally assert that **no** service instance was created, so "rejected before any calculation starts" is proven rather than assumed.
- Assert on the serialized JSON (`jsonPath`), not on a deserialized copy of the response record: field names are part of the contract, and round-tripping through the same record cannot detect a rename.
- Where a boundary introduces per-request state, test isolation explicitly: issue successive *and* concurrent requests whose stubbed results are derived from each request's own input, then assert each response carries its own input's result and that no two requests shared a service instance. A stub that returns the same canned value for every call cannot detect leakage.
- One context-startup test (`@SpringBootTest`) belongs in the default goal as evidence that the entry point genuinely boots and binds a port on the actual Java/Maven setup. Keep it from touching the database — with a per-request service factory, simply never invoking it is enough.

Account access has a separate database-free MVC fixture in
[`AccountAccessHttpFixture`](../backend/src/test/java/web/access/AccountAccessHttpFixture.java).
It imports the production guard registration and session controller, uses real access/session
services, and controls proof and time. The recovery and route-mapping tests assert refusal
before dispatch, verified scope, grants, origins, cookies and absolute expiry. Recording
handlers observe guard behavior; existing controller tests still own actual payload contracts.
Keep fixture configuration excluded from unrelated Boot component scans. Focused coverage from
these tests describes only that selection; complete CI owns package and overall measurements.

## 35.2 Real-database API checks

**Default controlled parity (`*Test`, runs in CI).** `support.fixture.ScopedCalculationHttpParityTest` is the
default-suite owner of Profit/Discovery table and resolution-detail parity. It boots the real application on a
random port over a disposable Flyway `kpi_` schema loaded with the synthetic fixtures (plus a three-level recipe
chain), reuses `PopulatedFixtureHttpTest`'s offline proof/refresh/upstream ports and real account/session guard, and
exercises the real controllers, mappers and REAL scoped `CraftingProfitService`/`CraftingDiscoveryService` SQL inputs
for two accounts with overlapping ids. Each HTTP payload is compared with the in-process scoped calculation of the
same verified account (the application service, never a production DTO mapper as the oracle), together with
hand-written fixture outcomes so that a wrong-on-both-sides result is caught. It covers Profit default and buying
settings, Discovery selected character/discipline defaults and listing prices, row count/order and every
authoritative value, both missing-material maps, JSON null versus zero on nullable tree fields, the complete
recursive resolution tree (fields, child order and count) for Profit and Discovery, and the
`RECIPE_NOT_IN_CALCULATION` 404 including a recipe only the other account has. It asserts positive results and a
tree depth of at least three, so the comparisons are not vacuous. Isolated controller contract tests (§35.1) remain
stubbed-service transport checks and do not replace these guarantees.

**Owner-data checks (explicit runs only).** Any check against the developer's real database (§34) is separately
scoped, named `*IT` so Surefire's default patterns exclude them (§36.2), and not automatic acceptance. Such a run
boots the real application on a random port with no schema override, so it reads that database exactly as the running application does. The retired owner-database equivalence suites' assertions now live in the default parity test above; the bullets below describe what an explicit owner-data run must still do.

- **Timing, when the issue requires it** (§34): measure from issuing the HTTP request to holding the complete response body. Report startup versus repeat scope, row count, response size, settings, data scale and environment. A backend-only observation does not prove a browser navigation-to-complete-page requirement.
- **Result equivalence**: for each settings combination, call the endpoint over HTTP and call the same application-service method in process with identical inputs, then compare every row field by field — count, order, each authoritative value, blocked state and both missing-material maps. This is what proves a mapping preserves results on real data; fixture-based contract tests cannot. Both runs must be read-only, with no synchronization in between.
- **Compare a nullable field's JSON null against the source null explicitly.** Jackson's `asInt()`/`asText()` on a JSON null return `0`/`""`, so a naive comparison agrees with a mapping that substituted a zero for an absent value — which is precisely the distinction a response carrying absent items, quantities or display metadata exists to preserve. Read such fields through a null-returning helper, and print how many rows actually carried a null, so a run's output says whether the real data exercised that path at all.
- These print evidence and, for timing, assert nothing — matching `application.CraftingProfitServiceRealDbPerfIT`'s precedent. The equivalence check does assert, since a mismatch is a defect rather than a measurement.
- **A recursive payload is compared recursively, in order, or not at all.** For a tree-shaped response, walk the domain structure and the JSON together and compare every node's own fields and its child count and child order, rather than comparing a root, a size or a serialized string. A root-only comparison passes while children are reordered, merged, dropped or silently truncated, which is exactly what a repeated occurrence and a split-sourcing case exist to detect.
- **Choose the measured case by probing, not by assuming it is representative.** Where a payload's size depends on what the domain decided rather than on the request, select the candidate by running the operation on a few plausible inputs and keeping the one that actually produced the larger structure — and print how many were tried and what was found. A single arbitrary input can resolve in one node and turn a "deep tree" measurement into an unmarked shallow one; the selection heuristic must stay outside every assertion so its approximation cannot make a check pass.

## 35.3 Asynchronous trigger and task-status routes

`STORY-API-003` added the first route whose work outlives its request. §35.1 still governs the transport assertions; this subsection owns the methodology the asynchrony adds, and applies to any later sync/refresh trigger.

- **Control the work with latches, never with a sleep.** The substituted application service signals a latch when it is entered and then blocks on a second latch the test releases. That makes "the trigger returned while the work was still running" an observation at a point the test chose, rather than a race against a guessed duration — and it is the only way to assert a non-terminal state without flakiness.
- **Prove non-blocking acceptance by what is true *between* those two latches**: the HTTP response has been read, the service has been entered, and the status route already answers with a running state and no finish time. Asserting only that a 202 came back does not distinguish an asynchronous trigger from a fast synchronous one.
- **Wait for a terminal state with a bounded poll that fails loudly** (a deadline plus an `AssertionError` naming the task), not a fixed sleep. Poll through the status route where the route is what is under test, and through the facility where it is not.
- **Use the real task facility, not a stub, in the trigger's own tests.** The asynchrony, the identifier and the admission rule *are* the contract; a same-thread fake would pass while proving none of them. The application service is still substituted, so no GW2 API call or database write happens.
- **Assert exactly-once delegation per accepted task, and zero for a rejected one.** A validation test must show both that the service was never called and that no task was admitted — the latter is visible in a following trigger being accepted rather than refused as already-running, since an admitted task would still be unfinished.
- **Assert every documented lifetime property of the facility**, including the retention bound and the answer for an unknown identifier. Those properties are written down as facts in `ARCHITECTURE.md`, so they need a test rather than a claim; the retention test simply submits one more task than the bound.
- **Assert the absence of things too, in a failure body**: that it contains no successful-completion wording, and that the exception's own text — hosts, endpoints, JDBC URLs — is not in it. A failure path is where disclosure conventions actually get broken.
- **Do not require live account mutation to test a task protocol.** Trigger validation, the 404, the 405 and the framework's own routing can be verified against a really running server without starting a synchronization; whether the accepted path was exercised live must then be recorded as a limitation rather than implied.
- **Where a second trigger shares the facility, test the operation key's independence, not just its exclusion** (`STORY-API-004`). Registering both triggers against one real facility, holding the first operation at its latch and then submitting the *other* one is what distinguishes "one unfinished task per operation" from "one unfinished task"; asserting only the 409 on a repeat of the same operation cannot tell the two rules apart. Await the second task's own terminal state rather than reading its delegation count straight after the 202 — it runs on another thread, so an immediate count is a race, not an observation.
- **A reused boundary is verified by the reused tests passing unchanged, not by copying their assertions.** When a later trigger extracts something shared out of an earlier one (here the parameterless-body rule), the earlier route's untouched contract test asserting its exact message is the evidence that the extraction preserved it; the new route asserts only its own message.
- **Where a trigger selects between use cases, assert the one that must *not* have run** (`STORY-API-005`). "The requested variant was called once" is half the contract; without "the other variant was called zero times" a controller that called both, or the wrong one plus the right one, still passes. This needs the substituted service to count each entry point separately rather than share one counter — and separate latches too, so one variant can be held while the other runs to completion, which is what makes two independent operation keys observable rather than asserted.
- **Sweep the rejected values of an enumerated request field in one test, including the plausible-but-unsupported one.** A value the route deliberately does not implement (here a combined "all" refresh) belongs in the same rejection sweep as a mistyped case, a blank and a wrong JSON type: it is the value a caller is most likely to try, and asserting its 400 is what records that no such operation exists rather than that nobody thought of it.
- **Substituting a real application service by subclassing it is acceptable only when constructing the real one is inert.** Here the superclass's constructor builds a gateway over static sync utilities that open no connection until a refresh runs, and none ever does in these tests; where a constructor would connect or fetch, use the collaborator seam instead.

---

# 36. The CI gate — who runs which tests, and when

This section owns the division of test execution between the local implementation
loop and GitHub Actions. §20 states the local scope rule; this states what the gate
is, what it runs, what it cannot run, and what "verified" therefore means.

## 36.1 Division of responsibility

| Where | Runs | Authoritative for |
|---|---|---|
| Local (implementing agent, during implementation) | the narrowest scope covering the change: one test, one class, at most the directly related suite (§20) | that the change under construction behaves as intended |
| GitHub Actions (`.github/workflows/ci.yml`) | complete default suites with coverage, type/build, workflow checks, and the STORY-COV-006 controlled browser selection after build | that required deterministic checks and named synthetic journeys pass on the PR head |
| Local, explicitly invoked during pairing | live GW2 API smoke (§31.4), real-backend/full-stack browser checks, focused additional controlled journeys, owner-data `*IT` checks (§34, §35.2) | the explicitly observed integration/state/measurement scope that default CI does not establish |

Neither side's scope is reduced by this split: the gate runs the same commands the
developer runs, with the same test selection. What changed is *who pays for the
broad run* — CI, once per pushed commit, instead of a model session on every
attempt.

## 36.2 What the gate runs

Backend, frontend and workflow-check jobs run on pushes, pull requests and explicit CI dispatch. A coverage report job summarizes the product reports:

- **Backend** — `./mvnw -B -ntp test jacoco:report` on `ubuntu-latest`,
  against a `postgres:17` service container. Surefire's default patterns still
  select `*Test` classes only, so every `*IT` class stays excluded exactly as it
  is locally. The build has no platform-specific classifier, so the same command
  runs on a developer machine and on the Linux runner.
- **Frontend** — `npm ci`, Vitest coverage with `npm run test:coverage -- --reporter=default --reporter=github-actions`, and the type-checking `npm run build`. STORY-COV-006 adds the locked Chromium installation and `npm run smoke:ci` after that build, as a separately named step inside the existing required frontend job (§12.5). Its failure fails that job; keep journey outcomes separate from code-coverage percentages. Upload available journey results/failure artifacts with `if: always()`.
- **Workflow checks** — actionlint validates the workflows; `python -m unittest discover -s .github/scripts` runs the helper and configuration tests, with native Nginx supplied where required.

## 36.3 Required services and configuration

- **PostgreSQL**: a `postgres:17` service container with an empty database. This
  satisfies §31.2 better than a developer machine does — the database is isolated
  and disposable by construction, and each Layer 2 test still creates and drops
  its own uniquely named schema inside it. No schema, migration or seed data is
  pre-provisioned, because every such test builds what it needs.
- **`GW2_API_KEY`**: set to a literal placeholder. `repo.AppConfig` reads it at
  class-initialization time, so the variable must exist, but no test in the
  default run makes a live Guild Wars 2 API call (§14, §31.4). **A real key must
  never appear in the workflow, in a log, or in a repository variable for CI**;
  if a future test genuinely needs one, it belongs in Layer 4 and therefore
  outside this gate.
- **Database credentials** in the workflow are the service container's own,
  reachable only from that job for its lifetime. They are configuration, not
  secrets, and no secret of any kind is required by the gate today.
- **The CI jobs require no application secret.** Agent workflows use their own scoped
  GitHub and model credentials; those are not application test inputs.
- **Controlled browser runtime:** the locked `playwright-core` CLI installs its matching
  Chromium and Linux dependencies. `GW2_BROWSER_PATH` names that executable explicitly.
  The journey process owns its static/API origin; it needs no separately running application
  backend/database and must block real upstream traffic (§12.5).

## 36.4 What the gate deliberately cannot verify

- **Layer 4 live GW2 API smoke (§31.4)** must never run automatically anywhere,
  by definition.
- **Owner-data `*IT` checks and performance measurements (§34, §35.2)** read a
  populated real database; CI has an empty one. They stay local and explicit. Controlled
  PostgreSQL HTTP parity (§35.2) is not in this category: it runs in the default suite.
- **Real-backend/full-stack browser checks (§12.2)** still require explicitly prepared
  application/data environments and remain separately invoked. Installing Chromium does not
  make a controlled fixture equivalent to a live backend, database or production deployment.

A green gate means the default deterministic suites and controlled journeys pass, not that
every layer or live integration passed. Browser evidence is limited to the actual curated
scenarios and supported desktop boundary; reading-page exemptions retain their own narrow
checks. A local browser pass or an older green frontend run cannot replace required checks
on the current PR head.

## 36.5 What "verified" means for a story

The PR head must have green backend and frontend CI checks and a completed Codex
review before a merge. A red or missing required check holds the PR.
The implementing agent fixes relevant failures on the same branch and CI reruns
on the new head. Local-only checks required by the issue need recorded evidence;
their absence cannot be inferred away from green CI. The review-merge workflow
requires a current clean Codex summary and explicit no-pending-local-checks
evidence for an automatic Claude story merge; other PRs are supervised.

A story's Definition of Done must not demand a full local regression run. Where
it needs test evidence, it names the tests that must exist and pass, and the gate
is what proves the rest of the repository still passes with them.
