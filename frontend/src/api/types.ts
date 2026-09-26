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
  dailyBuyInsteadOfCraft: boolean
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
 * Response body of `POST /api/crafting/profit/resolution` (`TARGET_ARCHITECTURE.md` 13.3).
 *
 * `recipeId` and `calculation` echo the *requested* identity and inputs, which is what the browser
 * associates the response with; they assert nothing about how the root requirement was sourced.
 * `row` and `tree` are one **fresh** calculation, not a retrieval of the table's earlier result.
 */
export interface CraftingProfitResolutionResponse {
  recipeId: number
  calculation: ResolutionCalculation
  /** Literal `FRESH_CALCULATION`; typed as a string because it is unvalidated transport data. */
  consistency: string
  calculatedAt: string
  row: CraftingRow
  /** `AVAILABLE` or `RESULT_UNAVAILABLE`. */
  treeStatus: string
  /** Literal `SINGLE_OUTPUT_REQUIREMENT`: one output batch, not every counted craft. */
  treeBasis: string
  /** Null only when `treeStatus` is `RESULT_UNAVAILABLE`; never a fabricated empty tree. */
  tree: ResolutionNode | null
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
