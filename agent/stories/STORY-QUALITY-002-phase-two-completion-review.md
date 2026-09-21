## Story ID

STORY-QUALITY-002

## Title

Review Phase 2 project health before milestone completion

## Status

DONE

## Milestone

milestone-02

## Goal

Perform the bounded PROJECT HEALTH REVIEW required before Phase 2 closure, assessing domain isolation, behavior preservation and the recorded account-wide knowledge correction against the milestone's exit criteria.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` section 6, Phase 2 objective, dependencies and exit criteria only.
- `docs/TARGET_ARCHITECTURE.md` sections 7 (Domain Independence Rule), 10 (Persistence Boundary) and 34 (PROJECT HEALTH REVIEW policy).
- `agent/stories/STORY-DOM-017-independent-crafting-domain-types.md`, Result.
- `agent/stories/STORY-DOM-018-extract-recipe-knowledge-policy.md`, Result and Blockers.
- `agent/stories/STORY-DOM-019-align-account-wide-recipe-knowledge.md`, Result and verification limitation.
- `agent/stories/STORY-INFRA-003-consolidate-database-connection-helper.md`, Result.

## Context

The supplied Phase 2 roadmap marks its implementation criteria done. Its bounded health review remains required and has no existing task. STORY-DOM-019 records the correction of the disagreement characterized by STORY-DOM-018; assess the final evidence together rather than treating the earlier finding as an unexamined current blocker. Recorded test results and limitations are review inputs, not independently verified facts for this review.

## Acceptance Criteria

1. Assess each Phase 2 objective and exit criterion with concrete evidence: independent domain types and repository mapping, absence of repository/persistence/transport dependencies in the domain, shared pure account-wide knowledge policy, consolidated connection helper, and database-independent Phase 0/1 domain tests. Record any unsatisfied criterion or uncertainty explicitly.
2. Compare Phase 2 implementation and documentation with TARGET_ARCHITECTURE sections 7 and 10 and relevant CURRENT_ARCHITECTURE statements. Distinguish intentional transitional structure from evidenced current debt; assess only this milestone's boundary.
3. Assess behavior-preservation evidence for the refactors separately from STORY-DOM-019's intentional, already-decided knowledge correction. Review the connection-helper Result's TEST_SCHEMA_PROPERTY caveat and the recorded verification limitations for their actual impact, without assuming either is a blocker or harmless.
4. Check Phase 2 documentation and known-problem records for stale facts, contradictions, obsolete references, duplicated authority and resolved findings still presented as current. Make only small, clearly evidenced corrections at their authoritative owner; preserve historical story results as historical evidence.
5. Assess whether existing verification meaningfully supports the milestone claims, including pure-domain independence, persistence mapping and knowledge agreement across all three repository entry points. Distinguish recorded executions from checks actually performed during this review and explain remaining confidence limits.
6. Record evidence, corrections, concrete findings and an overall milestone-health assessment in this story's Result and the established workflow reporting. Each finding identifies affected components, evidence, impact and whether it blocks closure. Any proposed criterion transfer includes an explicit destination and rationale for subsequent planner/user disposition; a proposal does not satisfy the criterion. Review completion must not declare the milestone closed.

## Required Tests

- Assess existing story results, saved verification evidence and relevant implementation evidence against specific Phase 2 review questions.
- Run a targeted dependency inspection or existing targeted check only when needed to answer a concrete unresolved question; record the question, command, outcome and limitations.
- Do not automatically rerun all suites or add tests because a review occurred. Report unverified claims and verification weaknesses honestly.

## Constraints

Bounded assessment under TARGET_ARCHITECTURE section 34: review first, remediation later. Inspect only Phase 2 roadmap material and milestone-relevant evidence. Small authoritative documentation corrections are permitted; substantial implementation, refactoring or test work requires a separate normal story decided during subsequent planning.

**Non-Goals:** no broad bug hunting, automatic broad regression testing, arbitrary test expansion, test-strategy redesign, speculative cleanup/refactoring, architecture implementation or later-milestone work. Planned future replacement alone is not current debt. Do not create stories or automatically populate the backlog from review findings. Do not silently drop or transfer exit criteria.

