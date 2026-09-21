## Story ID

STORY-DOC-001

## Title

Document the crafting calculation for readers and link development documentation

## Status

DONE

## Milestone

milestone-01

## Goal

Explain Phase 1's authoritative crafting behavior in a maintained human-readable guide and provide concise root README entry points for the guide and development process.

## Authoritative Source Documents / Sections

- Supplied Phase 1 objective: agreement with DOMAIN_SPEC.md; blocked-result and simulation-cap items.
- docs/DOMAIN_SPEC.md §2.2.1, §5–47 and decided domain questions.
- docs/TARGET_ARCHITECTURE.md §35 (guide ownership, readability exception and maintenance policy).
- Existing Phase 1 STORY-DOM-013, STORY-DOM-014 and STORY-DOM-015 (pending behavior, not proof of implementation).
- PO request: agent/product-owner-requests/document-development-process-and-crafting-calculation.md.

## Context

The guide is an explicitly authorized explanatory duplicate of authoritative rules, not a competing specification. Write against documented intended behavior and label known implementation gaps honestly while pending stories remain unfinished.

## Acceptance Criteria

- Create docs/crafting/README.md as the entry point, splitting into only a few Markdown files if useful. Explain the end-to-end Profit and Discovery flow to readers unfamiliar with GW2; include a compact glossary and/or FAQ.
- Cover recipe selection/unlocks, discipline/rating eligibility, consolidated Profit scopes versus Discovery character selection, coordinated crafting, transferable intermediates, soulbound/account-bound handling and account/bank/character inventory usage.
- Explain owned-material opportunity cost, buying missing ingredients, budgets, craft-versus-buy, instant/listing buy/sell price sources, feature-specific fee treatment, unavailable prices and visible blocked states, daily restrictions and discovery.
- Explain the intentional 250-craft limit and its effect on totals, craft count versus output quantity, profit per craft versus per output item and total profit, with internally consistent worked examples. Explain assumptions and why they matter; do not present hypothetical examples as executed results.
- Distinguish documented project choices from game mechanics and intended behavior from known implementation gaps. Link authoritative sources without forcing readers to consult them to understand the flow.
- Add only a short note and concise links near the top of root README.md to agent/README.md and the guide. Do not rewrite agent/README.md.
- Add a discoverable maintenance reminder to the appropriate existing development/documentation instructions, linking TARGET_ARCHITECTURE.md §35 rather than duplicating policy. Every material user-visible crafting rule/calculation change must update the guide.

## Required Tests

- Review each substantive explanation against authoritative documents and resolved decisions; check examples arithmetically and verify Markdown links/anchors and README destinations.
- Check guide coverage against the PO request, especially differing fee rules, binding restrictions, price modes, blocked results and simulation cap. No implementation test suite is required for documentation-only changes.

## Constraints

Markdown, readable prose, small file set, no invented mechanics or implementation claims. Source documents remain authoritative. Stay within Phase 1 behavior; no implementation or future-phase planning. The duplication exception applies to the human-readable guide only.

## Dependencies

None. Pending behavior may be explained as intended with explicit implementation gaps; subsequent behavior changes must maintain the guide under TARGET_ARCHITECTURE.md §35.

## Definition of Done

Guide and concise README links exist, policy reminder is discoverable, all coverage/link/example checks recorded, and no unsupported claims remain.

## Result

**What changed:**

