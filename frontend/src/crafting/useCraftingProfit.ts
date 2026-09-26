import { computed, ref, shallowRef, type ComputedRef, type Ref } from 'vue'
import type { CraftingApi } from '@/api/craftingApi'
import { ApiRequestError } from '@/api/http'
import type {
  CraftingProfitRequest,
  CraftingProfitResponse,
  CraftingRow,
  EffectiveScope,
  EffectiveSettings
} from '@/api/types'
import { buildScopeOptions, defaultScopeOptionId, type ScopeOption } from './scopeOptions'

/**
 * Screen state for Crafting Profit: the selector facts, the current scope/settings selection, and
 * the backend's answer for that selection.
 *
 * It decides nothing about crafting. Every displayed number, state and default comes from a backend
 * response — including the initial settings, which are read from the first response's echo instead
 * of being repeated here.
 */
export interface CraftingProfitState {
  scopeOptions: ComputedRef<ScopeOption[]>
  selectedScopeId: Ref<string | null>
  settings: Ref<EffectiveSettings | null>
  rows: Ref<readonly CraftingRow[]>
  effectiveScope: Ref<EffectiveScope | null>
  isLoading: Ref<boolean>
  hasResult: Ref<boolean>
  requestError: Ref<string | null>
  selectorError: Ref<string | null>

  open(): Promise<void>
  reload(): Promise<void>
  selectScope(scopeOptionId: string): Promise<void>
  applySettings(settings: EffectiveSettings): Promise<void>
}

export function useCraftingProfit(api: CraftingApi): CraftingProfitState {
  const scopeOptions = shallowRef<ScopeOption[]>([])
  const selectedScopeId = ref<string | null>(null)
  const settings = ref<EffectiveSettings | null>(null)
  const rows = shallowRef<readonly CraftingRow[]>([])
  const effectiveScope = ref<EffectiveScope | null>(null)
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

  const selectedScope = computed<ScopeOption | null>(
    () => scopeOptions.value.find((option) => option.id === selectedScopeId.value) ?? null
  )

  async function loadSelectorOptions(): Promise<void> {
    selectorError.value = null
    try {
      const loaded = await api.loadSelectorOptions()
      const options = buildScopeOptions(loaded)
      scopeOptions.value = options
      // A selection the backend no longer offers falls back to its documented default entry.
      if (!options.some((option) => option.id === selectedScopeId.value)) {
        selectedScopeId.value = defaultScopeOptionId(loaded, options)
      }
    } catch (cause) {
      selectorError.value = describeFailure(cause)
    }
  }

  async function requestProfit(request: CraftingProfitRequest): Promise<void> {
    const requestId = ++newestRequestId
    isLoading.value = true
    requestError.value = null

    try {
      const response = await api.calculateProfit(request)
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

  function apply(response: CraftingProfitResponse): void {
    rows.value = response.rows
    effectiveScope.value = response.scope
    settings.value = response.settings
    hasResult.value = true
  }

  /** The current selection as a request body; omitted parts ask the backend for its own defaults. */
  function currentRequest(): CraftingProfitRequest {
    const request: CraftingProfitRequest = {}
    const scope = selectedScope.value
    if (scope !== null) request.scope = scope.request
    if (settings.value !== null) request.settings = settings.value
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

    /**
     * Opening the screen loads the selector facts and asks for the backend's default calculation.
     * The two run together so a selector failure still leaves a usable default result, and the
     * first request carries no scope or settings at all — the defaults applied are the backend's.
     */
    async open(): Promise<void> {
      await Promise.all([loadSelectorOptions(), requestProfit({})])
    },

    /** Manual reload. Keeps the current scope and settings; search and sort are not touched. */
    async reload(): Promise<void> {
      await loadSelectorOptions()
      await requestProfit(currentRequest())
    },

    async selectScope(scopeOptionId: string): Promise<void> {
      selectedScopeId.value = scopeOptionId
      await requestProfit(currentRequest())
    },

    async applySettings(next: EffectiveSettings): Promise<void> {
      settings.value = next
      await requestProfit(currentRequest())
    }
  }
}

function describeFailure(cause: unknown): string {
  if (cause instanceof ApiRequestError) return `${cause.code}: ${cause.message}`
  return cause instanceof Error ? cause.message : String(cause)
}