## Dependencies

STORY-DOM-017, STORY-DOM-018, STORY-DOM-019 and STORY-INFRA-003 (all DONE).

## Definition of Done

The bounded assessment and any small documentation corrections are recorded with evidence. Findings, uncertainty and verification limits are explicit, and the Result gives a readiness assessment for subsequent normal planning. The review may finish with unresolved findings; milestone closure remains a separate planner/user decision.

## Result

### What was inspected

`docs/ROADMAP.md` §6 (Phase 2 objective, dependencies, exit criteria, high-level stories);
`docs/TARGET_ARCHITECTURE.md` §7 (Domain Layer / Domain Independence Rule), §10 (Persistence
Boundary), §34 (health-review policy); `agent/stories/STORY-DOM-017`, `STORY-DOM-018` (Result and
Blockers), `STORY-DOM-019` (Result), `STORY-INFRA-003` (Result); `docs/CURRENT_ARCHITECTURE.md` §2–§4
and §9; `docs/KNOWN_PROBLEMS.md` §4.1–4.4, §6, §9; `docs/DOMAIN_SPEC.md` §34/§35 and DQ-010. Source
spot-checks performed directly during this review (not merely re-read from story text): every
`src/main/java/craft/*.java` and `src/test/java/craft/*.java` file (import/dependency inspection);
`repo/RecipeRepository.java`'s `RecipeKnowledgePolicy` call sites; `repo/Db.java`; all six
`sync/*.java` classes' connection-opening call sites; `src/test/java/repo/DbTest.java`; presence of
`src/test/java/repo/RecipeRepositoryTest.java#knowledgeCrossCheck_...AgreesAcrossAllThreeEntryPoints`
and `src/test/java/craft/RecipeKnowledgePolicyTest.java`'s `isKnownAccountWide` cases;
`src/test/java/uiverify/CraftingDiscoveryViewBlockedRowIT.java`'s character-seeding fixture.

### AC1 — Exit-criterion evidence

All five Phase 2 exit-criteria sub-items other than the health review itself are satisfied with
concrete evidence, independently re-checked against current source in this review rather than only
re-stated from story Results:

- **`craft.*` has no `repo.*` import.** Fresh grep across every production and test file under
  `craft/` for `import repo`, `java.sql`, `jdbc`, `com.fasterxml`, `javafx` returns zero real matches
  (one file, `CraftingResolverBoundMaterialTest.java`, mentions `repo.InventoryRepository...` only in
  comments describing expected repository output, not an actual import).
- **Independent domain types + mapping boundary.** `craft/Recipe.java`, `Ingredient.java`,
  `PriceQuote.java` exist; `repo/RecipeRepository.java`/`repo/tp/TpPriceRepository.java` exist as the
  mapping boundary. File/boundary presence confirmed directly; internal row-to-object mapping fidelity
  was not re-read line-by-line here and relies on `STORY-DOM-017`'s recorded (not re-executed)
  `RecipeRepositoryTest`/`TpPriceRepositoryTest` evidence.
- **Shared knowledge policy used by all three entry points.** `repo/RecipeRepository.java` calls
  `RecipeKnowledgePolicy.isKnownAccountWide` at three call sites (lines 80, 139, 295), matching
  `loadRecipes`/`loadRecipesForCharacter`/`loadMissingDiscoverableRecipeIdsForCharacter` per
  `STORY-DOM-019`. The claimed cross-check test method
  `knowledgeCrossCheck_recipeKnownOnlyByAnotherCharacterAgreesAcrossAllThreeEntryPoints` exists in
  `RecipeRepositoryTest.java` (presence confirmed, not re-executed).
- **Consolidated connection helper.** `sync/Db.java` no longer exists. All six `sync/*.java` classes
  (`AccountSync`, `CharacterSync`, `IconSync`, `ItemSync`, `RecipeSync`, `TpSync`) import `repo.Db`
  and call `Db.open()` — 17 call sites confirmed by grep.
- **Database-independent Phase 0/1 domain tests.** No `src/test/java/craft/*.java` file references
  `DriverManager`, `java.sql.Connection`, or a real `repo.*` import.

