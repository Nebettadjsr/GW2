## Story ID

STORY-QUALITY-001

## Title

Review Phase 1 project health before milestone completion

## Status

DONE

## Milestone

milestone-01

## Goal

Perform a bounded project-health review before Phase 1 is considered complete.

The purpose of this review is to determine whether the repository, documentation,
roadmap state and completed Phase 1 work are still coherent with the documented
project direction.

The review identifies concrete inconsistencies, stale information, unfinished
milestone work and justified maintenance findings.

It is an assessment story, not a general bug-hunting, refactoring or implementation story.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md
- docs/TARGET_ARCHITECTURE.md
- docs/CURRENT_ARCHITECTURE.md
- docs/DOMAIN_SPEC.md
- docs/KNOWN_PROBLEMS.md
- docs/TEST_STRATEGY.md
- agent/stories/BACKLOG.md
- completed and remaining Phase 1 stories
- applicable user decisions and product-owner requests

## Context

Phase 1 contains multiple completed implementation stories and is approaching
milestone completion.

Before the milestone is considered complete, the repository should be reviewed
as a whole to determine whether:

- the roadmap still represents the intended project direction;
- Phase 1 exit criteria are actually satisfied;
- target architecture and roadmap remain consistent;
- current architecture documentation reflects the implementation;
- authoritative documentation is internally consistent and current;
- completed, skipped or unchecked milestone work has been handled correctly;
- known problems and technical-debt records still describe current reality;
- obsolete, dead or redundant repository artifacts are evident;
- later-milestone work has not accidentally been introduced or required by Phase 1.

The review should identify concrete findings without expanding its own scope into
their implementation.

## Acceptance Criteria

- Review the Phase 1 roadmap goals, dependencies and exit criteria.

- For each relevant Phase 1 criterion, determine whether it is:
    - satisfied with existing evidence;
    - still open;
    - obsolete;
    - incorrectly left unchecked despite being completed;
    - intentionally deferred;
    - still applicable and therefore required in a later milestone.

- Compare `docs/ROADMAP.md` with `docs/TARGET_ARCHITECTURE.md` and identify concrete
  contradictions, drift, missing milestone work or outdated assumptions.

- Review `docs/CURRENT_ARCHITECTURE.md`, `docs/DOMAIN_SPEC.md`,
  `docs/TEST_STRATEGY.md`, `docs/KNOWN_PROBLEMS.md` and other authoritative
  Phase 1 documentation for:
    - stale facts;
    - contradictions;
    - obsolete references;
    - duplicated authoritative information;
    - references to renamed or removed artifacts;
    - documentation that no longer matches the current repository.

- Review the repository at a high level for clearly evidenced project-health findings,
  including:
    - dead or obsolete code/artifacts;
    - duplicated or superseded implementations;
    - architecture drift;
    - clearly unnecessary compatibility paths;
    - unresolved TODO/TBD items;
    - obvious technical debt relevant to current repository health.

- Review the existing verification strategy at a high level and identify only concrete,
  material gaps that affect confidence in current Phase 1 behavior.

  Do not expand the test suite, redesign testing or create tests solely as part of
  this review.

- Review `docs/KNOWN_PROBLEMS.md` and:
    - remove or correct entries that are demonstrably obsolete;
    - ensure concrete unresolved problems discovered by the review are recorded;
    - distinguish historical issues from current problems.

- Produce a concise review result containing:
    - what was inspected;
    - concrete findings;
    - documentation corrections made during the review;
    - unresolved items;
    - milestone criteria that remain open;
    - items that should be transferred to a later milestone;
    - whether Phase 1 is ready for normal planner completion assessment.

- Where a finding requires implementation, refactoring, significant test work or product
  behavior changes, record the finding for subsequent planning rather than performing
  that work inside this story.

## Verification

Use existing repository evidence where practical.

Existing tests, build results, implementation state and completed story results may be
consulted where they help verify a specific milestone criterion or resolve a concrete
documentation question.

Do not run broad test suites merely because this is a quality review.

Do not create new tests unless required to establish a very specific fact necessary
for the review and no existing evidence can establish it.

The review must not become a general regression-testing exercise.

## Non-Goals

This story does not:

- perform a broad search for product bugs;
- attempt to find every possible defect;
- refactor code merely because improvement opportunities exist;
- remove complexity that belongs to planned future architecture work;
- implement later roadmap milestones;
- redesign architecture;
- redesign the test strategy;
- increase test count or coverage for its own sake;
- introduce speculative cleanup work;
- change intended product behavior;
- generate cleanup stories without a concrete evidenced finding.

## Constraints

Keep the review bounded to repository health and Phase 1 milestone coherence.

Do not mistake planned future architecture for current technical debt.

Do not classify code as unnecessary merely because a later milestone intends to
replace or restructure it.

