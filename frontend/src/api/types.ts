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

/** Response body of `POST /api/crafting/profit`. */
export interface CraftingProfitResponse {
  scope: EffectiveScope
  settings: EffectiveSettings
  rowCount: number
  rows: CraftingRow[]
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
 * Every field is a fact the backend's authoritative resolver produced. The browser displays them and
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
  /** Literal `FRESH_CALCULATION`; typed as a string because it is unvalidated transport data. */
  consistency: string
  calculatedAt: string
  row: CraftingRow
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
}

/**
 * Request body of `POST /api/crafting/profit/resolution` (`TARGET_ARCHITECTURE.md` 13.1).
 *
 * `calculation` is the existing table request contract, so the browser copies the effective scope
 * and settings the table response echoed rather than relying on defaults a second time. Row numbers,
 * prices, material maps and any prior context identifier are deliberately not part of it.
 */
export interface CraftingProfitResolutionRequest {
  recipeId: number
  calculation: CraftingProfitRequest
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
  maxBuyCopper: number
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
  maxBuyCopper: number
  listingSell: boolean
  listingBuy: boolean
  allowDailyCrafts: boolean
}

/**
 * Request body of `POST /api/crafting/discovery`. Unlike Profit's, the body and its scope are
 * required; omitted settings fields ask the backend for its own documented Discovery defaults.
 *
 * `inventoryCharacterName` is the separate, independent input for the character whose owned inventory
 * the calculation may consume. Omitting it reaches the service as null, which keeps its own
 * unfiltered-pool fallback; this client never substitutes a character for it.
 */
export interface CraftingDiscoveryRequest {
  scope: DiscoveryScopeRequest
  inventoryCharacterName?: string
  settings?: DiscoverySettingsRequest
}

/** Response body of `POST /api/crafting/discovery`. Rows are the same shape as Profit's. */
export interface CraftingDiscoveryResponse {
  scope: EffectiveDiscoveryScope
  /** The character whose owned inventory was used; null when the unfiltered pool was. */
  inventoryCharacterName: string | null
  settings: EffectiveDiscoverySettings
  rowCount: number
  rows: CraftingRow[]
}

/** The effective inputs a Discovery resolution echoes back, in the Discovery table's own shape. */
export interface DiscoveryResolutionCalculation {
  scope: EffectiveDiscoveryScope
  inventoryCharacterName: string | null
  settings: EffectiveDiscoverySettings
}

/**
 * Response body of `POST /api/crafting/discovery/resolution` (`TARGET_ARCHITECTURE.md` 13.3).
 *
 * The same envelope as the Profit route's, differing only in the echoed `calculation` — Discovery's
 * own, including its nullable inventory character.
 */
export interface CraftingDiscoveryResolutionResponse extends ResolutionDetailView {
  calculation: DiscoveryResolutionCalculation
}

/**
 * Request body of `POST /api/crafting/discovery/resolution` (`TARGET_ARCHITECTURE.md` 13.1).
 *
 * `calculation` is the Discovery table request contract, so the browser sends back the effective
 * inputs that table response echoed — the nullable inventory character included — rather than relying
 * on defaults a second time.
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
 * One non-empty material stack of `GET /api/account/materials` (`CURRENT_ARCHITECTURE.md` 5.12).
 *
 * `category` is the numeric id the backend grouped the stack by — the only way to see which id
 * produced a fallback category label. `iconUrl` is again this application's own image URL, or null.
 */
export interface MaterialStack {
  category: number
  itemId: number | null
  count: number
  iconUrl: string | null
  rarity: string | null
}

/** One material category as the backend grouped and labelled it; `name` may be its fallback label. */
export interface MaterialCategory {
  name: string
  materials: MaterialStack[]
}

/** Response body of `GET /api/account/materials`. `categories` is in the backend's order. */
export interface MaterialStorage {
  categoryCount: number
  categories: MaterialCategory[]
}

/**
 * One Ecto-buy / Dust-sell scenario of `GET /api/ecto/salvage` (`CURRENT_ARCHITECTURE.md` 5.15).
 *
 * Gross values and fee-inclusive economic results are separate fields on purpose (DOMAIN_SPEC 25,
 * 46): the first three carry no fee and are the ones to show as prices — the third is what the
 * expected Dust yield of one Ectoplasm is worth at that gross quote — while the rest are results the
 * domain produced with the selling fee already applied once. Nothing here may be derived from
 * anything else here — the browser displays these, it does not relate them.
 */
export interface EctoSalvageScenario {
  ectoAcquisitionCostCopper: number
  dustGrossUnitPriceCopper: number
  expectedGrossRecoveredDustValueCopper: number
  netValueOfRecoveredDustCopper: number
  netCostPerEctoCopper: number
  profitPerEctoCopper: number
  costPer1000LuckCopper: number
}

/**
 * The salvage parameters the backend calculated with. They are expected values, not guaranteed
 * drops (DOMAIN_SPEC 45, 47), and are reported whether or not a price snapshot was available.
 */
export interface EctoSalvageAssumptions {
  expectedLuckPerEcto: number
  expectedDustPerEcto: number
  ectosPer1000Luck: number
  /**
   * The selling fee already deducted, once, from the expected gross recovered Dust value, stated by
   * the backend.
   */
  tradingPostSellFeePercent: number
}

/**
 * Response body of `GET /api/ecto/salvage`.
 *
 * `resultAvailable` false is a completed calculation the Trading Post had no usable quotes for: all
 * four scenarios are then null, and a null must never be displayed as zero (DOMAIN_SPEC 21).
 */
export interface EctoSalvage {
  resultAvailable: boolean
  ectoItemId: number
  dustItemId: number
  assumptions: EctoSalvageAssumptions
  instantBuyInstantSell: EctoSalvageScenario | null
  instantBuyListingSell: EctoSalvageScenario | null
  listingBuyInstantSell: EctoSalvageScenario | null
  listingBuyListingSell: EctoSalvageScenario | null
}

/** Uniform backend error body. */
export interface ApiErrorBody {
  error: string
  message: string
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
