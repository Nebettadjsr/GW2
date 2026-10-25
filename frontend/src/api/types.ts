/**
 * TypeScript shapes of the backend HTTP transport records the browser client uses
 * (`CURRENT_ARCHITECTURE.md` 5.5, 5.7–5.9 and 5.10).
 *
 * These describe transport data only. They are not a second implementation of a domain rule, and
 * they do not validate what the backend actually sent — static types never do (TARGET_ARCHITECTURE
 * 4.1). Every value here is produced by the backend and only displayed here.
 */

/** Raw trading-post quote pass-through; either side is null when the item is unquoted. */
export interface TradingPostQuote {
  buyUnitCopper: number | null
  sellUnitCopper: number | null
}

/** A material the calculation still needs. `itemName` is null when the item is not in the loaded set. */
export interface MissingItem {
  itemId: number
  itemName: string | null
  quantity: number
  price: TradingPostQuote | null
  /** Acquisition price selected by the calculation's settings; null when unavailable. */
  purchaseUnitPriceCopper: number | null
  /** Authoritative quantity times selected acquisition price; null when unavailable. */
  totalPurchaseCostCopper: number | null
  /** This application's image URL for the material, or null when there is no accepted source. */
  iconUrl: string | null
}

/**
 * One visible recipe and its calculation result.
 *
 * Every result-derived field is null when `resultAvailable` is false; the recipe is still reported
 * rather than dropped, and a null must never be displayed as zero (DOMAIN_SPEC 21).
 */
export interface CraftingRow {
  recipeId: number
  outputItemId: number
  /** Null when the output item is not in the backend's loaded item set (`web.CraftingRowMapper`). */
  outputName: string | null
  outputCount: number
  disciplines: string
  minRating: number
  resultAvailable: boolean
  craftableCount: number | null
  buyCostCopper: number | null
  matsSellValueCopper: number | null
  /** Total opportunity value of owned materials consumed across the counted crafts. */
  totalMatsSellValueCopper: number | null
  revenueCopper: number | null
  profitCopper: number | null
  /**
   * The domain's authoritative total sell value for `craftableCount` crafts: what everything those
   * crafts produce is worth on the Trading Post, output quantity already included and no selling fee
   * deducted (`DOMAIN_SPEC.md` 2.1.1/25). Displayed and sorted as supplied, never derived here from
   * `revenueCopper × craftableCount`.
   */
  totalSellValueCopper: number | null
  /** The domain's authoritative total profit. Displayed and sorted as supplied, never recomputed. */
  totalProfitCopper: number | null
  /** A `craft.BlockedReason` name; `NONE` when not blocked. */
  blockedReason: string | null
  outputPrice: TradingPostQuote | null
  missingToBuy: MissingItem[] | null
  missingToBuyOne: MissingItem[] | null
  /** Names of this recipe's direct ingredients; flat search metadata, not a resolution tree. */
  ingredientNames?: string[]
  /**
   * This application's image URL for the recipe's *output item* (a recipe has no icon of its own), or
   * null when the backend has no accepted source for it. Display metadata only: a missing icon says
   * nothing about craftability or any economic value.
   */
  iconUrl: string | null
}

/** The scope the backend actually calculated with, after it applied its own defaults. */
export interface EffectiveScope {
  kind: string
  discipline: string | null
  characterName: string | null
  rating: number
}

/** The settings the backend actually calculated with, after it applied its own defaults. */
export interface EffectiveSettings {
  useOwnMats: boolean
  allowBuying: boolean
  maxBuyCopper: number
  listingSell: boolean
  listingBuy: boolean
  allowDailyCrafts: boolean
}

/**
 * One account source the Crafting Profit calculation requires, as the backend captured it
 * (`web.dto.CalculationFreshnessDto.AccountSourceDto`).
 *
 * `state` is typed as a plain string because a received value is unvalidated transport data: a state
 * this client has no wording for must stay visible rather than read as freshness.
 */
export interface AccountSourceFreshness {
  source: string
  /** Last successful synchronization instant; null only when `state` is `MISSING`. */
  fetchedAt: string | null
  /** `FRESH` | `STALE` | `MISSING`. `STALE` still has usable data behind it. */
  state: string
}