- `docs/crafting/README.md` (new) — the guide entry point. Explains, for a reader unfamiliar with
  this codebase and only casually familiar with Guild Wars 2, the end-to-end Crafting Profit and
  Crafting Discovery flow: recipes/disciplines/rating (§5–8), recipe unlocks/knowledge (§6, §34),
  multi-recipe selection priority (§30/DQ-003), Profit's single Discipline scope selector (All /
  discipline / character+discipline) versus Discovery's separate individual character+discipline
  selectors (§2.2.1), coordinated multi-character plans and transferable intermediates (§2.2.1),
  account-bound/soulbound handling (§11.1), the combined bank/storage/character owned-material pool
  and its explicit "not the same as in-game physical access" caveat (§9/DQ-006, §10), owned-material
  opportunity cost (§11), buying and budgets (§18–19), craft-vs-buy by effective cost (§22/DQ-002),
  instant/listing buy/sell price semantics (§20), why Crafting Profit excludes the TP selling fee
  while Ectoplasm Salvage includes it (§25/DQ-001 vs §46–47), all seven `BlockedReason` values and
  why unavailable prices/blocked results stay visible instead of being hidden (§21, §41–42),
  daily-limited crafting and the project's explicit non-tracking of daily state (§31–33/DQ-005),
  Discovery's candidate rule and non-profit utility (§35, §37), the intentional 250-craft simulation
  cap (§28/UD-003), craft-operations vs. produced vs. requested quantity (§16–17), and profit per
  craft vs. profit per output item vs. total profit (§24–27). Each of these areas carries a worked,
  explicitly-labeled-as-illustrative example (see Required Tests below for arithmetic verification).
  A closing "Known implementation gaps" section links `docs/KNOWN_PROBLEMS.md` and calls out the one
  remaining user-visible gap (§7.4's direct-price heuristic skip) rather than presenting the
  calculation as flawless.
- `docs/crafting/GLOSSARY.md` (new) — compact glossary plus an FAQ answering every example question
  listed in the PO request (discipline, recipe unlock, soulbound vs. account-bound, multi-character
  crafting, why soulbound can't be pooled, "use own mats", opportunity cost, instant vs. listing
  buy/sell, why blocked results aren't just omitted, the 250 cap) plus three added for this guide's
  own coverage (Profit/Ecto fee difference, Discovery's per-character scope, daily-state tracking).
- `README.md` (root) — added one short blockquote near the top (after the intro paragraph, before
  the first `---`) linking to the AI-orchestration doc and the new crafting guide. No other root
  README content was touched, per the story's constraint not to rewrite it.
- `CLAUDE.md` — added `docs/crafting/` to the Documentation Ownership list (pointing to
  `TARGET_ARCHITECTURE.md` §35 rather than restating its policy) and one line to the Documentation
  After Implementation trigger list: "user-visible crafting rule/calculation change -> docs/crafting/
  guide (see docs/TARGET_ARCHITECTURE.md §35)". This is the discoverable maintenance reminder
  required by the acceptance criteria.

**Why:** `docs/TARGET_ARCHITECTURE.md` §35 and the PO request
(`agent/product-owner-requests/document-development-process-and-crafting-calculation.md`) both
require a maintained, human-readable explanation of Phase 1's crafting behavior so a reader doesn't
have to cross-reference `DOMAIN_SPEC.md`, multiple `UD-*` files and story Results to understand one
calculation, plus a discoverable reminder so future crafting-rule changes keep the guide in sync.

**One deviation, deliberately made, flagged rather than silently resolved:** the story's acceptance
criteria and `TARGET_ARCHITECTURE.md` §35 both say to link root `README.md` to `agent/README.md`,
but no file exists at that path — the actual AI-orchestration document in this repository is
`agent/agent_README_experimental.md` (confirmed via a repository-wide search: only the PO request,
this story file, and `TARGET_ARCHITECTURE.md` §35 reference the `agent/README.md` path; no file with
that name exists). Linking to the nonexistent path would have produced a dead link, which the
story's own required link verification would then fail. The root README note links to the file that
actually exists instead. Renaming `agent_README_experimental.md` to `README.md` was intentionally
not done — it is outside this story's scope (a documentation-guide story, not an agent-directory
restructuring), risks being an undesired rename of a file whose "experimental" naming may itself be
a deliberate signal, and `CLAUDE.md`'s "if a material ambiguity is unspecified... report it" is
satisfied by recording the discrepancy here rather than by silently renaming another area's file.

**Tests run:** No implementation test suite applies to this documentation-only change, per the
story's Required Tests and `CLAUDE.md`'s Testing section (domain behavior did not change). Instead:

- **Source-fidelity review:** every substantive claim in both new files was checked against
  `docs/DOMAIN_SPEC.md` (read in full), `docs/TARGET_ARCHITECTURE.md` §35 (read in full),
  `docs/KNOWN_PROBLEMS.md` (read in full, for the "known implementation gaps" section), the resolved
  `agent/user-decisions/UD-002` and `UD-004`, the DONE Results of `STORY-DOM-013`/`014`/`015`, and
  direct source reading of `craft/BlockedReason.java` (confirmed all seven non-`NONE` values:
  `NO_RECIPE`, `PRICE_UNAVAILABLE`, `BUYING_DISABLED`, `DAILY_LIMIT`, `CYCLE_DETECTED`,
  `RECIPE_NOT_ALLOWED`, `INSUFFICIENT_BUDGET`, each confirmed actually assigned somewhere in
  `craft/CraftingResolver.java`/`RecipeSimulator.java`/`CraftingResultPresentation.java` by a
  repository-wide search, not just declared), `craft/CraftResult.java`, and the `CraftingProfitView`
  settings controls (`useOwnMatsCheck`, `allowBuyCheck`, instant/listing buy/sell `RadioButton`s) to
  confirm the guide's control names match the real UI.
- **Arithmetic verification of every worked example** (all explicitly labeled illustrative, not
  measured application output, per the constraint against presenting hypotheticals as executed
  results): opportunity cost (10 × 1 silver = 10 silver); craft-vs-buy (reused `DOMAIN_SPEC.md`
  §22's own figures: craft effective 80c vs. buy effective 60c, buy wins); output quantity
  (`ceil(5/3) = 2` crafts, 6 produced, 1 excess against 5 requested); 250-craft cap (250 × 5c =
  1,250 copper = 12 silver 50 copper, using the project's 100-copper-per-silver unit from §3, not
  the mathematically-available 300 × 5c = 1,500c); profit-per-craft/per-item/total (2 Potions/craft
  × 20c = 40c revenue, 2 Herbs × 10c = 20c cost, 20c profit/craft, 10c profit/Potion, capped total
  250 × 20c = 5,000c = 50 silver).
- **Markdown link/anchor verification:** every `](...)` destination in both new files was extracted
  and checked — relative paths (`../DOMAIN_SPEC.md`, `../TARGET_ARCHITECTURE.md`,
  `../KNOWN_PROBLEMS.md`, `GLOSSARY.md`/`README.md`) resolve to files that exist; every `#anchor`
  link's target heading was manually reduced using GitHub's heading-slug rule (lowercase, strip
  punctuation not in `[a-z0-9 -]`, spaces to hyphens) and matched character-for-character against
  the corresponding heading text in `docs/crafting/README.md` — no mismatches found. The root
  `README.md` addition and its target files (`agent/agent_README_experimental.md`,
  `docs/crafting/README.md`) were confirmed to exist via directory listing.
- **PO-request coverage check:** every bullet in
  `agent/product-owner-requests/document-development-process-and-crafting-calculation.md`'s "should
  include" list and every example glossary/FAQ question is addressed by name in the guide or
  glossary (cross-checked line by line against the final files).

**Remaining uncertainty:**

- The `agent/README.md` vs. `agent/agent_README_experimental.md` naming discrepancy above is a
  pre-existing repository inconsistency, not something introduced or fully resolved by this story;
  if the file is ever renamed to `agent/README.md`, the root README link should be updated to match
  (a one-line follow-up, not a reason to block this story).
- Rendering of `#anchor` links was verified by manually applying GitHub's documented slug algorithm,
  not by rendering the Markdown in an actual viewer (no Markdown preview/render tool was available
  in this session); this is a lower-confidence verification method than a rendered check, though the
  algorithm is deterministic and was applied consistently to every heading referenced.
- Ecto Salvage's own calculation is intentionally summarized only where it contrasts with Profit's
  fee rule (per this story's scope: "Explain the end-to-end Profit and Discovery flow"); it is not
  covered end-to-end, consistent with the acceptance criteria's focus on Profit/Discovery.

## Blockers

None.
