import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { connection } from '@/api/accountSession'
import { ACCOUNT_CHANGED_CODE, ApiRequestError } from '@/api/http'
import type { MysticForgeApi } from '@/api/mysticForgeApi'
import type { ForgeCatalogue, ForgeProject, ForgeRecipeRow, ForgeSettings } from '@/api/types'
import { readForgePreferences, saveForgePreferences } from './forgePreferences'

export { DEFAULT_FORGE_SETTINGS } from './forgePreferences'

export type ForgeTab = 'ALL' | 'WEAPONS' | 'ARMOR' | 'MATERIALS' | 'OTHER'
export const FORGE_TABS: readonly { id: ForgeTab; label: string }[] = [
  { id: 'ALL', label: 'All' }, { id: 'WEAPONS', label: 'Weapons' }, { id: 'ARMOR', label: 'Armor' },
  { id: 'MATERIALS', label: 'Materials' }, { id: 'OTHER', label: 'Other' }
]
export interface ForgeFilterOption { id: string; label: string }
const RARITY_ORDER: readonly string[] = ['Legendary', 'Ascended', 'Exotic', 'Rare', 'Masterwork', 'Fine', 'Basic', 'Junk']
const OTHER_TYPE_LABELS: Readonly<Record<string, string>> = {
  Back: 'Back item', CraftingMaterial: 'Crafting material', MiniPet: 'Miniature', UpgradeComponent: 'Upgrade component'
}
export function forgeSubtypeLabel(subtype: string): string {
  if (subtype === 'Harpoon') return 'Spear'
  return subtype.replace(/([a-z])([A-Z])/g, '$1 $2')
}
/** Rows rendered before the user asks for more; search always covers the whole catalogue. */
export const INITIAL_VISIBLE = 60
export const TARGET_DEBOUNCE_MS = 400
const REREAD_DELAY_MS = 5000
const MAX_REREADS = 6
export const SOURCE_POLL_MS = 2_000
export const SOURCE_MAX_POLLS = 60
const CREDENTIAL_REQUIRED = 'CREDENTIAL_REQUIRED'
/** Keyed-refresh recoveries per selection/target (a still-stale answer after a succeeded task stops here). */
export const SOURCE_MAX_RECOVERIES = 2
/** Admissions that did not cover this request (a narrower task was running) before the recovery stops. */
export const SOURCE_MAX_ADMISSIONS = 3
const SOURCE_ADMISSION_DELAY_MS = 2_000
const SOURCE_ADMISSION_DELAY_MAX_MS = 10_000

/** Text searched per row: name, ids and the real item type/subtype ("bow" matches LongBow and ShortBow). */
export function searchText(row: ForgeRecipeRow): string {
  return [row.name, row.outputItemId, row.recipeId, row.itemType, row.itemSubtype,
    row.itemSubtype ? forgeSubtypeLabel(row.itemSubtype) : null, row.category, row.rarity, row.weightClass,
    row.materialTier ? `T${row.materialTier}` : null,
    row.category === 'OTHER' ? otherTypeLabel(otherType(row)) : null]
    .filter((part) => part !== null && part !== undefined).join(' ').toLowerCase()
}

function otherType(row: ForgeRecipeRow): string {
  if (row.name?.startsWith('Gift of ') || row.sourcePage?.startsWith('Gift of ')) return 'Gift'
  return row.itemSubtype ?? row.itemType ?? 'Other'
}

function otherTypeLabel(type: string): string { return OTHER_TYPE_LABELS[type] ?? forgeSubtypeLabel(type) }

export function forgeItemTypeLabel(row: ForgeRecipeRow): string | null {
  if (row.category === 'OTHER') return otherTypeLabel(otherType(row))
  return row.itemSubtype ? forgeSubtypeLabel(row.itemSubtype) : row.itemType ? otherTypeLabel(row.itemType) : null
}

/** Named Other variants share one browse entry; each exact output and recipe remains a detail choice. */
function itemGroup(row: ForgeRecipeRow): string {
  return row.category === 'OTHER' && row.name && row.itemType
    ? JSON.stringify([row.category, row.itemType, row.name]) : `item:${row.outputItemId}`
}

