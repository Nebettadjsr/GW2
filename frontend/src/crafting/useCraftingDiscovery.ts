import { computed, ref, shallowRef, type ComputedRef, type Ref } from 'vue'
import type { CraftingApi } from '@/api/craftingApi'
import type {
  CraftingDiscoveryRequest,
  CraftingDiscoveryResponse,
  CraftingRow,
  DiscoverySettingsRequest,
  EffectiveDiscoveryScope,
  EffectiveDiscoverySettings
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
export interface CraftingDiscoveryState {
  scopeOptions: ComputedRef<DiscoveryScopeOption[]>
  selectedScopeId: Ref<string | null>
  settings: Ref<EffectiveDiscoverySettings | null>
  rows: Ref<readonly CraftingRow[]>
  effectiveScope: Ref<EffectiveDiscoveryScope | null>
  isLoading: Ref<boolean>
  hasResult: Ref<boolean>
  requestError: Ref<string | null>
  selectorError: Ref<string | null>
  /** Whether a complete, valid scope can be submitted at all. */
  canCalculate: ComputedRef<boolean>

  open(): Promise<void>
  reload(): Promise<void>
  selectScope(scopeOptionId: string): Promise<void>
  applySettings(settings: EffectiveDiscoverySettings): Promise<void>
}

export function useCraftingDiscovery(api: CraftingApi): CraftingDiscoveryState {
  const scopeOptions = shallowRef<DiscoveryScopeOption[]>([])
  const selectedScopeId = ref<string | null>(null)
  const settings = ref<EffectiveDiscoverySettings | null>(null)
  const rows = shallowRef<readonly CraftingRow[]>([])
  const effectiveScope = ref<EffectiveDiscoveryScope | null>(null)
  const isLoading = ref(false)
  const hasResult = ref(false)
  const requestError = ref<string | null>(null)
  const selectorError = ref<string | null>(null)

  /**
   * Identifies the newest requested selection. A response whose id is no longer the newest belongs
   * to a superseded selection and is discarded, so a slow earlier answer can never overwrite the
   * current selection's results.
   */
  let newestRequestId = 0

  /** The currently selected character/discipline option, or null when no option is available. */
  const selectedScope = computed<DiscoveryScopeOption | null>(
    () => scopeOptions.value.find((option) => option.id === selectedScopeId.value) ?? null
  )

  const canCalculate = computed(() => selectedScope.value !== null)

  async function loadSelectorOptions(): Promise<void> {
    selectorError.value = null
    try {
      const loaded = await api.loadSelectorOptions()
      const options = buildDiscoveryScopeOptions(loaded)
      scopeOptions.value = options
      // A selection the backend no longer offers falls back to the first entry it does offer; with no
      // entry at all the selection is emptied rather than left naming a character that is not there.
      if (!options.some((option) => option.id === selectedScopeId.value)) {
        selectedScopeId.value = options[0]?.id ?? null
      }
    } catch (cause) {
      selectorError.value = describeFailure(cause)
    }
  }

  /** Keep the selected option when still available; otherwise use the first available character/discipline. */
  async function requestDiscovery(): Promise<void> {
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

    try {
      const response = await api.calculateDiscovery(currentRequest(scope))
      if (requestId !== newestRequestId) return
      apply(response)
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

  function apply(response: CraftingDiscoveryResponse): void {
    rows.value = response.rows
    effectiveScope.value = response.scope
    settings.value = response.settings
    hasResult.value = true
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
    requestError,
    selectorError,
    canCalculate,

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