/** Coverage of the feature-relevant tradeable item set by the backend's quote cache. */
export interface PriceCoverage {
  requiredItems: number
  freshItems: number
  staleItems: number
  /** Required items with no stored quote at all; never reported as covered. */
  missingItems: number
  oldestFetchedAt: string | null
  newestFetchedAt: string | null
  /** True only when nothing in the required set is stale or missing. */
  covered: boolean
}

/**
 * Refresh work the backend knows to be unfinished for the inputs it answered from.
 *
 * `taskStatusUrl` is the location the backend advertised for this task; the browser polls that and
 * never rebuilds a task path of its own. A null `taskId` means no task could be admitted.
 */
export interface OutstandingRefresh {
  /** `ACCOUNT` | `TRADING_POST`; the two mechanisms are coordinated separately. */
  kind: string
  /** The account sources this work is expected to refresh; empty for Trading Post work. */
  sources: string[]
  taskId: string | null
  /** The state the task-status route last reported, or `UNKNOWN`/`NONE`. */
  taskState: string | null
  taskStatusUrl: string | null
  /**
   * How this work relates to work already running — including `WAITING_FOR_UNRELATED_TASK`, which
   * means the named task is *not* refreshing these sources.
   */
  reuse: string | null
}

/** Bounded recovery information for refresh work that failed or whose task is no longer known. */
export interface RefreshFailure {
  kind: string
  taskId: string | null
  taskStatusUrl: string | null
  code: string
  message: string
  /** Whether requesting the calculation again can reasonably admit the work again. */
  retryable: boolean
}

/**
 * What a Crafting Profit response says about the inputs its calculation used
 * (`TARGET_ARCHITECTURE.md` 9.1, `web.dto.CalculationFreshnessDto`).
 *
 * Nothing here is a result. `provisional` describes the *inputs*, not the correctness of the
 * numbers: a provisional result is the ordinary authoritative calculation over the last
 * successfully synchronized inputs. The browser displays these facts, polls what they advertise and
 * asks again — it never infers freshness of its own and never recalculates anything.
 */
export interface CalculationFreshness {
  /** Correlates the response with the request that produced it. */
  requestId: string
  /** Identity of the input generation the calculation used; equal across a row and its detail. */
  calculationId: string
  evaluatedAt: string
  /** True while the inputs are not confirmed current; a succeeded task alone never makes it false. */
  provisional: boolean
  /** False only on the pending response, which carries no rows. */
  resultAvailable: boolean
  accountSources: AccountSourceFreshness[]
  tradingPost: PriceCoverage
  outstandingWork: OutstandingRefresh[]
  failures: RefreshFailure[]
}

/** A completed Crafting Profit calculation over usable cached inputs (HTTP 200). */
export const PROFIT_STATUS_CALCULATED = 'CALCULATED'

/** No usable synchronized inputs existed, so the response carries no rows (HTTP 202). */
export const PROFIT_STATUS_PENDING = 'PENDING'

/**
 * Response body of `POST /api/crafting/profit` (`web.dto.CraftingProfitResponse`).
 *
 * One shape serves both outcomes of the cached-result contract: `rowCount` and `rows` are present
 * for `CALCULATED` and absent for `PENDING`, which is deliberately not an empty result.
 */
export interface CraftingProfitResponse {
  /** `CALCULATED` | `PENDING`; typed as a string because it is unvalidated transport data. */
  status: string
  scope: EffectiveScope
  settings: EffectiveSettings
  /** Null when `PENDING`. */
  rowCount: number | null
  /** Null when `PENDING` — never a fabricated empty list. */
  rows: CraftingRow[] | null
  freshness: CalculationFreshness
}

/** Requested scope. Omitted fields fall back to the backend's documented defaults. */
export interface ScopeRequest {
  kind: string
  discipline?: string
  characterName?: string
  rating?: number
}

/** Request body of `POST /api/crafting/profit`. An empty body requests the documented defaults. */
export interface CraftingProfitRequest {
  scope?: ScopeRequest
  settings?: EffectiveSettings
}

/**
 * One requirement in a resolution tree (`TARGET_ARCHITECTURE.md` 13.3, `web.dto.ResolutionNodeDto`).
 *
 * Resolver fields are authoritative backend facts. The Discovery-only recipeKnowledge field comes
 * from explicit account-known and eligible-discovery sets. The browser displays these fields and
 * nothing else: it does not add the three cost fields up (they already include everything below the
 * node), does not repair or infer a quantity, does not merge two occurrences of the same item and
 * does not read a state out of a price. `recipeId` is the recipe *actually* selected or attempted
 * here — null when no crafting path was selected — and is unrelated to the requested recipe on the
 * envelope, which may differ.
 *
 * The enum-valued fields are typed as plain strings because a received value is transport data this
 * client does not validate; a code it has no wording for must stay visible rather than read as
 * success.
 */
