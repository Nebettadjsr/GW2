# Known problems

Reviewed against source on 17 November 2026. This register contains current defects, risks and concrete follow-ups. Remove resolved findings when current code and verification establish closure; GitHub retains their history. [DOMAIN_SPEC](DOMAIN_SPEC.md) owns behavior and [Architecture](ARCHITECTURE.md) owns structural constraints.

The findings and opportunities below do not authorize a product-policy change or restart
the retired capacity programme. Select any improvement through a bounded approved issue.

## KP-12 Mixed inventory generations across sources

**Observed:** account refresh is coordinated by a per-account single writer; each source publishes atomically, and Profit captures its inputs in one repeatable-read snapshot. Bank, Materials and character inventory can nevertheless have different source completion times. The upstream responses are not one atomic game-account snapshot.

**Risk:** a stack moved between stores can temporarily appear in both cached source generations until the corresponding sources refresh. Single-writer admission prevents the previously claimed concurrent same-account writer race; it does not make independently refreshed source snapshots coherent.

**Follow-up:** decide whether coherent cross-source publication is needed and preserve the accepted refresh scope and honest freshness reporting. Do not equate this finding with accepted upstream API lag.

Evidence: `application/ScopedAccountRefreshCoordinator.java`, `application/AccountRefreshService.java`, `application/ProfitInputSession.java`, `sync/AccountSync.java` and `repo/AccountSourcePublication.java`. Existing single-writer and repeatable-read tests verify those protections; they do not prove a coherent upstream instant.

## KP-16 File graph cache validation and corruption recovery

**Observed:** `repo.CraftingGraphCache.load()` reuses its in-memory graph, and otherwise reads an existing file without validating the stored source key or age. Invalid JSON is not automatically rebuilt. Rebuilds are synchronized and written through a temporary file with atomic replacement where supported. Snapshot-bound Profit reads use `loadForSnapshot(connection)` and bypass this file.

**Risk:** consumers of the shared file can repeatedly fail on corrupt derived data or retain a graph from a different source until invalidation/rebuild. This is not a claim that every Profit request reparses that file.

**Follow-up:** validate source identity and recover by rebuilding disposable derived data. Preserve synchronized publication and the existing pinned-snapshot path.

Evidence: `repo/CraftingGraphCache.java`, especially `load`, `loadForSnapshot`, `rebuild` and `invalidate`.

## KP-17 Incremental TP refresh retains quotes after confirmed absence

**Observed:** ordinary `sync.TpSync.fetchTpBatchParsed` skips a requested ID when the single-item 404 fallback returns null; an earlier stored quote remains. The owner full-catalog path uses `fetchTpBatchStrict`, which already writes `TpPrice.noData` for that absence. Profit reports quote age separately.

**Risk:** incremental refresh can leave an obsolete quote in storage after the upstream explicitly reports no quote. Freshness metadata alone does not persist that absence.

**Follow-up:** align ordinary refresh's confirmed-absence semantics with the documented quote contract, distinguishing absence from a transient upstream failure. Preserve the full-catalog behavior already implemented.

Evidence: `sync/TpSync.java` (`fetchTpBatchParsed`, `fetchTpBatchStrict`), `api/tp/TpPriceApi.java` (`fetchSingle`).

## KP-30 Broad graph and root work for narrow crafting scopes

**Observed:** snapshot-bound calculation loads the complete recipe graph and broad item/quote inputs. Coordinated evaluation receives the global recipe set even for a narrow visible scope; disallowed roots can be rejected early, so this does not imply every root receives a full recursive simulation. Candidate queries also read broad ingredient data before discipline filtering.

**Risk:** repeated graph construction, indexing and root iteration may do avoidable work. Shared dependency, alternative-recipe and valuation needs make filtering the entire graph to visible rows unsafe.

**Follow-up:** consider graph-generation reuse and separate evaluation roots from the complete dependency graph, with targeted equivalence checks when pursued. No new load-testing programme or measured production speedup is claimed.

Evidence: `repo/CraftingGraphCache.loadForSnapshot`, `application/CraftingProfitService`, `craft/CraftingPlanner`, `repo/RecipeRepository`.

## KP-32 Incremental recipe checks miss changes under unchanged IDs

**Observed:** `sync.RecipeSync.syncAllRecipesGlobalSafe()` skips detail fetches when recipe IDs match a complete local publication. In-place changes to a recipe's output, ingredients or disciplines are therefore not detected by that check. The separate owner full-catalog operation deliberately reloads all details even when IDs match.

**Risk:** the periodic membership check can leave changed details stale until another trigger downloads them.

**Follow-up:** retain the cheap membership check while defining an affordable policy for detecting same-ID edits. Do not claim the owner full operation has the same limitation.

