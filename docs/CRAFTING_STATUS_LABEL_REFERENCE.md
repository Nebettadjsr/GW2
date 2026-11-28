# Crafting Status & Blocking Reasons — Developer Reference

A practical reference for understanding the status codes returned by the GW2 crafting planner. This document explains **what a code means, why it occurs, and how to interpret it** in Crafting Profit and Crafting Discovery. It is a developer guide, not a replacement for the authoritative domain rules.

**Authoritative specification:** [`DOMAIN_SPEC.md` §42](DOMAIN_SPEC.md) (blocking reasons), §2.1.1 (Profit presentation), §2.2.2 (Discovery), §44 (resolution tree)  
**Backend definitions:** [`craft/BlockedReason.java`](../backend/src/main/java/craft/BlockedReason.java), [`craft/ResolutionState.java`](../backend/src/main/java/craft/ResolutionState.java)  
**Frontend wording:** [`rowState.ts`](../frontend/src/crafting/rowState.ts), [`resolutionPresentation.ts`](../frontend/src/crafting/resolutionPresentation.ts)

## 1. Three different things to distinguish

- **`blockedReason`** describes *why a crafting path stopped or could not proceed*. It is a backend value, not a UI decision.
- **`resultAvailable`** tells us whether the backend supplied a calculated result at all. `false` is **not** the same as a calculated result of zero.
- **`craftableCount`** counts completed crafts for Crafting Profit. A blocking reason can describe why *further* crafting stopped **after** one or more crafts succeeded. Discovery instead evaluates **one discovery attempt per recipe**; it has no independent craftable-count concept in its UI.

For example, `craftableCount = 5` together with `blockedReason = BUYING_DISABLED` means five crafts were counted, but another craft could not be completed because a required purchase is disabled. It does **not** mean that the five completed crafts are invalid.

## 2. All thirteen `BlockedReason` values

The enum defines **thirteen values in total: `NONE` plus twelve reasons a path may stop**.

### `NONE` — No blocking reason

**Meaning:** The planner reported no reason preventing the attempted path from proceeding.

**Interpretation:** It does not, by itself, guarantee a positive craft count. Read `craftableCount` and `resultAvailable` separately. In Discovery, the absence of a blocking reason does not replace checking whether the one requested attempt actually resolved.

**UI:** Usually no badge is needed; avoid a redundant “Not blocked” label.

### `NO_RECIPE` — No usable recipe for a required item

**Meaning:** An ingredient or intermediate item cannot be obtained through an eligible recipe path.

**Typical scenario:** The selected craft needs an intermediate that is not already available in sufficient quantity, and the planner has no usable crafting recipe for the remaining amount. Whether another acquisition path (such as buying) can work depends on the current settings and available data.

**Developer check:** Inspect the affected node in the resolution tree. Determine which ingredient is missing and whether an eligible crafting or purchasing path exists; do not assume the *final* recipe is the problem.

**UI:** Use “Not craftable” with an item Wiki link only when no usable recipe path exists. If an eligible recipe was selected or attempted but resolution failed, show the missing sourcing quantities without claiming the item is uncraftable. Keep the code in backend/API diagnostics; crafting detail UI has no Technical details disclosure.

### `PRICE_UNAVAILABLE` — Required purchase has no usable price

**Meaning:** The planner needs a Trading Post purchase, but the necessary purchase quote is absent or unusable.

**Typical scenario:** The item needs to be bought, but the selected buy-price mode has no usable quote for it.

**Developer check:** Look at the affected item, the requested acquisition mode and the relevant TP price data. A missing price must remain *unknown*, never be converted to a purchase cost of `0c`.

**UI:** In normal detail presentation label the affected item “Not available on TP” and link its name to the GW2 Wiki. Keep this label independent of why its usable quote is absent. Retain the raw code in backend/API diagnostics.

### `BUYING_DISABLED` — A required purchase is forbidden by settings

**Meaning:** A required material cannot be obtained through purchasing because **Allow buying** is off, and other permitted acquisition paths did not satisfy the requirement.