export function parseTarget(text: string): number | null {
  const trimmed = text.trim()
  if (!/^\d{1,10}$/.test(trimmed)) return null
  const value = Number(trimmed)
  return value >= 1 && value <= 1_000_000_000 ? value : null
}

/**
 * State of the Mystic Forge destination. The overview is derived only from the cached catalogue: tab,
 * search and paging never call the project endpoint. Opening a recipe plans exactly that recipe and
 * target; every response is checked against a request token so a late answer for an earlier
 * selection, target or deactivation never overwrites the current one.
 */
export function useMysticForge(api: MysticForgeApi, sleep: (ms: number) => Promise<void> = defaultSleep) {
  const catalogue = shallowRef<ForgeCatalogue | null>(null)
  const catalogueLoading = ref(false)
  const catalogueError = ref<string | null>(null)
  /** True when bounded background completion rereads ran out while public metadata is still completing. */
  const catalogueStalled = ref(false)
  const tab = ref<ForgeTab>('ALL')
  const subtype = ref('ALL')
  const rarity = ref('ALL')
  const query = ref('')
  const visibleLimit = ref(INITIAL_VISIBLE)
  const selectedRecipeId = ref<number | null>(null)
  const targetText = ref('1')
  const savedPreferences = readForgePreferences(connection.accountId)
  const settings = ref<ForgeSettings>(savedPreferences.settings)
  const preferencesUnavailable = ref(savedPreferences.unavailable)
  const project = shallowRef<ForgeProject | null>(null)
  const projectLoading = ref(false)
  const projectError = ref<string | null>(null)
  const pending = ref(false)
  /** Keyed source refresh for the selected project: idle, running, or stopped with a truthful reason + retry. */
  const sourceRefreshing = ref(false)
  const sourceError = ref<string | null>(null)
  const stalled = ref(false)
  let token = 0
  let catalogueToken = 0
  let debounce: ReturnType<typeof setTimeout> | null = null
  let reread: ReturnType<typeof setTimeout> | null = null
  let rereads = 0
  let sourceRecoveries = 0
  let catalogueRereads = 0
  let catalogueTimer: ReturnType<typeof setTimeout> | null = null
  let active = true
  let disposed = false

  const rows = computed(() => catalogue.value?.recipes ?? [])
  const subtypeOptions = computed<ForgeFilterOption[]>(() => {
    if (tab.value === 'ARMOR') return ['ALL', 'Light', 'Medium', 'Heavy'].map(id => ({ id, label: id === 'ALL' ? 'All weights' : id }))
    if (tab.value === 'MATERIALS') return [{ id: 'ALL', label: 'All tiers' },
      ...[2, 3, 4, 5, 6].map(tier => ({ id: `T${tier}`, label: `T${tier}` }))]
    if (tab.value === 'OTHER') {
      const types = [...new Set(rows.value.filter(row => row.category === 'OTHER').map(otherType))]
        .map(id => ({ id, label: otherTypeLabel(id) })).sort((a, b) => a.label.localeCompare(b.label))
      return [{ id: 'ALL', label: 'All types' }, ...types]
    }
    if (tab.value !== 'WEAPONS') return []
    const types = [...new Set(rows.value.filter(row => row.category === 'WEAPONS' && row.itemSubtype).map(row => row.itemSubtype!))]
      .map(id => ({ id, label: forgeSubtypeLabel(id) })).sort((a, b) => a.label.localeCompare(b.label))
    return [{ id: 'ALL', label: 'All types' }, ...types]
  })
  const rarityOptions = computed<ForgeFilterOption[]>(() => {
    const rarities = [...new Set(rows.value.map(row => row.rarity).filter((value): value is string => !!value))]
    const rank = (value: string) => { const index = RARITY_ORDER.indexOf(value); return index < 0 ? RARITY_ORDER.length : index }
    rarities.sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))
    return [{ id: 'ALL', label: 'All rarities' }, ...rarities.map(id => ({ id, label: id }))]
  })
  const filtered = computed(() => {
    const needle = query.value.trim().toLowerCase()
    const words = needle === '' ? [] : needle.split(/\s+/)
    const matching = rows.value
      .filter((row) => tab.value === 'ALL' || row.category === tab.value)
      .filter((row) => subtype.value === 'ALL' || (tab.value === 'ARMOR' ? row.weightClass
        : tab.value === 'MATERIALS' ? `T${row.materialTier}` : tab.value === 'OTHER' ? otherType(row) : row.itemSubtype) === subtype.value)
      .filter((row) => rarity.value === 'ALL' || row.rarity === rarity.value)
      .filter((row) => words.every((w) => searchText(row).includes(w)))
    // Filter before grouping so a rarity or exact item/recipe-id search can open that variant directly.
    const unique = new Map<string, ForgeRecipeRow>()
    for (const row of matching) if (!unique.has(itemGroup(row))) unique.set(itemGroup(row), row)
    return [...unique.values()]
      .sort((a, b) => (a.name ?? `~${a.outputItemId}`).localeCompare(b.name ?? `~${b.outputItemId}`) || a.recipeId - b.recipeId)
  })
  const visible = computed(() => filtered.value.slice(0, visibleLimit.value))
  const selectedRow = computed(() => rows.value.find((row) => row.recipeId === selectedRecipeId.value) ?? null)
  // Material pages expose only their applicable controls; hidden equipment options must not affect their plan.
  const effectiveSettings = computed<ForgeSettings>(() => selectedRow.value?.category === 'MATERIALS'
    ? { ...settings.value, daily: 'CRAFT', promotions: false, reduce: 'NO' } : settings.value)
  /**
   * The project is current only for the recipe and exact target now typed; a result for another quantity is never
   * shown as current while the new answer is pending.
   */
  const currentProject = computed(() => {
    const p = project.value
    if (p === null) return null
    // Only an answer for this exact target AND these exact settings is current; a stale one never paints.
    const s = p.settings
    const same = s === undefined || (Object.keys(effectiveSettings.value) as (keyof ForgeSettings)[]).every((k) => s[k] === effectiveSettings.value[k])
    return p.requestedTarget === parseTarget(targetText.value) && same ? p : null
  })
  const selectedPaths = computed(() => {
    const selected = selectedRow.value
    return selected ? rows.value.filter(row => itemGroup(row) === itemGroup(selected)) : []
  })

  function message(cause: unknown): string | null {
    if (cause instanceof ApiRequestError && cause.code === ACCOUNT_CHANGED_CODE) return null
    return cause instanceof Error ? cause.message : String(cause)
  }

  async function loadCatalogue(): Promise<void> {
    if (catalogueLoading.value || disposed || !active) return
    const mine = ++catalogueToken
    const generation = connection.generation
    catalogueLoading.value = true
    catalogueError.value = null
    try {
      const loaded = await api.loadCatalogue()
      if (mine === catalogueToken && active && generation === connection.generation) {
        catalogue.value = loaded
        scheduleCatalogueReread(loaded)
      }
    } catch (cause) {
      const text = message(cause)
      if (mine === catalogueToken && active && text !== null) {
        catalogueError.value = text
        // A failed reread of a still-completing cached catalogue stays inside the same bounded completion:
        // re-arm (never resetting the counter) or stop with the stalled Retry visible.
        const previous = catalogue.value
        if (previous !== null && previous.completing === true) { catalogueTimer = null; scheduleCatalogueReread(previous) }
      }
    } finally {
      if (mine === catalogueToken) catalogueLoading.value = false
    }
  }

  /** Public metadata still completing in the background: bounded rereads so types/quotes appear without a resync. */
  function scheduleCatalogueReread(loaded: ForgeCatalogue): void {
    if (catalogueTimer !== null) clearTimeout(catalogueTimer)
    catalogueTimer = null
    if (loaded.completing !== true) { catalogueRereads = 0; catalogueStalled.value = false; return }
    if (catalogueRereads >= MAX_REREADS) { catalogueStalled.value = true; return }
    catalogueStalled.value = false
    catalogueRereads += 1
    catalogueTimer = setTimeout(() => { catalogueTimer = null; void loadCatalogue() }, REREAD_DELAY_MS)
  }

  /** Manual Retry: the only path that resets the bounded counter; automatic rereads never do. */
  function retryCatalogue(): void {
    catalogueRereads = 0
    catalogueStalled.value = false
    void loadCatalogue()
  }

  /** Re-arms a completion reread cancelled by opening a detail or deactivating, still within the same bound. */
  function resumeCatalogue(): void {
    const loaded = catalogue.value
    if (loaded === null || loaded.completing !== true || catalogueTimer !== null || catalogueLoading.value
      || catalogueStalled.value || !active || disposed) return
    scheduleCatalogueReread(loaded)
  }

  function clearTimers(): void {
    if (catalogueTimer !== null) clearTimeout(catalogueTimer)
    catalogueTimer = null
    if (debounce !== null) clearTimeout(debounce)
    if (reread !== null) clearTimeout(reread)
    debounce = null
    reread = null
  }

  /** The effective settings the newest project request was made for; a later change while it is in flight replans once. */
  let requestedSettingsKey = ''

  async function loadProject(isReread = false): Promise<void> {
    const recipeId = selectedRecipeId.value
    const target = parseTarget(targetText.value)
    if (recipeId === null || target === null || disposed || !active) return
    const mine = ++token
    const generation = connection.generation
    requestedSettingsKey = JSON.stringify(effectiveSettings.value)
    if (!isReread) { rereads = 0; sourceRecoveries = 0; projectLoading.value = true; stalled.value = false; sourceError.value = null }
    projectError.value = null
    try {
      const loaded = await api.loadProject(recipeId, target, { ...effectiveSettings.value })
      if (mine !== token || !active || generation !== connection.generation) return
      const unavailable = hasNoResult(loaded)
      pending.value = unavailable
      project.value = unavailable ? null : loaded
      // A first-use (no plan) OR usable-but-stale account plan needs the keyed DATA refresh; the stale plan stays
      // visible (marked by its freshness) while it runs. Recoveries are capped so a still-stale answer cannot loop.
      if (needsKeyedRefresh(loaded) && api.startSourceRefresh && api.readSourceTask && sourceRecoveries < SOURCE_MAX_RECOVERIES) {
        sourceRecoveries += 1
        void runSourceRefresh(mine)
      } else if (needsKeyedRefresh(loaded) && api.startSourceRefresh && api.readSourceTask) {
        // Recovery cap exhausted and the answer still needs keyed data: stop visibly (the usable stale plan stays shown)
        // with Retry instead of silently idling; no further automatic admission or reread.
        if (reread !== null) clearTimeout(reread)
        reread = null
        sourceError.value = 'Account data is still out of date after synchronizing; automatic checking has stopped.'
        pending.value = false
      } else scheduleReread(loaded)
    } catch (cause) {
      const text = message(cause)
      if (mine === token && active && text !== null) { projectError.value = text; project.value = isReread ? project.value : null }
    } finally {
      if (mine === token) projectLoading.value = false
    }
  }

  /**
   * Only a pending-account / no-result answer is "pending". A REAL result (output present or resultAvailable) is kept
   * and shown even when it is arithmetic-incomplete and carries no tree.
   */
  function hasNoResult(loaded: ForgeProject): boolean {
    return loaded.tree === null && loaded.output === null && loaded.freshness?.resultAvailable !== true
  }

  /** Outstanding source work: reread a bounded number of times instead of spinning forever. */
  function scheduleReread(loaded: ForgeProject): void {
    if (reread !== null) clearTimeout(reread)
    reread = null
    const outstanding = (loaded.freshness?.outstandingWork?.length ?? 0) > 0 || hasNoResult(loaded)
    if (!outstanding) return
    if (rereads >= MAX_REREADS) { stalled.value = true; return }
    rereads += 1
    reread = setTimeout(() => { void loadProject(true) }, REREAD_DELAY_MS)
  }

  function needsKeyedRefresh(loaded: ForgeProject): boolean {
    const freshness = loaded.freshness
    if ((freshness?.failures ?? []).some((failure) => failure.kind === 'ACCOUNT' && failure.code === CREDENTIAL_REQUIRED)) return true
    return !hasNoResult(loaded) && (freshness?.accountSources ?? []).some((source) => source.state === 'STALE' || source.state === 'MISSING')
  }

  /**
   * The cached project read is keyless, so a first-use account answers 202 CREDENTIAL_REQUIRED. Run the existing
   * keyed account-data refresh once, follow its task a bounded number of times, then reread ONLY the selected
   * project. A stale selection, account change or deactivation cancels it; a failure/timeout stops with a retry.
   */
  async function runSourceRefresh(mine: number): Promise<void> {
    const generation = connection.generation
    const current = () => mine === token && active && generation === connection.generation
    sourceRefreshing.value = true
    sourceError.value = null
    stalled.value = false
    try {
      let accepted = await api.startSourceRefresh!()
      // A narrower running task does not cover this request: retry admission a bounded number of times.
      for (let admission = 1; accepted.coversRequest === false; admission += 1) {
        if (admission >= SOURCE_MAX_ADMISSIONS) throw new Error('Account synchronization is busy with another task. Try again shortly.')
        const seconds = accepted.retryAfterSeconds
        const delay = typeof seconds === 'number' && Number.isFinite(seconds) && seconds > 0
          ? Math.min(seconds * 1_000, SOURCE_ADMISSION_DELAY_MAX_MS) : SOURCE_ADMISSION_DELAY_MS
        await sleep(delay)
        if (!current()) return
        accepted = await api.startSourceRefresh!()
        if (!current()) return
      }
      for (let poll = 0; ; poll += 1) {
        if (!current()) return
        if (poll >= SOURCE_MAX_POLLS) throw new Error('Account synchronization is taking longer than expected.')
        const status = await api.readSourceTask!(accepted.statusUrl)
        if (!current()) return
        if (status.state === 'SUCCEEDED') break
        if (status.state === 'FAILED') {
          throw new Error(status.failure ? `Account synchronization failed: ${status.failure.message}` : 'Account synchronization failed.')
        }
        await sleep(SOURCE_POLL_MS)
      }
      if (!current()) return
      sourceRefreshing.value = false
      await loadProject(true)
    } catch (cause) {
      if (!current()) return
      sourceError.value = message(cause) ?? 'Account synchronization failed.'
      pending.value = false
      sourceRecoveries = SOURCE_MAX_RECOVERIES // terminal: no automatic re-admission until the user retries
    } finally {
      if (mine === token) sourceRefreshing.value = false
    }
  }

  function retry(): void {
    if (selectedRecipeId.value === null) return
    void loadProject()
  }

  function open(recipeId: number, keepTarget = false): void {
    clearTimers()
    ++token
    selectedRecipeId.value = recipeId
    if (!keepTarget) targetText.value = selectedRow.value?.category === 'MATERIALS' ? '250' : '1'
    project.value = null
    pending.value = false
    sourceRefreshing.value = false
    sourceError.value = null
    stalled.value = false
    // A row whose type is not cached yet (category OTHER) must not stay generic: keep the same bounded public
    // metadata reread running while its detail is open so weapon/armor controls appear in place.
    if (selectedRow.value?.category === 'OTHER') resumeCatalogue()
    void loadProject()
  }

  function selectPath(recipeId: number): void {
    if (recipeId === selectedRecipeId.value || !selectedPaths.value.some(row => row.recipeId === recipeId)) return
    open(recipeId, true)
  }

  function back(): void {
    clearTimers()
    ++token
    selectedRecipeId.value = null
    project.value = null
    projectLoading.value = false
    sourceRefreshing.value = false
    projectError.value = null
    pending.value = false
    resumeCatalogue()
  }

  /** A modest debounce; an empty or invalid draft never posts. */
  function setTarget(text: string): void {
    targetText.value = text
    // Any edit immediately invalidates the earlier answer, source refresh and bounded reread.
    ++token
    if (debounce !== null) clearTimeout(debounce)
    if (reread !== null) clearTimeout(reread)
    debounce = null
    reread = null
    projectLoading.value = false
    sourceRefreshing.value = false
    sourceError.value = null
    pending.value = false
    stalled.value = false
    projectError.value = null
    if (parseTarget(text) === null) return
    projectLoading.value = true
    debounce = setTimeout(() => { debounce = null; void loadProject() }, TARGET_DEBOUNCE_MS)
  }

  /** A menu change recalculates only the selected project after the same debounce; earlier answers are invalidated. */
  function setSettings(patch: Partial<ForgeSettings>): void {
    settings.value = { ...settings.value, ...patch }
    preferencesUnavailable.value = !saveForgePreferences(connection.accountId, settings.value)
    setTarget(targetText.value)
  }

  /** Deactivation discards anything still in flight; activation rereads the open project. */
  function deactivate(): void {
    active = false
    clearTimers()
    ++token
    ++catalogueToken
    projectLoading.value = false
    catalogueLoading.value = false
    sourceRefreshing.value = false
  }
  function activate(): void {
    if (disposed) return
    active = true
    if (catalogue.value === null) void loadCatalogue()
    else resumeCatalogue()
    if (selectedRecipeId.value !== null) void loadProject()
  }

  function setTab(next: ForgeTab): void {
    if (tab.value !== next) subtype.value = 'ALL'
    tab.value = next
    visibleLimit.value = INITIAL_VISIBLE
  }
  function setSubtype(next: string): void {
    if (!subtypeOptions.value.some(option => option.id === next)) return
    subtype.value = next
    visibleLimit.value = INITIAL_VISIBLE
  }
  function setRarity(next: string): void {
    if (!rarityOptions.value.some(option => option.id === next)) return
    rarity.value = next
    visibleLimit.value = INITIAL_VISIBLE
  }
  function setQuery(next: string): void { query.value = next; visibleLimit.value = INITIAL_VISIBLE }
  function showMore(): void { visibleLimit.value += INITIAL_VISIBLE }

  function restorePreferences(): void {
    const saved = readForgePreferences(connection.accountId)
    settings.value = saved.settings
    preferencesUnavailable.value = saved.unavailable
  }

  // Late metadata can change which settings are effective for the open recipe (an uncached row becoming a Material neutralizes
  // daily/promotions/reduce). The plan held for the old effective settings no longer paints, so replan once for the change,
  // keeping the selection, target and shared preferences. A user edit already has its debounced plan pending and is skipped.
  watch(() => JSON.stringify(effectiveSettings.value), () => {
    if (selectedRecipeId.value === null || debounce !== null || !active || disposed) return
    // A request still in flight for the OLD settings is superseded once (its token goes stale) by one for the current ones.
    if (projectLoading.value) {
      if (requestedSettingsKey !== JSON.stringify(effectiveSettings.value)) void loadProject()
      return
    }
    if (currentProject.value !== null || project.value === null) return
    void loadProject()
  })

  watch(() => connection.accountId, () => {
    restorePreferences()
    if (active && selectedRecipeId.value !== null) setTarget(targetText.value)
  })

  /** Account replacement: every earlier answer is discarded and the visible data is re-read for the new account. */
  watch(() => connection.generation, () => {
    clearTimers()
    ++token
    ++catalogueToken
    catalogue.value = null
    project.value = null
    catalogueLoading.value = false
    projectLoading.value = false
    sourceRefreshing.value = false
    sourceError.value = null
    pending.value = false
    stalled.value = false
    catalogueRereads = 0
    catalogueStalled.value = false
    // A selected project belongs to the replaced account: close it rather than silently planning it for another.
    selectedRecipeId.value = null
    targetText.value = '1'
    restorePreferences()
    projectError.value = null
    if (!active || disposed) return
    void loadCatalogue()
  })

  onScopeDispose(() => { disposed = true; active = false; clearTimers(); ++token; ++catalogueToken })

  return {
    catalogue, catalogueLoading, catalogueError, catalogueStalled, retryCatalogue, tab, subtype, subtypeOptions, rarity, rarityOptions, query, visibleLimit, filtered, visible, selectedRecipeId,
    selectedRow, selectedPaths, selectPath, targetText, settings: effectiveSettings, setSettings, preferencesUnavailable, project, currentProject, projectLoading, projectError, pending, sourceRefreshing, sourceError, stalled, retry, loadCatalogue, open, back, setTarget,
    activate, deactivate, setTab, setSubtype, setRarity, setQuery, showMore
  }
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}
