import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { connection } from '@/api/accountSession'
import { ACCOUNT_CHANGED_CODE, ApiRequestError } from '@/api/http'
import type { MysticForgeApi } from '@/api/mysticForgeApi'
import type { ForgeCatalogue, ForgeProject, ForgeRecipeRow } from '@/api/types'

export type ForgeTab = 'ALL' | 'WEAPONS' | 'ARMOR' | 'MATERIALS' | 'OTHER'
export const FORGE_TABS: readonly { id: ForgeTab; label: string }[] = [
  { id: 'ALL', label: 'All' }, { id: 'WEAPONS', label: 'Weapons' }, { id: 'ARMOR', label: 'Armor' },
  { id: 'MATERIALS', label: 'Materials' }, { id: 'OTHER', label: 'Other' }
]
/** Rows rendered before the user asks for more; search always covers the whole catalogue. */
export const INITIAL_VISIBLE = 60
export const TARGET_DEBOUNCE_MS = 400
const REREAD_DELAY_MS = 5000
const MAX_REREADS = 6
export const SOURCE_POLL_MS = 2_000
export const SOURCE_MAX_POLLS = 60
const CREDENTIAL_REQUIRED = 'CREDENTIAL_REQUIRED'

/** Text searched per row: name, ids and the real item type/subtype ("bow" matches LongBow and ShortBow). */
export function searchText(row: ForgeRecipeRow): string {
  return [row.name, row.outputItemId, row.recipeId, row.itemType, row.itemSubtype, row.category]
    .filter((part) => part !== null && part !== undefined).join(' ').toLowerCase()
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
  const tab = ref<ForgeTab>('ALL')
  const query = ref('')
  const visibleLimit = ref(INITIAL_VISIBLE)
  const selectedRecipeId = ref<number | null>(null)
  const targetText = ref('1')
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
  let catalogueRereads = 0
  let catalogueTimer: ReturnType<typeof setTimeout> | null = null
  let active = true
  let disposed = false

  const rows = computed(() => catalogue.value?.recipes ?? [])
  const filtered = computed(() => {
    const needle = query.value.trim().toLowerCase()
    const words = needle === '' ? [] : needle.split(/\s+/)
    return rows.value
      .filter((row) => tab.value === 'ALL' || row.category === tab.value)
      .filter((row) => words.every((w) => searchText(row).includes(w)))
      .slice()
      .sort((a, b) => (a.name ?? `~${a.outputItemId}`).localeCompare(b.name ?? `~${b.outputItemId}`) || a.recipeId - b.recipeId)
  })
  const visible = computed(() => filtered.value.slice(0, visibleLimit.value))
  /**
   * The project is current only for the recipe and exact target now typed; a result for another quantity is never
   * shown as current while the new answer is pending.
   */
  const currentProject = computed(() => {
    const p = project.value
    if (p === null) return null
    return p.requestedTarget === parseTarget(targetText.value) ? p : null
  })
  const selectedRow = computed(() => rows.value.find((row) => row.recipeId === selectedRecipeId.value) ?? null)

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
      if (mine === catalogueToken && active && text !== null) catalogueError.value = text
    } finally {
      if (mine === catalogueToken) catalogueLoading.value = false
    }
  }

  /** Public metadata still completing in the background: bounded rereads so types/quotes appear without a resync. */
  function scheduleCatalogueReread(loaded: ForgeCatalogue): void {
    if (catalogueTimer !== null) clearTimeout(catalogueTimer)
    catalogueTimer = null
    if (loaded.completing !== true) { catalogueRereads = 0; return }
    if (catalogueRereads >= MAX_REREADS) return
    catalogueRereads += 1
    catalogueTimer = setTimeout(() => { catalogueTimer = null; void loadCatalogue() }, REREAD_DELAY_MS)
  }

  function clearTimers(): void {
    if (catalogueTimer !== null) clearTimeout(catalogueTimer)
    catalogueTimer = null
    if (debounce !== null) clearTimeout(debounce)
    if (reread !== null) clearTimeout(reread)
    debounce = null
    reread = null
  }

  async function loadProject(isReread = false): Promise<void> {
    const recipeId = selectedRecipeId.value
    const target = parseTarget(targetText.value)
    if (recipeId === null || target === null || disposed || !active) return
    const mine = ++token
    const generation = connection.generation
    if (!isReread) { rereads = 0; projectLoading.value = true; stalled.value = false; sourceError.value = null }
    projectError.value = null
    try {
      const loaded = await api.loadProject(recipeId, target)
      if (mine !== token || !active || generation !== connection.generation) return
      pending.value = loaded.tree === null
      project.value = loaded.tree === null ? null : loaded
      if (loaded.tree === null && needsKeyedRefresh(loaded) && api.startSourceRefresh && api.readSourceTask) {
        void runSourceRefresh(mine)
      } else scheduleReread(loaded)
    } catch (cause) {
      const text = message(cause)
      if (mine === token && active && text !== null) { projectError.value = text; project.value = isReread ? project.value : null }
    } finally {
      if (mine === token) projectLoading.value = false
    }
  }

  /** Outstanding source work: reread a bounded number of times instead of spinning forever. */
  function scheduleReread(loaded: ForgeProject): void {
    if (reread !== null) clearTimeout(reread)
    reread = null
    const outstanding = (loaded.freshness?.outstandingWork?.length ?? 0) > 0 || loaded.tree === null
    if (!outstanding) return
    if (rereads >= MAX_REREADS) { stalled.value = true; return }
    rereads += 1
    reread = setTimeout(() => { void loadProject(true) }, REREAD_DELAY_MS)
  }

  function needsKeyedRefresh(loaded: ForgeProject): boolean {
    return (loaded.freshness?.failures ?? []).some((failure) => failure.kind === 'ACCOUNT' && failure.code === CREDENTIAL_REQUIRED)
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
      const accepted = await api.startSourceRefresh!()
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
    } finally {
      if (mine === token) sourceRefreshing.value = false
    }
  }

  function retry(): void {
    if (selectedRecipeId.value === null) return
    void loadProject()
  }

  function open(recipeId: number): void {
    clearTimers()
    ++token
    selectedRecipeId.value = recipeId
    targetText.value = '1'
    project.value = null
    pending.value = false
    sourceRefreshing.value = false
    sourceError.value = null
    stalled.value = false
    void loadProject()
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
    if (selectedRecipeId.value !== null) void loadProject()
  }

  function setTab(next: ForgeTab): void { tab.value = next; visibleLimit.value = INITIAL_VISIBLE }
  function setQuery(next: string): void { query.value = next; visibleLimit.value = INITIAL_VISIBLE }
  function showMore(): void { visibleLimit.value += INITIAL_VISIBLE }

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
    // A selected project belongs to the replaced account: close it rather than silently planning it for another.
    selectedRecipeId.value = null
    targetText.value = '1'
    projectError.value = null
    if (!active || disposed) return
    void loadCatalogue()
  })

  onScopeDispose(() => { disposed = true; active = false; clearTimers(); ++token; ++catalogueToken })

  return {
    catalogue, catalogueLoading, catalogueError, tab, query, visibleLimit, filtered, visible, selectedRecipeId,
    selectedRow, targetText, project, currentProject, projectLoading, projectError, pending, sourceRefreshing, sourceError, stalled, retry, loadCatalogue, open, back, setTarget,
    activate, deactivate, setTab, setQuery, showMore
  }
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}
