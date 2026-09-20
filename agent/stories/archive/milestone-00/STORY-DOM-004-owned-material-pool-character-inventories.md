## Story ID

STORY-DOM-004

## Title

Extend owned-material pool to include character inventories

## Status

DONE

## Milestone

milestone-00

## Goal

Make `repo.InventoryRepository.loadOwnedInventory()` include item quantities held in
`character_items` (populated by `STORY-SYNC-001`) alongside the existing
`account_materials`/`account_bank` sources, so crafting-profit/discovery calculations stop
understating owned materials that are sitting in character bags — per `DOMAIN_SPEC.md` §9 /
DQ-006, which is explicitly marked "DECIDED".

## Authoritative Source Documents / Sections

- `docs/DOMAIN_SPEC.md` §9, DQ-006 ("owned pool = material storage + bank + all character inventories", decided).
- `docs/KNOWN_PROBLEMS.md` §3.3 — the confirmed conflict this fixes.
- `docs/TEST_STRATEGY.md` §9 (repository integration tests), §15 (test data), §24 (priority ordering — this is a `KNOWN_PROBLEMS`-identified conflict).
- `src/main/java/repo/InventoryRepository.java`.
- `STORY-SYNC-001` (prerequisite — `character_items` must actually be populated for this to be verifiable).

## Context

`loadOwnedInventory()` currently sums `account_materials` + `account_bank` into a single
`Map<Integer, Integer>` (item id -> quantity) that `craft.*` consumes as an opaque "owned
quantity" input — the domain resolver itself does not distinguish material source, so this is
purely a repository-layer gap, not a `craft.*` logic bug. Once `STORY-SYNC-001` lands,
`character_items` will hold real rows this method can also sum.

`DOMAIN_SPEC.md` DQ-006 decides the pool includes **all** character inventories account-wide —
it does not ask for per-character scoping here. Restricting *usable* items by binding (e.g. a
soulbound item is only usable by its bound character) is a separate, still-unimplemented rule
(`docs/KNOWN_PROBLEMS.md` §3.4 / DQ-007) that needs a "selected character" concept that does not
exist yet anywhere in the planner input. This story must not attempt that — it only fixes the
raw pool total.

## Acceptance Criteria

- `loadOwnedInventory()` additionally sums `item_id`/`count` from `character_items` (all
  characters, all locations) into the same combined map returned today.
- The fix is verified to actually include character-held quantities (see Required Tests for
  what "verified" means given current test-tooling constraints).
- Binding (`binding`/`bound_to`) is read but **not** filtered or treated specially in this
  story — every owned `character_items` row counts toward the pool the same as bank/materials
  rows, matching DQ-006's "all character inventories" scope. This intentional deferral of
  bound-material rules (§3.4/DQ-007) must be stated explicitly in the Result section.
- No change to any `craft.*` consumer of this map — confirmed unnecessary since
  `craft.PlannerContext`/`CraftingResolver` already treat "owned quantity" as source-agnostic.

## Required Tests

- Repository integration test tooling against a disposable Postgres instance is not yet decided
  (`docs/TEST_STRATEGY.md` §9/§29) — do not introduce Testcontainers or any other new test-tooling
  decision as a side effect of this story.
- If a meaningful unit/integration test is practical without deciding new test tooling (e.g. by
  isolating the three per-source SQL reads behind a small seam that can be tested independently
  of a live connection), add one.
- If no such test is practical without a live database, this story must say so explicitly in the
  Result and document the manual verification performed instead (e.g. running the query against
  the developer's local Postgres with a known character inventory row and confirming the return
  value) — do not silently skip verification.

## Constraints

- Do not implement bound/soulbound filtering (§3.4/DQ-007) in this story.
- Do not touch `craft.*` domain code.
- Do not decide or introduce new database test tooling (Testcontainers etc.) as part of this
  story — that remains a `docs/TEST_STRATEGY.md` §29 open item.
