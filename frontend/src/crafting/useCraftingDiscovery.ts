import { computed, onScopeDispose, ref, shallowRef, type ComputedRef, type Ref } from 'vue'
import type { CraftingApi } from '@/api/craftingApi'
import type {
  CraftingDiscoveryRequest,
  CraftingDiscoveryResponse,
  CraftingRow,
  DiscoverySettingsRequest,
  EffectiveDiscoveryScope,
  EffectiveDiscoverySettings,
  SyncTaskAccepted,
  SyncTaskStatus
} from '@/api/types'
import {
  buildDiscoveryScopeOptions,
  type DiscoveryScopeOption
} from './discoveryScopeOptions'
import { describeFailure } from './useResolutionDetail'

/**
 * Screen state for Crafting Discovery: the selector facts, one selected character discipline,
 * settings, and the backend's answer for that selection.
 *
 * It decides nothing about crafting or discovery. Recipe eligibility, the account-wide
 * recipe-knowledge rule, the rating filter and the normal-discovery restriction are all the backend's
 * (`DOMAIN_SPEC.md` 34/35); this state only submits a scope and displays what comes back, negative
 * profits included (`DOMAIN_SPEC.md` 37). Every displayed number, state and default comes from a
 * response — the initial settings are read from the first answer's echo instead of being repeated
 * here, and they are Discovery's own defaults, not Profit's.
 *
 * Because the route has no default scope, an opened screen with no character discipline to offer sends
 * **no** request at all rather than an invalid one.
 */

/** Ports for the one keyed refresh Discovery may admit when a keyless read reports it needs one. */
export interface DiscoveryRefreshPorts {
  startAccountRefresh(): Promise<SyncTaskAccepted>
  readTaskStatus(statusUrl: string): Promise<SyncTaskStatus>
  hasKey(): boolean
  sleep?(milliseconds: number): Promise<void>
}

/** The refresh follow-up is bounded: at most this many status reads, one second apart. */
export const DISCOVERY_REFRESH_MAX_POLLS = 120
const DISCOVERY_POLL_INTERVAL_MS = 1_000

export interface CraftingDiscoveryState {
  scopeOptions: ComputedRef<DiscoveryScopeOption[]>
  selectedScopeId: Ref<string | null>
  settings: Ref<EffectiveDiscoverySettings | null>
  rows: Ref<readonly CraftingRow[]>
  effectiveScope: Ref<EffectiveDiscoveryScope | null>
  isLoading: Ref<boolean>
  hasResult: Ref<boolean>
  /** Changes whenever a calculation is applied, including a background refresh of the same scope. */
  resultVersion: Ref<number>
  requestError: Ref<string | null>
  selectorError: Ref<string | null>
  /** Whether a complete, valid scope can be submitted at all. */
  canCalculate: ComputedRef<boolean>
  /** Why account data could not be refreshed (usable cached rows stay); null when nothing is amiss. */
  refreshNotice: Ref<string | null>

  open(): Promise<void>
  reload(): Promise<void>
  selectScope(scopeOptionId: string): Promise<void>
  applySettings(settings: EffectiveDiscoverySettings): Promise<void>
}