**Typical scenario:** The account lacks the required quantity, available crafting cannot cover the remainder, and the option to buy is disabled.

**Developer check:** Check `allowBuying`, the quantity available from owned stock, and any usable crafting paths. This reason concerns the *unresolved remainder*; it does not invalidate ingredients already supplied.

**UI:** Do not expose this generic resolver label in normal details. Show actual sources and quantities, and retain the raw code in backend/API diagnostics.

### `DAILY_LIMIT` — Daily crafting allowance reached or unavailable

**Meaning:** A required crafting step or quantity cannot be produced under the applicable daily limit.

**Typical scenario:** The path needs more daily-gated output than the permitted daily crafting operations can provide. Owned materials that already exist remain usable independently of whether additional daily crafting is allowed.

**Developer check:** Inspect the daily-gated intermediate and the configured daily-crafting permission. The application does not track whether the player has already consumed today's in-game allowance (`DOMAIN_SPEC.md`, DQ-005); this code describes the planner's modeled restriction, not a verified account cooldown.

**UI:** Do not expose the generic daily-limit label in normal details. Preserve actual source quantities and the raw code in backend/API diagnostics; earlier completed crafts remain valid.

### `CYCLE_DETECTED` — Recursive recipe dependency loop

**Meaning:** While resolving a recipe, the planner reaches an item or recipe already active in the same dependency path, creating a loop.

**Typical scenario:** Recipe A needs an output from B; B needs C; C leads back to A. Continuing recursively would never finish.

**Developer check:** Inspect the dependency chain in the resolution tree and the alternative acquisition paths. This is a path-level failure, not necessarily proof that the item is impossible to obtain by every route.

**UI:** Do not expose “Recipe loop” in normal details or imply that one cyclic attempt proves no alternative path exists. Keep the raw code in backend/API diagnostics.

### `RECIPE_NOT_ALLOWED` — Recipe excluded by eligibility or allowed-set rules

**Meaning:** The planner rejected a recipe because it is not permitted by the active recipe restrictions or the applicable character's eligibility.

**Typical scenario:** A necessary intermediate would have to be crafted using a recipe outside the allowed set or one that the assigned character cannot currently use.

**Developer check:** Identify *which recipe* was rejected. In **Crafting Discovery**, the final target is intentionally unknown, while intermediate steps may use known usable recipes or unknown recipes that qualify as normal discovery candidates for the selected character.

**UI:** Do not expose “Recipe not allowed” in normal details. Where the requirement has no usable crafting path, use “Not craftable” with a Wiki link. If a path exists but did not resolve, show the missing quantities without claiming that no path exists. Raw codes remain in backend/API diagnostics.

### `INSUFFICIENT_BUDGET` — Required purchase exceeds the maximum buy setting

**Meaning:** The next required purchase exceeds the configured maximum-buy constraint.

**Typical scenario:** In Crafting Profit, enough materials cannot be obtained within **Max buy**. Crafts already counted remain valid; the limit applies to the attempted additional purchase.

**Developer check:** Compare the next required purchase with the configured budget and check which materials were already supplied. Do not confuse this with a negative-profit result: profit and available purchase budget are different constraints.

**Discovery:** The Discovery specification has **no cumulative purchase budget**. This reason should not normally arise from Discovery's one-attempt calculation; if it does, inspect the backend request and configuration before assuming it is a meaningful Discovery restriction.

**UI:** Do not show the generic budget reason as a normal detail status; preserve it in backend/API diagnostics and retain the backend-supplied quantities and amounts.

### `MATERIAL_LIMIT` — Candidate plan exceeds a material's demand bound

**Meaning:** The plan would require more than **2,500 units of one material item ID**. Count
repeated sibling and recursive ingredient requirements and every accepted Profit batch, whether
units come from stock, crafted intermediates, purchases or reused surplus. This is separate from
the 250-craft simulation cap and the table's changeable displayed-row limit.