Evidence: `sync/RecipeSync.java` (`syncAllRecipesGlobalSafe`, `syncAllRecipesFull`).

## KP-33 Recipe sync uses the legacy unpinned API profile

**Observed:** `sync.RecipeSync` requests `/v2/recipes` without a `v=` schema version. The default catalog omits some modern recipes (for example Jade recipe 13839, present under `v=2022-03-09T02:00:00.000Z` with typed Item/Currency ingredients), and `RecipeParser` reads only legacy `item_id` ingredients. The automatic owner API compatibility check reports this as attention (`KNOWN_COVERAGE_GAP`, shown as "Needs update" on the compact card), not as an upstream break.

**Risk:** recipes that exist only under newer schemas are invisible or have no readable ingredients. The check samples a small fixed public set; authenticated account endpoint payloads are not live-verified.

**Update (#372):** recipe sync now pins one schema version for catalog and details; typed Item/Currency requirements are stored apart (`recipe_non_item_requirements`). Structurally valid records with a known unsupported kind (`GuildUpgrade`, e.g. recipe 13324) are skipped in both the safe and the full sync, never published as free; unknown/malformed kinds still fail the full sync. No guild-upgrade acquisition planner exists. AutoLearned recipes (e.g. 13839) are known without unlock rows; level/discipline/account boundaries still apply and they are not Discovery candidates. Wallet balances join the calculation identity/freshness (WALLET source) only for requests with the wallet grant, using the configured account freshness policy; currencies consumed only after the first craft stay visible via the union of per-craft and total currency.

**Original follow-up:** a separately approved story must choose and migrate to an explicit supported schema version; the check never switches production versions itself. Currency valuation is owned by #372 (see DOMAIN_SPEC 2.1.0); production sync stays unpinned until that migration so Jade-class recipes are not yet synced. The parser and storage already accept the typed shape.

Evidence: `sync/RecipeSync.java`, `parser/RecipeParser.java`, `api/ApiCompatibilityChecker.java`.

## Further performance opportunities

- **Bound ordinary upstream waits and release database leases.** Authenticated
  `Gw2ApiClient.getAuth` lacks a per-request timeout; ordinary TP refresh uses
  `TaskDeadline.NONE`. `TpSync.syncTpPrices` keeps its connection across HTTP batches,
  and Profit holds its pinned connection during calculation. Bound waits and narrow
  lease ownership while preserving cancellation, publication and snapshot correctness.
  The full-catalog deadline already exists.
- **Reduce large-result DOM work.** `CraftingProfitTable.vue` renders every displayed row,
  including Show all; default results remain 250. Consider windowing while preserving
  sorting, filtering, selected detail, keyboard use and links. Transfer compression needs
  its own evidence and does not reduce DOM work.
- **Pause or share redundant status reads.** System Status avoids overlapping requests but
  still polls every five seconds in hidden tabs. Profit and the shell can separately poll
  the same task. Consider visibility pausing/coalescing without changing account epochs,
  ordering or cancellation. Inventory refresh already pauses while hidden/inactive.
- **Consider result reuse only after observing useful repetition.**
  `CachedCraftingProfitService` caches inputs, not results. A safe result key would need
  all settings, account/scope, catalog/quote generations and implementation identity,
  with bounded lifetime and memory. No useful hit rate or speedup is established.

## Other open follow-ups

- **Legacy branding images are packaged without a runtime caller.**
  `backend/src/main/resources/images` includes originals (for example the two
  `ChatGPT Image ...` PNGs and `favicon.zip`) that no runtime code references (inference
  from a source search, not a measured size finding). Originals are preserved; artifact
  validation of the packaged jar is needed before moving or excluding any file
  (see [#345](https://github.com/Nebettadjsr/GW2/issues/345)).
- **Trading Post warning wording overstates the API limit.**
  `TradingPostPriceDisclaimer.vue` says the GW2 API provides no order-book depth, but
  `commerce/listings` documents price levels with quantities. This tool uses best unit
  quotes from `commerce/prices` without quantity-aware execution. Correction is scoped in
  [#360](https://github.com/Nebettadjsr/GW2/issues/360); no new fetch or algorithm is requested.
- **One optional icon-tree smoke timeout ([#347](https://github.com/Nebettadjsr/GW2/issues/347)) has an unproven cause.** A single
  first-run timeout was followed by passing reruns. Stricter readiness checks and failure
  diagnostics were added; this is hardening, not a confirmed product defect or a proven
  fix. Treat it as a residual optional-check risk and do not restart repeated smoke
  investigation without new evidence.

Graph-generation reuse and narrower evaluation roots belong to KP-30 above. Actual
measurements stay in the relevant issue/CI evidence; these are opportunities, not claims
about production capacity or completed optimizations.