Do not create work solely because a quality category was inspected.

Prefer concrete repository evidence over speculative improvement suggestions.

Small authoritative documentation corrections discovered during the review may be
performed directly.

Implementation changes, meaningful refactoring and substantial verification work
belong in separate stories derived from concrete findings.

Do not change workflow-owned Active/Archived state or select another story.

## Dependencies

STORY-DOC-001 must be DONE before this review executes.

## Definition of Done

The Phase 1 project-health review has:

- assessed the milestone criteria;
- checked roadmap and target-architecture coherence;
- reviewed authoritative documentation;
- reviewed known problems and obvious repository-health findings;
- identified unfinished, obsolete or transferable milestone items;
- recorded concrete findings without speculative work;
- corrected small evidenced documentation inconsistencies where appropriate;
- produced a bounded conclusion for subsequent planner assessment.

No broad refactoring, bug hunt, test expansion or later-milestone implementation has
been performed as part of the review.

## Result

### What was inspected

`docs/ROADMAP.md` (Phase 0/1 exit criteria and high-level stories in full; later phases at a
coherence-check level), `docs/TARGET_ARCHITECTURE.md`, `docs/CURRENT_ARCHITECTURE.md`,
`docs/DOMAIN_SPEC.md` (§2.2.1, §28, §42, §50 in full; scanned for other Phase-1-relevant sections),
`docs/KNOWN_PROBLEMS.md`, `docs/TEST_STRATEGY.md`, `agent/stories/BACKLOG.md`, every file under
`agent/stories/` (current milestone-01 stories) and `agent/stories/archive/milestone-00/` (listing),
`agent/user-decisions/UD-001` through `UD-004`, all files under `agent/product-owner-requests/`,
and `agent/PROJECT_STATE.md`. Source spot-checks: `craft/BlockedReason.java` and every call site
assigning a `BlockedReason` value; `craft/CraftingPlanner.java` (confirmed §7.1/§7.2's removed dead
code/debug print stay removed); `BankView.java`/`MaterialsView.java`; `api/Gw2PriceFetch.java`;
confirmed `Main.java` no longer exists; `parser/CharacterItemsParserTest.java` and
`src/test/resources/parser/`.

### Concrete findings and documentation corrections made

**Phase 1 exit criteria — satisfied.** All `KNOWN_PROBLEMS.md` §3 conflicts are Resolved or answered
via a `UD-*` decision; `craft.BlockedReason` carries the full §42 set and every value is actually
assigned (verified by source search), not just declared; the dead legacy craft-vs-buy path (§7.1) is
removed. No blocking gap found.

**Mistakenly-unchecked completed work (roadmap health), corrected in `docs/ROADMAP.md`:**
- Phase 1: "Add the missing `BlockedReason` values..." was unchecked despite `STORY-DOM-008`/
  `STORY-DOM-013` completing it — marked `(Done)`.
- Phase 1: "Decide whether the 250-craft simulation cap is intentional..." was unchecked despite
  `UD-003`/`DOMAIN_SPEC.md` §28 resolving it — marked `(Done)`.
- Phase 0 (already archived, but per `TARGET_ARCHITECTURE.md` §34 inherited gaps are assessed in the
  current milestone without reopening the archived one): "Add integration tests for character/item
  sync and the repositories consumed by crafting logic" was unchecked despite `STORY-TEST-002`
  through `STORY-TEST-005` completing it — marked `(Done)`.

**Stale/obsolete references (documentation health), corrected:**
- `docs/TARGET_ARCHITECTURE.md` §35 told future work to link root `README.md` to `agent/README.md`,
  a path that has never existed; the real file is `agent/agent_README_experimental.md` (already used
  correctly by the root README and already flagged as a deviation in `STORY-DOC-001`'s Result, but
  the authoritative target-architecture text itself was never corrected). Fixed both occurrences.
- `docs/CURRENT_ARCHITECTURE.md` still listed the deleted `Main.java` in its package layout, package
  responsibility table, and dependency-direction bullets, and still described Ectoplasm Salvage as
  "two independent implementations" with a `Main` flow diagram, and `EctoView`'s calculation as
  computed "inline." `Main.java` was actually deleted under the resolved `UD-002`
  (`KNOWN_PROBLEMS.md` §3.6), and `STORY-DOM-016` extracted `EctoView`'s calculation into the plain
  `EctoSalvageCalculator` class. Updated §2, §3, §4, and rewrote §5.3's flow diagram to the current
  single-implementation state; also added the newly-found `BankView`/`MaterialsView` hardcoded
  connections to §9's mixed-responsibility list.
- `docs/DOMAIN_SPEC.md` §42 stated "not every [blocked reason] is currently represented explicitly
  in the implementation" — false as of `STORY-DOM-008`/`STORY-DOM-013` (verified all seven values are
  declared and assigned). Corrected.
