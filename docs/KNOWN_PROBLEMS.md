# Known problems

Reviewed against source on 17 November 2026. This register contains current defects, risks and concrete follow-ups. Remove resolved findings when current code and verification establish closure; GitHub retains their history. [DOMAIN_SPEC](DOMAIN_SPEC.md) owns behavior and [Architecture](ARCHITECTURE.md) owns structural constraints.

There are five remaining findings. Empty legacy categories and resolved JavaFX commentary have been removed. None of these findings is an authorization to change product policy or restart the retired capacity programme.

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