export interface ResolutionNode {
  itemId: number
  /** Null when this operation captured no name; display may fall back to the ID. */
  itemName: string | null
  requestedQuantity: number
  inventoryQuantity: number
  /** Units of this requirement covered by crafting — not the surplus a batch may also produce. */
  craftedQuantity: number
  boughtQuantity: number
  missingQuantity: number
  /** The recipe actually selected or attempted, or null when no crafting path was selected. */
  recipeId: number | null
  craftCount: number
  /** Can exceed `craftedQuantity`, because a recipe produces a whole batch. */
  producedQuantity: number
  characterName: string | null
  /** `craft.AcquisitionMethod` names that actually contributed; several for split sourcing. */
  methods: string[]
  /** `craft.ResolutionState` names that apply; these may coexist with methods. */
  states: string[]
  /** `craft.BlockedReason` names; empty when this requirement is not blocked. */
  blockedReasons: string[]
  /** Inclusive of descendants. Null means no complete value could be established, never zero. */
  cashCostCopper: number | null
  opportunityCostCopper: number | null
  effectiveCostCopper: number | null
  /** In the resolver's own order; a repeated item stays a separate occurrence. */
  children: ResolutionNode[]
  /**
   * This application's image URL for *this node's own item* - not the requested recipe's output, and
   * not derived from how the requirement was sourced - or null when there is no accepted source.
   */
  iconUrl: string | null
  /** Discovery only: present only for a crafted recipe proven known or eligible to discover. */
  recipeKnowledge?: 'KNOWN' | 'TO_DISCOVER' | null
}

/** The effective inputs a resolution response echoes back, in the table response's own shape. */
export interface ResolutionCalculation {
  scope: EffectiveScope
  settings: EffectiveSettings
}

/**
 * Everything a completed fresh-detail response carries *apart from* its echoed calculation
 * (`TARGET_ARCHITECTURE.md` 13.3).
 *
 * The Profit and Discovery routes share this envelope field for field — identical names, literals and
 * meanings — and differ only in the shape of `calculation`, which is each route's own table request
 * contract (`web.dto.CraftingDiscoveryResolutionResponse`). Presentation that renders the row and the
 * tree therefore takes this type and works for either route, while the association rules of 13.4,
 * which must compare the echoed inputs, stay with the route-specific response type below.
 */
export interface ResolutionDetailView {
  recipeId: number
  /**
   * `FRESH_CALCULATION` or, on the Profit route, `RECALCULATED_FOR_CHANGED_INPUTS`; typed as a
   * string because it is unvalidated transport data.
   */
  consistency: string
  calculatedAt: string
  /**
   * The recipe's row, recalculated in this operation and matching this response's own `tree`. Null
   * only when the route answered `PENDING` — no usable synchronized inputs existed, so nothing was
   * calculated and no row is fabricated.
   */
  row: CraftingRow | null
  /** `AVAILABLE` or `RESULT_UNAVAILABLE`. */
  treeStatus: string
  /** Profit reports the selected result output quantity; Discovery may report one output batch. */
  treeBasis: string
  /** Null only when `treeStatus` is `RESULT_UNAVAILABLE`; never a fabricated empty tree. */
  tree: ResolutionNode | null
}

/**
 * Response body of `POST /api/crafting/profit/resolution` (`TARGET_ARCHITECTURE.md` 13.3).
 *
 * `recipeId` and `calculation` echo the *requested* identity and inputs, which is what the browser
 * associates the response with; they assert nothing about how the root requirement was sourced.
 * `row` and `tree` are one **fresh** calculation, not a retrieval of the table's earlier result.
 */
export interface CraftingProfitResolutionResponse extends ResolutionDetailView {
  calculation: ResolutionCalculation
  /** `CALCULATED` | `PENDING`, in the Profit table route's own literals. */
  status?: string
  /** The inputs this detail was calculated from, in the table route's shape. */
  freshness?: CalculationFreshness
}

/** The detail still explains the input generation the caller's row came from. */
export const CONSISTENCY_FRESH_CALCULATION = 'FRESH_CALCULATION'

