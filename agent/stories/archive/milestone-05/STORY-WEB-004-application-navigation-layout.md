## Story ID

STORY-WEB-004

## Title

Restructure the existing frontend around application navigation and shared layouts

## Status

DONE

## Milestone

milestone-05

## Goal

Replace the documented flat synchronization-plus-crafting arrangement with a responsive application structure, applying the new authoritative UX guidelines to existing workflows before additional pages extend that arrangement.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5: browser frontend, synchronization UI, frontend interaction/state tests and JavaFX coexistence.
- `docs/TARGET_ARCHITECTURE.md` sections 4.1 and 12: established frontend technology, presentation responsibility and UX-guideline reference.
- `docs/FRONTEND_UX_GUIDELINES.md` sections 1–3 and 5–8: navigation, shared presentation, responsive behavior, status and accessibility.
- `docs/CURRENT_ARCHITECTURE.md` section 5.11 and STORY-WEB-002: current stacked layout and synchronization lifecycle.
- `agent/product-owner-requests/Request-003-UX-guidelines.md`: immediate structural correction.

## Context

The documented App stacks synchronization above Crafting Profit. The Product Owner reports weak hierarchy and mixed workflows. This is planning evidence, not a source or visual audit. STORY-WEB-003 is active and must finish without modification by this planning pass. Incorporate its delivered bank/materials views when implementing this story; do not rebuild their backend reads.

## Acceptance Criteria

1. Inspect the then-current implemented frontend against the cited guidelines and record concrete gaps and correction scope in Result. Deliver structural changes in this story, not only a review or color changes.
2. Provide shared application navigation, an accessible current-destination indication and identifiable page headings for existing Crafting Profit, synchronization, bank and materials workflows. Expose no dead destinations for unimplemented screens. Local navigation/component choices remain implementation details within section 4.1.
3. Put synchronization controls and detailed task status in a dedicated application area. Preserve the four existing operation variants, explicit trigger behavior, per-operation admission handling, truthful outcomes, safe status-location checks and bounded polling. Moving between application areas preserves unfinished tracking without duplicating triggers or polling loops; lifecycle cleanup still occurs when tracking is actually disposed. Do not add browser-reload persistence or a new backend mechanism.
4. Establish reusable dark-theme layout, typography, spacing, controls and status treatments. Bound content widths, group page actions and secondary details, and reflow at narrow widths. Keep necessary table scrolling local to its region. Apply the shell and common treatments to existing bank/materials views while preserving slot order, empty slots, material grouping and backend values.
5. Present user-oriented synchronization labels and meaningful status/failure text prominently, with task IDs/timestamps and other diagnostic data secondary. Preserve unknown-outcome and partial-work distinctions; do not invent progress or cancellation.
6. Navigation and controls are keyboard-operable, semantically labelled and visibly focused. Page changes expose the current destination to assistive technology. Verify wide/narrow layouts, zoom and relevant contrast; record any remaining accessibility gaps against guidelines section 7.
7. Preserve existing API behavior and useful within-session screen controls when navigating. No navigation action submits a sync operation, no domain calculation moves into the frontend, and JavaFX remains supported. Update current-architecture documentation to describe the actual resulting frontend structure and record validation/limitations in Result.

## Required Tests

- Component/interaction tests for navigation and active destination, usable keyboard controls, independent synchronization state, navigation away/back during an unfinished task, absence of duplicate submissions/polling, and meaningful failure versus unknown-outcome presentation.
- Existing affected frontend regressions, type checking and production build. Preserve bank/materials rendering assertions from STORY-WEB-003 and synchronization semantics from STORY-WEB-002.
- Real-browser checks at representative wide and narrow viewports, keyboard focus/navigation, zoom/reflow and synchronization interaction using controlled API responses. Record viewport sizes and observations; no live mutation is required to validate layout.

## Constraints

- Follow the referenced UX rules and existing Vue/TypeScript decision; no new framework decision, backend rewrite or domain behavior change.
- Execute after the active bank/materials story finishes; preserve its delivered behavior. This story does not change that story's contract.
- Crafting Profit's detailed comparison/detail redesign is STORY-WEB-005; this story owns the shell and shared patterns. Do not introduce additional unfinished feature pages.
- No broad cleanup, invented progress, fabricated tree, or full-page performance/milestone completion claim.