No unsatisfied criterion found in this group; this is the review's highest-confidence area because
the underlying code was independently re-inspected rather than only inferred from story Results.

### AC2 — Target-architecture comparison

`TARGET_ARCHITECTURE.md` §7 (Domain Independence) and §10 (Persistence Boundary) are both satisfied
by the current `craft.*`/`repo.*` structure, per the AC1 evidence.

Comparing against `docs/CURRENT_ARCHITECTURE.md` found a real, evidenced documentation defect: its
§2 (package layout), §3 (responsibility table), and §4 (dependency diagram/prose) still described
the **pre-Phase-2** state as current fact — `craft/*` still listed `CraftingGraphCache`/
`CraftingGraphDto` (relocated to `repo/*` by `STORY-DOM-017`), `sync/*` still listed a `Db` class
(deleted by `STORY-INFRA-003`), §3's `sync` row still said it "has its own duplicate `Db` connection
helper" (false since `STORY-INFRA-003`), and §4's diagram/prose still asserted `craft.*` "directly
imports and depends on `repo.RecipeRepository.Recipe`/`Ingredient`" (false since `STORY-DOM-017`) —
while §9 of that **same document** already carried "(Resolved by `STORY-DOM-017`/...)" annotations
for the identical facts. This was an internal self-contradiction, not merely staleness relative to
code. Corrected directly in `CURRENT_ARCHITECTURE.md` §2, §3, and §4, since that document is its own
authoritative owner (`CLAUDE.md` Documentation Ownership) and this is exactly the "small, clearly
evidenced correction at the authoritative owner" `TARGET_ARCHITECTURE.md` §34 permits during a review.

Distinguishing transitional structure from debt: `CURRENT_ARCHITECTURE.md` §9 items 3–4 (`EctoView`
not wired through an application-service boundary; `Gw2App` calling `sync.*`/`repo.*` directly from
UI handlers) remain open but are already explicitly Phase-3-scoped (`KNOWN_PROBLEMS.md` §4.4 Status
line, `docs/ROADMAP.md` later phases) — correctly outside this milestone's boundary, not Phase 2 debt.

### AC3 — Behavior-preservation and verification-limitation impact

`STORY-DOM-017`/`STORY-DOM-018`/`STORY-INFRA-003` each recorded a full-suite pass (81, 98, 74 tests
respectively, 0 failures) at implementation time. This review did not rerun the full suite —
`TARGET_ARCHITECTURE.md` §34 treats automatic reruns as a non-goal for a bounded review — and instead
independently re-verified the structural claims underpinning those results still hold in current
source (AC1). That is inspection evidence, not re-executed test evidence.

**`STORY-INFRA-003`'s `TEST_SCHEMA_PROPERTY` caveat.** Confirmed by reading `repo/Db.java` and
`repo/DbTest.java`: every pre-existing `repo`/`sync` integration test bypasses `Db.open()` via its own
direct `DriverManager` connection, so only the story's added `DbTest` actually calls `Db.open()`
against a real database. Impact assessed as low: the consolidation is a mechanical one-line
substitution (`Db.openConnection()` → `Db.open()`) in six `sync` classes, each independently confirmed
in this review to import `repo.Db` and call `Db.open()` at all 17 of their connection sites; the
shared method itself has no branching logic for the caveat to hide a defect in, and `DbTest` directly
exercises its actual behavior (open/valid/close). A real but narrow gap, not a blocker.