**Developer check:** Inspect accumulated demand for that item across the attempted plan. Failed
speculative alternatives must roll back their demand and stock/cost effects; each candidate root
has independent accounting. A fitting alternative recipe or direct purchase may still succeed.
The saved Bank/Materials reservation target itself is not limited to 2,500.

**Interpretation:** An excessive attempt is rejected as a whole. Profit retains earlier complete
batches, while Discovery's single attempt cannot become a partial successful discovery.

**UI:** Keep the code in backend/API diagnostics. Do not present an enormous truncated shopping
list, invent a successful partial root or call a requirement uncraftable merely because this
particular plan crossed the bound.

### `VALUE_RANGE` — Quantity or money arithmetic is out of range

**Meaning:** A quantity, copper product/sum, revenue, fee or profit cannot be represented in the
calculation's supported range. Copper totals use the signed 32-bit range; calculations check
products and totals before accepting them rather than allowing integer wraparound.

**Developer check:** Reproduce the first excessive operation and its upstream inputs. Negative
purchase costs caused by overflow must never become profitable crafts. Reject and roll back the
affected attempt; do not clamp its values. A valid alternative may still fit, and earlier accepted
Profit batches remain valid when only the next batch exceeds the range.

**UI:** Preserve unavailable versus known-zero values and the backend reason. Do not turn the
guard into a fabricated negative cost, inflated profit, or a new normal row-state badge.

### `WORK_LIMIT` — The root's work budget was spent

**Meaning:** One root evaluation may make at most 50,000 craft-step attempts (`PlanState.MAX_WORK_UNITS`).
When it is spent, unevaluated alternatives remain, so the result is limited, never exact (DOMAIN_SPEC §42,
"Work budget").

**Developer check:** Look at how many alternatives, retries and rollbacks the root made. The root keeps its
fully accepted prefix of crafts and its actual output quote. With no accepted craft the cost, profit and total
fields are `null` (unknown), never `0`.

**UI:** The row is labelled "Incomplete" and says that at least N crafts were calculated and that this is not
a proven maximum, or that profit is unknown when no craft was accepted.

### `WALLET_UNAVAILABLE` — A required currency balance is unknown

**Meaning:** A recipe consumes a currency (for example Research Notes) and the account wallet is unknown: no
wallet permission, never published, or a failed refresh. Currency ids are not item ids.

**Developer check:** Check whether the request's session may read the wallet and whether the wallet was ever
published for the account. An unknown balance must never be treated as zero or as unlimited, and it must not
disable item-only workflows (DOMAIN_SPEC §2.1.0).

**UI:** Say that the currency balance is unknown. Do not show the requirement as unaffordable.

### `INSUFFICIENT_CURRENCY` — The known wallet cannot pay the currency requirement

**Meaning:** The wallet is known, but its balance together with any verified production source (such as the
Research Note production from Peer Reviewed Research) cannot pay the next craft's currency requirement. A fully
published wallet without a row for the currency is a known zero.

**Developer check:** Compare the node's currency sourcing (needed, from wallet, from production, shortfall) with
the captured balance.

**UI:** Show the shortfall in the currency itself, using the exact quantities supplied by the backend.

## 3. Result states that are not `BlockedReason` values

These cases matter when inspecting a row or diagnosing apparently misleading `0c` values:

| Supplied data | Meaning | What not to assume |
| --- | --- | --- |
| `resultAvailable = false` | Backend supplied no calculated result | Not equivalent to zero cost, zero profit or success |
| `resultAvailable = true`, `blockedReason = null` | Result exists but its blocking state was not reported | Not equivalent to `NONE` |
| `blockedReason = NONE`, `craftableCount = 0` | No reason was reported, but no completed craft was counted | Not evidence that the recipe is craftable |
| Unknown/future code | Backend sent a reason not recognized by this frontend | Do not silently map it to success, `NONE` or a known reason |

Keep numeric `null` values distinct from real zero values. This is especially important when a blocked result has incomplete economic data.

## 4. `ResolutionState`: four additional, requirement-level states

`craft.ResolutionState` is a **different enum**. These states describe individual requirements in the resolution tree and can coexist with successful sourcing methods (`INVENTORY`, `CRAFT`, `BUY`). They are **not** four more values of `BlockedReason`.