## Dependencies

- STORY-WEB-003 completed before implementation; integrate its delivered screens.
- STORY-WEB-001 and STORY-WEB-002 (DONE).

## Definition of Done

Existing workflows use the responsive application shell with separate synchronization presentation, meaningful navigation/state tests and browser checks pass, and Result records guideline gaps addressed, evidence and remaining limitations.

## Result

The frontend now has an application shell: a site header with four addressable destinations, a shared
presentation layer, and synchronization as its own area instead of a panel on top of the crafting
analysis. **No file under `src/`, no `pom.xml` and no resource was touched** (verified: nothing under
`src/` has a modification time in this session), so no domain, HTTP contract or JavaFX behavior changed
and the backend suite was deliberately not re-run — there was nothing in it to affect.

### 1. Gaps found in the implemented frontend (AC 1)

Inspected in the browser and in source before changing anything, against `FRONTEND_UX_GUIDELINES.md`
§1–3 and §5–8:

- **§2 no application navigation.** Three unlabelled `<button>`s in a bare `<nav>`; no `<main>`
  landmark, no skip link, no per-screen title, no URL per screen, no document-title change. A reload
  always returned to Crafting Profit, and a screen change was silent for assistive technology.
- **§2 synchronization dominated every screen.** `SyncControls.vue` was rendered above all three
  screens — Bank and Materials included — putting four triggers, four task states, task UUIDs and
  three timestamps at the top of the crafting analysis.
- **§2 navigation destroyed useful state and submitted a calculation.** Every screen was unmounted, so
  returning to Crafting Profit discarded the chosen scope, settings, search and sort *and* re-posted
  `POST /api/crafting/profit` — the "navigation must not accidentally submit a calculation" rule,
  broken by the navigation itself.
- **§2/§6 nothing said tracking is not retained.** A reload silently forgot every tracked task.
- **§3 no shared presentation.** A 20-line global stylesheet plus per-component hardcoded hex values
  (`#1b2129`, `#4a3210`, `#4a1d1d`, `#141a21`, `#22303d`, `#2c3440`, `#33455a`), ad-hoc rem spacing, no
  type scale, and opacity (0.55–0.75) used for secondary text and for empty bank slots — which lowers
  contrast rather than expressing hierarchy.
- **§3 unbounded width and page-level table scrolling.** Every screen was `padding: 1rem` across the
  whole viewport; the 14-column table was `width: 100%` and *its* overflow was the page's, so the whole
  page scrolled sideways and the header moved with it.
- **§3 no reflow.** Fixed `min-width: 18rem` controls and 16–18rem grid minimums. Measured after the
  first restyling pass, the crafting page still forced **427px of content into a 360px viewport**; the
  control widths were changed to shrinkable ones and re-measured (below).
- **§5/§13 implementation terminology in the normal workflow.** Raw state enums as the status text
  (`PENDING`, `SUCCEEDED`), operation keys' terms as labels ("Sync Global Data"), endpoint paths
  printed on Bank/Materials (`GET /api/account/bank`), backend field names (`slotCount`,
  `categoryCount`), and `task <uuid>` plus three timestamps shown at the same level as the status.
- **§6 failures led with the code.** "The task failed: SYNC_FAILED — …" put the code before the
  meaning.
- **§7 no focus management, default focus ring on a dark surface, no reduced-motion handling.**
- **§8 no browser verification of layout, keyboard or contrast existed at all.**

Correction scope taken: the shell, the shared treatments, the synchronization area and the state's
ownership, plus applying all of it to the four existing workflows. Deliberately **not** taken: the
Crafting Profit column set, its selected-result detail and its recipe tree — `STORY-WEB-005` owns
those, so the table's own contents are unchanged here.

### 2. What was built

