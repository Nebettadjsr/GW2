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
  buildInventoryCharacterNames,
  type DiscoveryScopeOption
} from './discoveryScopeOptions'
import { describeFailure } from './useResolutionDetail'

/**
 * Screen state for Crafting Discovery: the selector facts, the chosen character discipline, inventory
 * character and settings, and the backend's answer for that selection.
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
  /** Distinct character names offerable for the separate inventory input. */
  inventoryCharacterNames: ComputedRef<string[]>
  /** The chosen inventory character, or null for the backend's own unfiltered-pool fallback. */
  selectedInventoryCharacter: Ref<string | null>
  settings: Ref<EffectiveDiscoverySettings | null>
  rows: Ref<readonly CraftingRow[]>
  effectiveScope: Ref<EffectiveDiscoveryScope | null>
  /** The inventory character the backend echoed; null when it used the unfiltered pool. */
  effectiveInventoryCharacter: Ref<string | null>
  isLoading: Ref<boolean>
  hasResult: Ref<boolean>
  requestError: Ref<string | null>
  selectorError: Ref<string | null>
  /** Whether a complete, valid scope can be submitted at all. */
  canCalculate: ComputedRef<boolean>

  open(): Promise<void>
  reload(): Promise<void>
  selectScope(scopeOptionId: string): Promise<void>
  selectInventoryCharacter(characterName: string | null): Promise<void>
  applySettings(settings: EffectiveDiscoverySettings): Promise<void>
}

export function useCraftingDiscovery(api: CraftingApi): CraftingDiscoveryState {
  const scopeOptions = shallowRef<DiscoveryScopeOption[]>([])
  const inventoryCharacterNames = shallowRef<string[]>([])
  const selectedScopeId = ref<string | null>(null)
  const selectedInventoryCharacter = ref<string | null>(null)
  const settings = ref<EffectiveDiscoverySettings | null>(null)
  const rows = shallowRef<readonly CraftingRow[]>([])
  const effectiveScope = ref<EffectiveDiscoveryScope | null>(null)
  const effectiveInventoryCharacter = ref<string | null>(null)
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

  /**
   * Whether the user has chosen an inventory character themselves. It tells an explicit "no character"
   * apart from a value that has not been established yet — the two are both null, and only the first
   * one must survive a selector reload (`DOMAIN_SPEC.md` 2.2.1).
   */
  let inventoryChosen = false

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
      inventoryCharacterNames.value = buildInventoryCharacterNames(loaded)
      // A selection the backend no longer offers falls back to the first entry it does offer; with no
      // entry at all the selection is emptied rather than left naming a character that is not there.
      if (!options.some((option) => option.id === selectedScopeId.value)) {
        selectedScopeId.value = options[0]?.id ?? null
      }
      reconcileInventoryCharacter()
    } catch (cause) {
      selectorError.value = describeFailure(cause)
    }
  }

  /**
   * The inventory character opens on the selected character discipline's own character — the page's
   * established individual-character start, rather than an account-wide pool the user did not ask for.
   *
   * That is an *initial* value only. Once a name is in effect it is the calculation's own input and a
   * selector reload leaves it alone, whether the user picked it or it started as this default: a
   * reload re-reads the selector, and re-deriving from the scope there would silently submit different
   * owned materials than the result on screen was calculated with. Like the two JavaFX selectors, the
   * two controls are independent afterwards, so changing the character discipline never rewrites it
   * either. Only a name the selector no longer offers falls back to this default, and an explicit
   * "no character" is a choice that stays.
   */
  function reconcileInventoryCharacter(): void {
    const current = selectedInventoryCharacter.value
    if (current === null && inventoryChosen) return
    if (current !== null && inventoryCharacterNames.value.includes(current)) return
    selectedInventoryCharacter.value = selectedScope.value?.request.characterName ?? null
  }

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
    effectiveInventoryCharacter.value = response.inventoryCharacterName
    settings.value = response.settings
    hasResult.value = true
  }

  /**
   * The current selection as a request body. The scope is always complete and explicit; the inventory
   * character is sent only when one is chosen, so "no character" stays the backend's own null fallback
   * rather than a value invented here. Settings are omitted until the backend has echoed its own.
   */
  function currentRequest(scope: DiscoveryScopeOption): CraftingDiscoveryRequest {
    const request: CraftingDiscoveryRequest = { scope: scope.request }
    const inventoryCharacter = selectedInventoryCharacter.value
    if (inventoryCharacter !== null) request.inventoryCharacterName = inventoryCharacter
    if (settings.value !== null) request.settings = settingsRequestOf(settings.value)
    return request
  }

  return {
    scopeOptions: computed(() => scopeOptions.value),
    selectedScopeId,
    inventoryCharacterNames: computed(() => inventoryCharacterNames.value),
    selectedInventoryCharacter,
    settings,
    rows,
    effectiveScope,
    effectiveInventoryCharacter,
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

    /** Manual reload. Keeps the current scope, inventory character and settings; search and sort too. */
    async reload(): Promise<void> {
      isLoading.value = true
      await loadSelectorOptions()
      await requestDiscovery()
    },

    async selectScope(scopeOptionId: string): Promise<void> {
      selectedScopeId.value = scopeOptionId
      await requestDiscovery()
    },

    async selectInventoryCharacter(characterName: string | null): Promise<void> {
      inventoryChosen = true
      selectedInventoryCharacter.value = characterName
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
 * `dailyBuyInsteadOfCraft` is dropped on purpose: Discovery fixes it and does not accept it as an
 * input, so echoing it back would send the route a field it does not have. No Profit-only setting is
 * reachable from here at all — this client cannot build one.
 */
export function settingsRequestOf(settings: EffectiveDiscoverySettings): DiscoverySettingsRequest {
  return {
    useOwnMats: settings.useOwnMats,
    allowBuying: settings.allowBuying,
    maxBuyCopper: settings.maxBuyCopper,
    listingSell: settings.listingSell,
    listingBuy: settings.listingBuy
  }
}