/**
 * The inputs changed since the caller's row, so this response is a complete recalculation and its
 * own `row` — not the caller's older one — is what its `tree` explains.
 */
export const CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS = 'RECALCULATED_FOR_CHANGED_INPUTS'

/**
 * Request body of `POST /api/crafting/profit/resolution` (`TARGET_ARCHITECTURE.md` 13.1).
 *
 * `calculation` is the existing table request contract, so the browser copies the effective scope
 * and settings the table response echoed rather than relying on defaults a second time. Row numbers,
 * prices and material maps are deliberately not part of it.
 *
 * `basedOnCalculationId` is the input generation the displayed table result reported. It lets the
 * backend say whether this detail still explains that generation (`consistency`) instead of leaving
 * the browser to present a newer explanation of an older row.
 */
export interface CraftingProfitResolutionRequest {
  recipeId: number
  calculation: CraftingProfitRequest
  basedOnCalculationId?: string
}

/* ------------------------------------------------------------------ Crafting Discovery ----------- */

/**
 * Requested Discovery scope (`CURRENT_ARCHITECTURE.md` 5.6, `web.dto.CraftingDiscoveryRequest`).
 *
 * All three members are required and none of them is optional here: Discovery has no All reading and
 * no default scope, and `rating` drives the `recipe.minRating <= rating` filter, so the backend
 * refuses a request that omits it rather than answering with an emptier result. Every value is a fact
 * the selector route supplied — this client neither invents a rating nor offers an All entry.
 */
export interface DiscoveryScopeRequest {
  discipline: string
  characterName: string
  rating: number
}

/**
 * Requested Discovery settings. Exactly the five fields the route accepts.
 *
 * `allowDailyCrafts` is deliberately absent: the Discovery flow fixes it, and the route maps
 * that contract rather than accepting it as an input.
 */
export interface DiscoverySettingsRequest {
  useOwnMats: boolean
  allowBuying: boolean
  listingSell: boolean
  listingBuy: boolean
}

/** The Discovery scope the backend actually calculated with. */
export interface EffectiveDiscoveryScope {
  discipline: string
  characterName: string
  rating: number
}

/**
 * The Discovery settings the backend actually calculated with, after it applied its own defaults —
 * which are not Profit's (`CURRENT_ARCHITECTURE.md` 5.6).
 *
 * `allowDailyCrafts` is reported for completeness even though it is not a request field; it is
 * the value Discovery fixed, not one this client chose or may change.
 */
export interface EffectiveDiscoverySettings {
  useOwnMats: boolean
  allowBuying: boolean
  listingSell: boolean
  listingBuy: boolean
  allowDailyCrafts: boolean
}

/**
 * Request body of `POST /api/crafting/discovery`. Unlike Profit's, the body and its scope are
 * required; omitted settings fields ask the backend for its own documented Discovery defaults.
 *
 * The scope's character is used for both crafting eligibility and owned-material inventory.
 */
export interface CraftingDiscoveryRequest {
  scope: DiscoveryScopeRequest
  settings?: DiscoverySettingsRequest
}

/** Response body of `POST /api/crafting/discovery`. Rows are the same shape as Profit's. */
export interface CraftingDiscoveryResponse {
  scope: EffectiveDiscoveryScope
  settings: EffectiveDiscoverySettings
  rowCount: number
  /** Null on HTTP 202: the account's inputs are missing, so nothing was calculated. */
  rows: CraftingRow[] | null
  /** `PENDING` (no usable result) or `STALE` (usable result, refresh outstanding). */
  freshness?: { status: string; sources?: string[]; refresh?: string; taskId?: string } | null
}

/** Compact response from the cached static ingredient-dependency search. */
export interface CraftingIngredientSearchResponse {
  recipeIds: number[]
}

/** The effective inputs a Discovery resolution echoes back, in the Discovery table's own shape. */
export interface DiscoveryResolutionCalculation {
  scope: EffectiveDiscoveryScope
  settings: EffectiveDiscoverySettings
}

/**
 * Response body of `POST /api/crafting/discovery/resolution` (`TARGET_ARCHITECTURE.md` 13.3).
 *
 * The same envelope as the Profit route's, differing only in the echoed `calculation` — Discovery's
 * own one-character scope.
 */
export interface CraftingDiscoveryResolutionResponse extends ResolutionDetailView {
  calculation: DiscoveryResolutionCalculation
}