**Shell (new `src/shell/`).** `destinations.ts` (the four implemented destinations, their paths and
titles — nothing unfinished is listed, so there is no dead entry), `useHashRoute.ts` (the open
destination in the location hash, `hashchange` for Back/Forward, `replaceState` correction of an
unknown hash, `document.title` per destination — no router library, no new dependency),
`SiteHeader.vue` (wordmark, real `<a href>` destination links with `aria-current="page"`, the compact
"N tasks running" indication on the synchronization link) and `PageHeader.vue` (every page's single
`h1` with `tabindex="-1"`, one intro sentence, and the page's actions grouped in one place).
`App.vue` is now the shell: skip link, header, `<main>`, and the one open area.

**Shared presentation (`src/styles.css`, 11.60 kB built, 2.64 kB gzipped).** The §9 baseline palette as
semantic tokens, a 4/8/12/16/24/32/48 spacing scale, one body face and a small type scale, two radii,
and the reusable treatments: `.page` (bounded 1440px, centered), `.screen`, `.panel`, `.stack`,
`.cluster`, `.prose` (68ch), `.meta`, `.notice` (error/warning/info), `.status` (idle/busy/success/
failure/caution/unknown), `.diagnostics`, `.table-region`, plus default/hover/focus/active/disabled
states for buttons and inputs, one `:focus-visible` outline, and `prefers-reduced-motion` handling.
Every hardcoded color in every component was replaced by a token; no page defines a color of its own.
Disabled buttons keep their size and readability (dashed border, muted text) and state their reason
next to themselves instead of fading to 50% opacity.

**Synchronization as an area.** `SyncControls.vue` became `sync/SyncScreen.vue` at
`#/synchronization`. New `sync/provideSyncOperations.ts` puts the tracking state in the shell: one
instance, created by `App.vue`, injected by the page, which **refuses to run without it** rather than
silently creating a second one. New `sync/statusPresentation.ts` words the situations: the four states
read "Accepted, waiting to start" / "Running" / "Completed" / "Failed"; an unrecognized state is shown
as itself and is neither terminal nor success; a refusal ("Not accepted"), an unanswered trigger and
stopped tracking ("Outcome not established", keeping the last reported state visible as "Running —
outcome not established") and a task that ran and failed stay four distinct situations. The four
triggers read as actions ("Synchronize account", "Refresh Trading Post prices for Crafting Profit").
Task id, timestamps and the raw state code moved into a closed "Technical details" disclosure.
`useSyncOperations.ts` itself changed only by gaining each operation's description — the admission
rule, the four states, the status-location check, the chained non-overlapping polling, the 3 s × 600
bound and the token-based discarding are `STORY-WEB-002`'s, unmodified.

**Existing workflows.** Crafting Profit gained the page header with its action, a labelled
"Calculation controls" panel (scope, search, settings) and an "Opportunities" region (summary, states,
table), with the table inside a focusable, named `.table-region`, and a retry on a failed calculation.
Bank and Materials gained the same header, panels, notices and secondary-text treatment, and fluid
grids; their behavior — supplied slot order, empty slots in place, null never read as `0`,
`slotCount: 0` distinct from empty slots, the backend's categories/labels/stack order, per-stack
category id, no `<img>`, the four read states, the superseded-answer rule — is untouched, and their
endpoint paths and field-name hints were rewritten in user terms.

### 3. Judgement calls

- **Crafting Profit is kept alive across navigation; Bank and Materials are not.** §2 requires that
  navigation preserve useful controls and not submit a calculation, and Crafting Profit's rows belong
  to a scope and settings the user chose — so `<KeepAlive>` keeps them and returning posts nothing.
  Bank and Materials have no such input, and their read-per-open plus late-answer discarding is
  `STORY-WEB-003`'s tested contract, so they stay mounted-on-open. The consequence, recorded rather
  than hidden: returning to Crafting Profit shows the same rows as before, however long the detour
  took; the explicit Reload is the refresh.
- **Hash routing rather than a router dependency.** Four flat destinations, no nested or
  parameterized routes; this keeps §4.1's technology decision unchanged and adds no dependency, while
  still giving real links, direct URLs, Back/Forward and per-page titles.
- **Only the destination is in the URL.** No scope, settings, selection or task id, so nothing implies
  persistence across a reload; the synchronization page states in words that a reload cannot follow a
  task the backend keeps running.
- **The result table's `position: sticky` header was dropped.** The region is now the horizontal
  scroll container, so a sticky header inside it has nothing to stick to while the page scrolls
  vertically. Keeping ordinary page scrolling was the trade; a header that stays visible is the
  table's own concern in `STORY-WEB-005`.
- **No separate live region for navigation.** Focus moves to the new page's `h1`, which is what a
  screen reader announces; an additional announcement would duplicate it.

### 4. Verification

- `npm test` **98/98** in 11 files (84 before), controlled responses only. `src/__tests__/App.spec.ts`
  rewritten to 11 tests: the default destination marked and titled with no account or sync request;
  exactly the four destinations, as real `<a href>` links, plus the skip link; each destination's URL,
  document title, `aria-current` and **focused heading**; an unknown hash opening Crafting Profit and
  the address bar corrected; a browser `hashchange` (Back/Forward) changing area; the bank read per
  open and repeated on return; **no `/api/sync` or `/api/prices` request from any navigation**; the
  crafting search text and row set preserved with **no second `POST /api/crafting/profit`** on return;
  an unfinished account task **still tracked while another area is open**, with the activity indication
  showing, no repeated trigger and **no additional status lookup**, and the task id still present on
  return; and the late-bank-answer isolation from `STORY-WEB-003`. `sync/__tests__/SyncScreen.spec.ts`
  (18, was 17) keeps every `STORY-WEB-002` assertion under the new wording and adds the user-oriented
  label set, an unrecognized state shown as itself and still polled, and the busy reason; polling now
  stops when the *tracking state* is disposed, which is the case that matters. New
  `sync/__tests__/statusPresentation.spec.ts` (6) covers the state mapping and the four distinctions,
  including that a reported outcome is not taken away by a later failed lookup.
- `npm run type-check` clean; `npm run build` OK — 99.77 kB JS (36.43 kB gzip), 11.60 kB CSS (2.64 kB).
- **New `npm run smoke:layout`** (`scripts/layout-browser-smoke.mjs`), installed Chrome driven through
  `playwright-core` against a controlled origin in its own process: **PASSED, 11 steps**. Each of the four areas opened
  by a **fresh load of its own URL** at **1440×900, 768×1024 and 360×800** — heading, document title
  and marked destination correct, and **no page-level horizontal scrolling at any of the twelve
  combinations**; at 360px the table's own region held **1547px of columns in a 334px region** with
  `role="region"`, an accessible name and `tabindex="0"`; at **200% root font size** the page still
  reflowed (1440px content in 1440px); a real Tab walk gave **skip link → the four destinations →
  the page action**, each with a **2px visible focus outline**; **Enter** on the Bank link opened Bank
  and left focus on its heading; **17 rendered text/background pairs met WCAG AA** measured from
  `getComputedStyle` (lowest: the primary action at **4.92:1**), including the five status tones applied
  to a probe element; `prefers-reduced-motion: reduce` left 5 controls at `0s`; and across all 37 API
  requests there was **no `/api/sync` or `/api/prices` call, no foreign call and no page error**.
- `npm run smoke:sync` **PASSED, 9 steps** (was 8), same controlled-origin discipline: the area opened
  by `#/synchronization` with the right title and nothing triggered on load; four triggers, each
  button disabled while unfinished; **tracking survived leaving to Crafting Profit and returning** —
  the activity indication read "3 tasks running", the controls were gone from the crafting page, the
  tracked task id was still there afterwards and **no trigger was repeated**; the account task tracked
  to "Completed" in 3 lookups; a `FAILED` task from an HTTP 200 rendered with the no-rollback wording
  **before** the `SYNC_FAILED` code (asserted by position); the two price variants kept apart with no
  retry offered for the unresolvable one; polling stopped at 8 lookups; exactly four triggers with the
  documented bodies; no page error and no non-backend call.
- Live-backend regressions, read-only, against the running backend and the user's real PostgreSQL:
  `npm run smoke:browser` **PASSED, 8 steps** (30 selector entries, HTTP 200, **3175 rows**, first
  total profit `26g 41s 76c`, sort, search, scope change to `DISCIPLINE / Chef` → 433 rows) and
  `npm run smoke:account` **PASSED, 8 steps** (**180 bank slots, 17 empty, in the supplied order**, the
  reload repeating only that read, **9 categories / 505 stacks** with labels and order as supplied, no
  icon image, **no `/api/sync` call**) — the rendering compared field-for-field against the script's own
  second read of each route, so the restructured markup demonstrably still shows the backend's values.
- Environment note, per `tasks/lessons.md`: three **pre-existing IPv6-only listeners** were found on
  5173/5174/5175 and were left alone; the controlled-origin checks were run on **5184/5185** (probed
  free on both address families first) and the live checks against a dev server **this session started
  on 127.0.0.1:5190** and then stopped. `scripts/stubOrigin.mjs` now holds the hardened bind/identity
  logic once for both controlled checks (explicit `127.0.0.1`, rejects on a `listen` error, asserts it
  served the page).

### 4b. Re-verification after the interruption

This story was interrupted after the implementation and the documentation updates but before its
Status was set. Nothing was re-implemented on resume; the delivered work was re-run instead, and every
check reproduced the figures recorded above:

- `npm test` **98/98** in 11 files; `npm run type-check` clean; `npm run build` OK at the identical
  sizes (99.77 kB JS / 36.43 kB gzip, 11.60 kB CSS / 2.64 kB gzip).
- `npm run smoke:layout` **PASSED, 11 steps** — the same 334px region holding 1547px of columns, the
  same lowest contrast **4.92:1** across 17 pairs, 5 controls at `0s`, 37 API requests with no
  synchronization, foreign call or page error.
- `npm run smoke:sync` **PASSED, 9 steps** — tracking still survived leaving and returning ("3 tasks
  running") with exactly four triggers in total, and the failure text still placed the meaning ahead
  of the `SYNC_FAILED` code.
- Live read-only regressions against the running backend and the real PostgreSQL:
  `smoke:account` **PASSED, 8 steps** (180 slots, 17 empty, supplied order; 9 categories / 505 stacks;
  no `/api/sync` call) and `smoke:browser` **PASSED, 8 steps** (30 selector entries, **3175 rows**,
  first total `26g 41s 76c`, scope change to `DISCIPLINE / Chef` → 433 rows).
- Environment discipline per `tasks/lessons.md`: the live checks ran against a dev server this session
  started on an explicitly probed-free **127.0.0.1:5191** (`--strictPort`, identity confirmed by its
  own startup log and an HTTP 200 before any check), not the scripts' `localhost:5173` default. It was
  stopped afterwards and the port confirmed released; the four pre-existing dev servers were left
  untouched.

### 5. Limitations

- **No real assistive-technology run.** `aria-current`, the focus move, the landmarks, the accessible
  names and the document titles were verified mechanically; no screen reader was used, so how the
  change is announced in NVDA/JAWS/VoiceOver is unverified. Full WCAG 2.2 AA conformance is **not**
  claimed.
- **Contrast is measured, not exhaustive.** 17 pairs from the Crafting Profit page plus the five status
  tones; each other page's unique combinations (a bank slot number, rarity text) reuse the same muted
  token on a surface but were not individually measured, and the disabled-control pair was not
  measured. `forced-colors`/`prefers-contrast` were not tested.
- **Zoom was checked as text zoom** (root font size at 200%) and as a 360px viewport; true browser
  page zoom was not emulated separately.
- **Crafting Profit is otherwise unchanged**: all 14 columns and all rows (3175 live) still enter the
  DOM with no virtualization, paging or selected-result detail, and the table header no longer sticks.
  That is `STORY-WEB-005`'s scope.
- **No mobile disclosure menu, no footer, no artwork or icon set**; the four short destination links
  simply wrap at narrow widths.
- **The synchronization page was never driven against the live backend** (deliberate: that would spend
  the GW2 API budget and write to the user's database), so every synchronization state, failure, unknown
  admission and unresolvable identifier remains controlled-response evidence only.
- **§33 full-page performance is not claimed or measured**, no Phase 5 exit criterion is declared met,
  and no milestone transition is claimed. The Java suite was not run because no Java file changed.

## Blockers

None.