export function useCraftingDiscovery(
  api: CraftingApi,
  refresh: DiscoveryRefreshPorts | null = null
): CraftingDiscoveryState {
  const scopeOptions = shallowRef<DiscoveryScopeOption[]>([])
  const selectedScopeId = ref<string | null>(null)
  const settings = ref<EffectiveDiscoverySettings | null>(null)
  const rows = shallowRef<readonly CraftingRow[]>([])
  const effectiveScope = ref<EffectiveDiscoveryScope | null>(null)
  const isLoading = ref(false)
  const hasResult = ref(false)
  const resultVersion = ref(0)
  const requestError = ref<string | null>(null)
  const selectorError = ref<string | null>(null)
  const refreshNotice = ref<string | null>(null)

  /**
   * Identifies the newest requested selection. A response whose id is no longer the newest belongs
   * to a superseded selection and is discarded, so a slow earlier answer can never overwrite the
   * current selection's results.
   */
  let newestRequestId = 0
  let disposed = false
  onScopeDispose(() => {
    disposed = true
    newestRequestId += 1
  })

  /** The currently selected character/discipline option, or null when no option is available. */
  const selectedScope = computed<DiscoveryScopeOption | null>(
    () => scopeOptions.value.find((option) => option.id === selectedScopeId.value) ?? null
  )

  const canCalculate = computed(() => selectedScope.value !== null)

  async function loadSelectorOptions(): Promise<void> {
    selectorError.value = null
    try {
      const loaded = await api.loadSelectorOptions()
      if (disposed) return
      const options = buildDiscoveryScopeOptions(loaded)
      scopeOptions.value = options
      // A selection the backend no longer offers falls back to the first entry it does offer; with no
      // entry at all the selection is emptied rather than left naming a character that is not there.
      if (!options.some((option) => option.id === selectedScopeId.value)) {
        selectedScopeId.value = options[0]?.id ?? null
      }
    } catch (cause) {
      if (disposed) return
      selectorError.value = describeFailure(cause)
    }
  }

  /** Keep the selected option when still available; otherwise use the first available character/discipline. */
  async function requestDiscovery(): Promise<void> {
    if (disposed) return
    const scope = selectedScope.value
    if (scope === null) {
      // Nothing valid to ask: the route requires a complete scope, so no request is sent and no
      // earlier answer is left standing as if it described the current (absent) selection.
      newestRequestId += 1
      rows.value = []
      hasResult.value = false
      isLoading.value = false
      requestError.value = null
      return
    }

    const requestId = ++newestRequestId
    isLoading.value = true
    requestError.value = null
    refreshNotice.value = null

    try {
      const response = await api.calculateDiscovery(currentRequest(scope))
      if (requestId !== newestRequestId) return
      apply(response)
      if (needsKeyedRefresh(response)) {
        isLoading.value = false
        await refreshThenRecalculate(requestId, scope)
      }
    } catch (cause) {
      if (requestId !== newestRequestId) return
      requestError.value = describeFailure(cause)
      // Cleared rather than kept: an earlier selection's rows are not this selection's answer.
      rows.value = []
      hasResult.value = false
    } finally {
      if (requestId === newestRequestId) isLoading.value = false
    }
  }

  /**
   * The keyless read reported account inputs it could not refresh. The usable rows are already on
   * screen; with a retained key the explicit account sync is admitted, its advertised task followed
   * for a bounded number of reads, and the calculation asked once more (still keyless). Any newer
   * selection ends the follow-up. Nothing is claimed fresh here: the recalculated answer says so.
   */
  async function refreshThenRecalculate(requestId: number, scope: DiscoveryScopeOption): Promise<void> {
    if (refresh === null || !refresh.hasKey()) {
      refreshNotice.value = 'Account data needs a refresh, but no GW2 API key is connected. Connect a key to refresh.'
      return
    }
    const sleep = refresh.sleep ?? ((ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms)))
    try {
      const accepted = await refresh.startAccountRefresh()
      if (requestId !== newestRequestId) return
      if (accepted.coversRequest === false) {
        refreshNotice.value = 'The account refresh is waiting behind another task and has not started. Reload shortly.'
        return
      }
      for (let polls = 0; polls < DISCOVERY_REFRESH_MAX_POLLS; polls += 1) {
        const status = await refresh.readTaskStatus(accepted.statusUrl)
        if (requestId !== newestRequestId) return
        if (status.state === 'FAILED') {
          refreshNotice.value = `The account refresh failed (${status.failure?.error ?? 'unknown'}). The cached result is kept; reload to retry.`
          return
        }
        if (status.state === 'SUCCEEDED') {
          const recalculated = await api.calculateDiscovery(currentRequest(scope))
          if (requestId !== newestRequestId) return
          apply(recalculated)
          if (needsKeyedRefresh(recalculated)) {
            refreshNotice.value = 'Account data still needs a refresh. Reload to try again.'
          }
          return
        }
        await sleep(DISCOVERY_POLL_INTERVAL_MS)
        if (requestId !== newestRequestId) return
      }
      refreshNotice.value = 'The account refresh did not finish in time. It may still be running; reload later.'
    } catch (cause) {
      if (requestId !== newestRequestId) return
      refreshNotice.value =
        `The account refresh could not be completed right now. The cached result and your key are kept; reload to retry. (${describeFailure(cause)})`
    }
  }

  function apply(response: CraftingDiscoveryResponse): void {
    resultVersion.value += 1
    effectiveScope.value = response.scope
    settings.value = response.settings
    // No usable result (HTTP 202 / PENDING): the inputs are still being synchronized. Never present
    // that as a completed calculation with an empty list.
    if (response.rows === null || response.rows === undefined) {
      rows.value = []
      hasResult.value = false
      requestError.value =
        'Account data for this character is still being synchronized, so nothing was calculated yet. Reload in a moment.'
      return
    }
    rows.value = response.rows
    hasResult.value = true
    requestError.value = null
  }

  /** The selected character/discipline is the only Discovery scope and inventory source. */
  function currentRequest(scope: DiscoveryScopeOption): CraftingDiscoveryRequest {
    const request: CraftingDiscoveryRequest = { scope: scope.request }
    if (settings.value !== null) request.settings = settingsRequestOf(settings.value)
    return request
  }

  return {
    scopeOptions: computed(() => scopeOptions.value),
    selectedScopeId,
    settings,
    rows,
    effectiveScope,
    isLoading,
    hasResult,
    resultVersion,
    requestError,
    selectorError,
    canCalculate,
    refreshNotice,

    /**
     * Opening the screen loads the selector facts first and only then calculates: the route needs a
     * character discipline, so the request cannot be issued in parallel the way Profit's default one
     * is. Nothing is sent when the selector supplied no character or failed.
     *
     * The selector read is part of the page's loading state. Until it has answered, whether there is
     * a character discipline at all is simply not known yet — reporting "no character" there would
     * state something the page has not established, so `requestDiscovery` is what clears this again.
     */
    async open(): Promise<void> {
      isLoading.value = true
      await loadSelectorOptions()
      await requestDiscovery()
    },

    /** Manual reload. Keeps the current scope and settings; search and sort too. */
    async reload(): Promise<void> {
      isLoading.value = true
      await loadSelectorOptions()
      await requestDiscovery()
    },

    async selectScope(scopeOptionId: string): Promise<void> {
      selectedScopeId.value = scopeOptionId
      await requestDiscovery()
    },

    async applySettings(next: EffectiveDiscoverySettings): Promise<void> {
      settings.value = next
      await requestDiscovery()
    }
  }
}

/**
 * The five settings the Discovery route accepts, taken from its own echo.
 *
 * `allowDailyCrafts` is dropped on purpose: Discovery fixes it and does not accept it as an
 * input, so echoing it back would send the route a field it does not have. No Profit-only setting is
 * reachable from here at all — this client cannot build one.
 */
export function settingsRequestOf(settings: EffectiveDiscoverySettings): DiscoverySettingsRequest {
  return {
    useOwnMats: settings.useOwnMats,
    allowBuying: settings.allowBuying,
    listingSell: settings.listingSell,
    listingBuy: settings.listingBuy
  }
}

/** The backend's own signal that a refresh is needed which this keyless read could not admit. */
function needsKeyedRefresh(response: CraftingDiscoveryResponse): boolean {
  return response.freshness?.refresh === 'CREDENTIAL_REQUIRED'
}