/**
 * Request body of `POST /api/crafting/discovery/resolution` (`TARGET_ARCHITECTURE.md` 13.1).
 *
 * `calculation` is the Discovery table request contract, so the browser sends back the effective
 * inputs that table response echoed rather than relying on defaults a second time.
 */
export interface CraftingDiscoveryResolutionRequest {
  recipeId: number
  calculation: CraftingDiscoveryRequest
}

/** The backend error code for a recipe absent from the fresh visible candidate set (13.4). */
export const RECIPE_NOT_IN_CALCULATION = 'RECIPE_NOT_IN_CALCULATION'

/** One synced character discipline offered by `GET /api/crafting/selector-options`. */
export interface CharacterOption {
  characterName: string
  discipline: string
  rating: number
  active: boolean
}

/** Response body of `GET /api/crafting/selector-options`. */
export interface SelectorOptions {
  defaultScopeKind: string
  disciplines: string[]
  characterOptionCount: number
  characterOptions: CharacterOption[]
  /**
   * Set by this client when the backend answered 202: the account's characters were never
   * synchronized. The list is then empty because the data is missing, not because there are none.
   */
  pending?: boolean
}

/**
 * One account bank slot of `GET /api/account/bank` (`CURRENT_ARCHITECTURE.md` 5.12).
 *
 * An empty slot is kept in place with `itemId` and `count` both null; that is the backend's
 * empty-slot representation and neither null may be read as item id `0` or an owned count of `0`.
 * `iconUrl` is this application's own image URL for the slot's item
 * (`TARGET_ARCHITECTURE.md` 12.1), or null when there is no item, no retained metadata, or metadata
 * the backend's canonical-source policy rejects. It is served by the backend from its persistent
 * cache; it is never an upstream URL and never a backend filesystem path.
 */
export interface BankSlot {
  slot: number
  itemId: number | null
  count: number | null
  iconUrl: string | null
  rarity: string | null
}

/** Response body of `GET /api/account/bank`. `slots` is in the backend's slot order. */
export interface BankContents {
  slotCount: number
  slots: BankSlot[]
}

/**
 * One official material-storage position, including unowned positions.
 */
export interface MaterialStack {
  position: number
  itemId: number
  count: number
  iconUrl: string | null
  rarity: string | null
}

/** One official material category in game display order. */
export interface MaterialCategory {
  category: number
  name: string
  order: number
  materials: MaterialStack[]
}

/** Response body of `GET /api/account/materials`. `categories` is in the backend's order. */
export interface MaterialStorage {
  categoryCount: number
  categories: MaterialCategory[]
}

/** Uniform backend error body. */
export interface ApiErrorBody {
  error: string
  message: string
  taskStatusUrl?: string | null
  staleSources?: string[] | null
}

/**
 * Acceptance body of the three synchronization triggers (`CURRENT_ARCHITECTURE.md` 5.7–5.9).
 *
 * Returned with HTTP 202 and carrying no outcome of any kind: it says the work was accepted, not
 * that it ran. The outcome is only ever readable from the status resource `statusUrl` names.
 */
export interface SyncTaskAccepted {
  taskId: string
  operation: string
  /** Path of this task's status resource, as advertised by the backend rather than rebuilt here. */
  statusUrl: string
  /** How the request was admitted or joined; informational. */
  admission?: string
  /** False when the referenced task is a narrower earlier run: its success is not this refresh. */
  coversRequest?: boolean
  retryAfterSeconds?: number
}

/** Response body of `GET /api/sync/tasks/{taskId}` (`CURRENT_ARCHITECTURE.md` 5.7). */
export interface SyncTaskStatus {
  taskId: string
  operation: string
  /**
   * `PENDING` | `RUNNING` | `SUCCEEDED` | `FAILED`. The sole carrier of the outcome — a known
   * identifier is HTTP 200 whatever its state, so the HTTP status never means the task succeeded.
   * Typed as a string because a received value is transport data this client does not validate.
   */
  state: string
  submittedAt: string
  /** Null while `PENDING`. */
  startedAt: string | null
  /** Null unless the task reached a terminal state. */
  finishedAt: string | null
  /** The backend's sanitized failure code and message; null unless the state is `FAILED`. */
  failure: ApiErrorBody | null
}

/** Request value of `POST /api/prices/refresh`. The backend supports exactly these two variants. */
export type PriceRefreshVariant = 'PROFIT' | 'DISCOVERY'