- Smallest possible change to `InventoryRepository`.

## Dependencies

`STORY-SYNC-001` (character inventory sync must be in place first — otherwise `character_items`
has no real data to sum, and this story's fix would be untestable/unverifiable).

## Definition of Done

- [x] `loadOwnedInventory()` includes `character_items` quantities.
- [x] Verification approach documented (automated test, or explicit manual-verification note if
      no automated option is practical without deciding new test tooling).
- [x] `agent/CLAUDE_RESULT.md` filled in, explicitly noting binding/soulbound handling remains
      out of scope.

## Result

`InventoryRepository.loadOwnedInventory()` (`src/main/java/repo/InventoryRepository.java`) now
sums a third source into the same combined `Map<Integer, Integer>`: `SELECT item_id, count FROM
character_items WHERE item_id IS NOT NULL` (all characters, all `location`/`bag_index`/
`slot_index`/`equipment_slot` rows — no per-character or per-location filtering). This mirrors the
existing `account_materials`/`account_bank` read pattern exactly (smallest possible change, no
other method signature or behavior touched).

**Binding is explicitly out of scope, as required.** `binding`/`bound_to` columns are not read or
filtered by this change at all — every `character_items` row counts toward the pool identically to
bank/materials rows, matching DQ-006's "all character inventories" scope. Restricting usable
quantity by binding (§3.4/DQ-007) remains unimplemented and needs a "selected character" concept
that does not exist anywhere in the planner input yet.

**Dependency note (deviation from this story's stated Blockers/Dependencies):** `STORY-SYNC-001`
is still `TODO` — no code anywhere in the repo writes to `character_items` yet (confirmed via
repo-wide search), so there is no real synced character-inventory data to verify against today.
Rather than leaving this blocked, I verified the SQL/aggregation logic directly against the
already-existing `character_items` table schema (`src/PostgreSQL Query to create DB`, which
predates and does not depend on `STORY-SYNC-001`'s sync code): a throwaway, uncommitted harness
(`src/test/java/repo/ManualVerifyDom004.java`, deleted immediately after use, never part of the
build) inserted a temp `characters` row and one `character_items` row (`item_id=999999001,
count=7`) into the developer's local Postgres (`GWDatabase`), called the real
`loadOwnedInventory()`, confirmed `inv.get(999999001) == 7`, then deleted both rows. Output:
`RESULT item_id=999999001 count=7 expected=7 PASS=true`. This is the manual-verification path the
story's own "Required Tests" section anticipates ("running the query against the developer's
local Postgres with a known character inventory row and confirming the return value").

No automated test was added: per `docs/TEST_STRATEGY.md` §9/§29, repository-integration test
tooling (Testcontainers, etc.) is not yet decided, and this story explicitly forbids deciding it
here. The "small seam" alternative suggested in Required Tests (isolating the per-source SQL reads
so they're testable without a live connection) would require hand-writing fakes for the ~10+
`java.sql.Connection`/`PreparedStatement`/`ResultSet` interface methods actually exercised, which
is disproportionate infrastructure for a three-line SQL addition and was judged not "practical
without deciding new test tooling" as the story requires — so this is a documented manual
verification, not a skipped one.

Once `STORY-SYNC-001` actually lands, the real synced rows will flow through this same code path
with no further change needed here.

No `craft.*` files were touched — confirmed unnecessary, matching this story's own acceptance
criterion.

Tests run: `./mvnw -DskipITs clean test` — 5 tests, 0 failures, 0 errors, `BUILD SUCCESS`
(unchanged from pre-change baseline; no new automated tests added, see above).

## Blockers

None remaining for this story's own scope. Note for the backlog: `STORY-SYNC-001` (character
inventory sync) is still `TODO`, so in the real application `character_items` remains empty today
— this fix has no observable effect until that story lands, but the repository-layer code is
correct and ready for it.