**`STORY-DOM-019`'s verification limitation** (TestFX/desktop IT suite not run for the ownership
change). Independently re-confirmed the specific claim: `CraftingDiscoveryViewBlockedRowIT` (line 54)
inserts exactly one `characters` row, so it structurally cannot exercise the cross-character
disagreement this story corrected. This substantiates "no outcome change for that one test," but also
means **no live-view (TestFX) test currently exercises the corrected cross-character behavior
end-to-end** — it is verified only at the `RecipeRepository`/policy layer (a real PostgreSQL
integration test). Impact assessed as non-blocking for Phase 2 (the exit criterion targets the
domain/persistence layers, which are covered) but a genuine, disclosed confidence gap for a
user-visible Discovery-list behavior change — worth a targeted future multi-character Discovery IT if
that view is next touched. Not fixed here (new test authoring is outside this review's bounded scope).

### AC4 — Documentation/known-problem health

Corrections made (small, evidenced, at the authoritative owner):

- `docs/CURRENT_ARCHITECTURE.md` §2, §3, §4 — see AC2.
- `docs/KNOWN_PROBLEMS.md` §9 — "Still-open work: §4's architectural coupling (Phase 2)" was stale/
  misleading: §4.1–4.3 are actually Resolved (matching §4's own subsection Status lines); only §4.4
  (Phase-3-scoped) remains open within §4. Corrected the summary sentence to say so explicitly.

No other stale facts, duplicated authority, or resolved-but-marked-open findings were found within
Phase 2's scope. `STORY-DOM-018`'s Blockers section ("the Phase 2 ... exit criterion ... remains
open") was historically accurate when written and is explicitly superseded by `STORY-DOM-019`'s
Result; left untouched as historical evidence per this story's Constraints.

### AC5 — Verification-confidence assessment

Executed during this review (not merely recorded elsewhere): the dependency-inspection greps (AC1),
direct reads of `repo/Db.java`, all six `sync/*.java` classes, `repo/DbTest.java`, presence checks of
`RecipeRepositoryTest`/`RecipeKnowledgePolicyTest`'s relevant methods, the
`CraftingDiscoveryViewBlockedRowIT` fixture's character count, and `DOMAIN_SPEC.md` §34/§35's text.
No test suite was executed by this review, consistent with §34's non-goal against automatic reruns;
the targeted checks above were performed only to answer concrete open questions.

Confidence levels:
- Pure-domain independence: **high** — directly re-verified against current source.
- Persistence-mapping fidelity (`RecipeRepository`/`TpPriceRepository` field-level mapping):
  **moderate** — boundary/file existence confirmed directly; internal correctness relies on
  `STORY-DOM-017`'s recorded, not re-executed, test results.
- Knowledge agreement across all three entry points: **moderate-to-high at the repository/policy
  layer** (call sites and the agreement test's existence confirmed) but **not exercised by any
  live-view test** (see AC3) — confidence is layer-specific, not end-to-end.

### Unresolved items / candidates for later planning

- (Non-blocking) No live-view (TestFX) test exercises `STORY-DOM-019`'s cross-character Discovery
  ownership correction end-to-end; candidate for a small future UI-layer test story if
  `CraftingDiscoveryView` is next touched.
- (Non-blocking, already recorded, unchanged by this review) `KNOWN_PROBLEMS.md` §2.2
  (`BankView`/`MaterialsView` hardcoded credentials) and §7.8 (orphaned `Gw2PriceFetch`).
- (Non-blocking, already recorded, Phase-3-scoped, unchanged by this review)
  `CURRENT_ARCHITECTURE.md` §9 items 3–4 (`EctoView`/`Gw2App` UI-layer wiring).

No criterion transfer is proposed: every Phase 2 exit-criterion sub-item other than the health review
itself is satisfied with direct evidence, so nothing needs deferring to a later milestone.

### Milestone-health assessment

Phase 2's five implementation exit criteria are satisfied with concrete, independently-verified
evidence (AC1). This bounded PROJECT HEALTH REVIEW is now complete, discharging the sixth exit
criterion's review obligation. Two small, evidenced documentation corrections were made (AC2/AC4).
One narrow, disclosed, non-blocking verification gap was found (AC3/AC5: no live-view test for the
cross-character Discovery correction). No blocking finding was identified against Phase 2's scope.

Per `TARGET_ARCHITECTURE.md` §34, review completion is distinct from milestone completion: this
Result does not declare Phase 2 or milestone-02 closed. That decision belongs to subsequent
planner/user disposition of this Result.

## Blockers

None against Phase 2's exit criteria. See "Unresolved items" above for one non-blocking follow-up
candidate (no live-view test for the STORY-DOM-019 cross-character correction).