| State | Meaning | UI / developer interpretation |
| --- | --- | --- |
| `BLOCKED` | Some or all of this requirement could not be supplied | Keep the raw code in backend/API diagnostics; show actual source quantities |
| `PRICE_UNAVAILABLE` | A required purchase has no usable TP quote | Normal detail says “Not available on TP”; unknown price remains distinct from `0c` |
| `DAILY_LIMIT` | A requirement cannot be fully met under the modeled daily allowance | Keep the raw code in backend/API diagnostics; preserve completed-source quantities |
| `UNVALUED_NONTRADEABLE` | Owned non-tradeable material was assigned zero economic value because no crafting cost or vendor value could be established | Preserve the supplied `0c` valuation and raw code; do not call it acquired for free |

A node can also say **how** the supplied portion was obtained: `INVENTORY` (“From stock”), `CRAFT` (“Crafted”), or `BUY` (“Bought”). A requirement can have multiple acquisition methods, and a partially supplied node can still have a warning for the missing remainder.

## 5. How to read these codes in each page

**Crafting Profit:** The planner may count multiple crafts. Its row's blocking reason can describe why the **next** craft failed, even though earlier crafts succeeded. Most status badges are intentionally omitted from the normal table and selected-detail panel by `DOMAIN_SPEC.md` §2.1.1; the table keeps only a minimal diagnostic for a missing result, an unreported state, `CYCLE_DETECTED`, `PRICE_UNAVAILABLE` and `WORK_LIMIT` (the Profit table shows "Incomplete"). Item-specific problems belong in the resolution tree. Raw diagnostics remain in backend/API data.

**Crafting Discovery:** Each row represents **one attempt to discover a recipe by crafting it once**. One craft operation can produce multiple output items (`outputCount`). There is no Discovery-wide Max buy setting and no independent craftable-count control. The final discovery target and any prerequisite intermediate recipes must be distinguished when interpreting `RECIPE_NOT_ALLOWED`. Normal detail uses the same item-specific statuses as Profit, and the Discovery table shows the minimal diagnostics named above. Show actual source quantities; raw codes remain in backend/API data.

**Both pages:** A blocking reason diagnoses a restriction on a particular attempted path. Do not infer additional causes from a reason code alone. The resolution tree's “Not craftable” and “Not available on TP” labels are scoped to that item; each named tree item links to its Wiki article. These labels do not report a resolver's chosen acquisition path.

## 6. Where to look when debugging

| Question | File / section |
| --- | --- |
| Which blocking reasons can the backend return? | [`backend/src/main/java/craft/BlockedReason.java`](../backend/src/main/java/craft/BlockedReason.java) |
| What do those reasons mean by contract? | [`docs/DOMAIN_SPEC.md` §42](DOMAIN_SPEC.md) |
| Which requirement-level states exist? | [`backend/src/main/java/craft/ResolutionState.java`](../backend/src/main/java/craft/ResolutionState.java) |
| What text does a row-level reason generate? | [`frontend/src/crafting/rowState.ts`](../frontend/src/crafting/rowState.ts) |
| How do tree nodes get their labels? | [`frontend/src/crafting/resolutionPresentation.ts`](../frontend/src/crafting/resolutionPresentation.ts) |
| How is the Discovery detail assembled? | [`frontend/src/crafting/SelectedDiscoveryDetail.vue`](../frontend/src/crafting/SelectedDiscoveryDetail.vue) |
| How is the Profit detail assembled? | [`frontend/src/crafting/SelectedResultDetail.vue`](../frontend/src/crafting/SelectedResultDetail.vue) |
| What must Discovery calculate and display? | [`docs/DOMAIN_SPEC.md` §2.2.2](DOMAIN_SPEC.md) |

**Maintenance note:** If a new value is added to `BlockedReason` or `ResolutionState`, update this reference, `DOMAIN_SPEC.md` §42 if domain semantics change, and the frontend's wording and tests. Do not alter calculation rules just to simplify presentation.