- `docs/KNOWN_PROBLEMS.md` §4.4 still described `EctoView`'s calculation as inline (stale for the
  same reason as above); corrected to describe the current extracted-but-not-application-service-wired
  state, and refined its recommendation accordingly. §6's duplication-summary table row for JDBC
  connection acquisition was updated from two to four independent paths (see next finding).
- `agent/stories/BACKLOG.md`'s "Archiving" section claimed "every current story is tagged
  `milestone-00`... the only phase this project has had so far," which is false: Phase 0 has already
  been archived to `agent/stories/archive/milestone-00/` and current stories are `milestone-01`.
  Corrected. The same section also had a dangling, undescribed bullet
  (`STORY-TEST-010-character-item-sync-integration-test.md`) sitting outside any list structure,
  pointing to a file that does not exist anywhere in the repository (not in `agent/stories/`, not in
  the archive). The work it apparently named was actually delivered under `STORY-TEST-003`. Removed
  the broken reference.

**New open items found (real code, recorded in `docs/KNOWN_PROBLEMS.md`, not fixed — implementation
work, out of this review's scope):**
- New §2.2: `BankView.java`/`MaterialsView.java` each hardcode their own literal
  `DB_URL`/`DB_USER`/`DB_PASS` and call `DriverManager.getConnection(...)` directly, bypassing
  `repo.AppConfig`/`repo.EnvConfig` entirely — a third and fourth independent hardcoded-connection
  path beyond the two already tracked in §4.3. Per the project's accepted policy the specific literal
  value is not a material security finding; the finding is the hardcoded/uncentralized pattern
  (`TARGET_ARCHITECTURE.md` §15 drift). Low risk, not a Phase 1 blocker.
- New §7.8: `api/Gw2PriceFetch.java` is now fully orphaned dead code (zero callers repository-wide,
  confirmed by search) left behind by `Main.java`'s deletion. Same category as the already-resolved
  §7.1. Low risk, not a Phase 1 blocker.

**Verification gap (not fixed — no new test added per this story's constraints):**
`parser.CharacterItemsParserTest` uses a hand-typed inline JSON string rather than a captured real
GW2 API payload, deviating from `TEST_STRATEGY.md` §31.3's Layer 3 requirement ("captured real
Guild Wars 2 API JSON responses... not hand-typed approximations"). This parser feeds the
`character_items`/`binding`/`bound_to` data that Phase 1's bound-material rule (§3.4, DQ-007)
depends on, unlike the already-correctly-covered recipe parser (`STORY-SYNC-002`,
`recipe_7319.json`, a real captured fixture). Production behavior for this rule is still exercised
through other Phase 1 evidence (`STORY-TEST-003`'s Layer 2 integration test, `STORY-DOM-011`/`012`'s
domain/repo tests, and `STORY-DOM-013`–`015`'s real-view TestFX checks), so this is a narrow,
non-blocking confidence gap specific to the parser layer itself, not an unverified domain rule.

### Unresolved items / candidates for later planning

- `docs/KNOWN_PROBLEMS.md` §2.2 (hardcoded DB literals in `BankView`/`MaterialsView`) — a small
  INFRA-area story when either view is next touched.
- `docs/KNOWN_PROBLEMS.md` §7.8 (orphaned `Gw2PriceFetch`) — trivial deletion, any future
  INFRA-area story.
- The `CharacterItemsParserTest` Layer 3 fixture gap above — a small TEST-area story adding a
  captured real character-inventory API payload, whenever character-sync parsing is next touched.
- `docs/KNOWN_PROBLEMS.md` §4.1 (domain layer coupled to repository types) and §4.4 (`Gw2App`/
  `EctoView` UI-layer wiring) remain open, but were already correctly scheduled for Phase 2/Phase 3
  respectively before this review — no change needed, not Phase 1 scope.

### Milestone criteria that remain open

None. Every Phase 1 exit criterion in `docs/ROADMAP.md` is satisfied with concrete evidence (see
above).

### Items that should be transferred to a later milestone

None new. The pre-existing Phase 2/Phase 3 transfers (§4.1, §4.4) were already correctly scheduled
and required no change. The three new items above are ordinary backlog-sized technical debt/test
gaps, not milestone-scoped transfers — any future milestone's INFRA/TEST area can pick them up
without a formal transfer.

### Phase 1 readiness

**Ready for normal planner completion assessment.** No blocking findings were identified. All Phase 1
exit criteria are satisfied with concrete evidence; the newly discovered items (hardcoded view-level
DB credentials, one orphaned dead class, one parser-layer fixture gap) are low-risk, narrowly scoped,
and do not contradict any Phase 1 exit criterion.

## Blockers

None.