/**
 * Read state for the Characters destination (STORY-CHAR-002).
 *
 * Three independent reads: the roster (CHARACTERS coverage), the selected character's detail, and its
 * equipment tabs (CHARACTER_EQUIPMENT_TABS coverage). Unavailable equipment never erases an admitted
 * roster. Every answer is guarded by the account generation it was requested under and by the
 * character that is still selected, so a late answer for a replaced account or a previous selection
 * is discarded. Nothing is derived from the data: absence of an equipment row is never turned into an
 * empty slot, and `tabs` absent is never read as `[]`.
 *
 * Equipment recovery uses the existing refresh operation: at most one in flight, a bounded task wait,
 * one automatic attempt per workspace for a PENDING source, otherwise only on the user's request.
 */
import { computed, getCurrentScope, onScopeDispose, ref, shallowRef } from 'vue'
import { connection } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import { charactersApi, type CharactersApi } from '@/api/charactersApi'
import { syncApi, type SyncApi } from '@/api/syncApi'
import type {
  CharacterBuildTabsResponse, CharacterEquipmentTabsResponse, CharacterInventoryResponse, CharacterOverview, CharacterReadResponse, EquipmentTab
} from '@/api/types'

export const EQUIPMENT_POLL_MS = 2_000
export const EQUIPMENT_MAX_POLLS = 60
export const EQUIPMENT_MAX_COVER_RETRIES = 2

const HAS_DATA = ['AVAILABLE', 'STALE']

export interface ReadFailure { code: string; message: string }
export type ReadPhase = 'idle' | 'loading' | 'loaded'

function failureOf(cause: unknown): ReadFailure {
  if (cause instanceof ApiRequestError) return { code: cause.code, message: cause.message }
  return { code: 'CLIENT_ERROR', message: cause instanceof Error ? cause.message : String(cause) }
}

export function useCharacters(
  api: CharactersApi = charactersApi,
  tasks: Pick<SyncApi, 'readTaskStatus'> = syncApi,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((resolve) => window.setTimeout(resolve, ms))
) {
  const generation = connection.generation
  let disposed = false
  if (getCurrentScope()) onScopeDispose(() => { disposed = true })
  const current = (): boolean => !disposed && generation === connection.generation

  // Roster
  const rosterPhase = ref<ReadPhase>('idle')
  const rosterResponse = shallowRef<CharacterReadResponse | null>(null)
  /** Last roster that actually carried characters; kept across a later transient failure. */
  const lastGoodRoster = shallowRef<CharacterReadResponse | null>(null)
  const rosterFailure = ref<ReadFailure | null>(null)
  let rosterId = 0

  async function loadRoster(): Promise<void> {
    if (!current()) return
    const id = ++rosterId
    rosterPhase.value = 'loading'
    rosterFailure.value = null
    try {
      const response = await api.loadRoster()
      if (!current() || id !== rosterId) return
      rosterResponse.value = response
      if (HAS_DATA.includes(response.state) && response.characters) lastGoodRoster.value = response
    } catch (cause) {
      if (!current() || id !== rosterId) return
      rosterFailure.value = failureOf(cause)
    }
    rosterPhase.value = 'loaded'
  }

  /** The roster to show: the current one when it carries data, else the retained last-good one. */
  const roster = computed(() => {
    const now = rosterResponse.value
    if (now && HAS_DATA.includes(now.state) && now.characters) return now
    return lastGoodRoster.value
  })
  /** True when the shown roster is a retained earlier answer rather than the newest read. */
  const rosterIsRetained = computed(() => roster.value !== null && roster.value !== rosterResponse.value)

  // Selection
  const selectedName = ref<string | null>(null)
  const detail = shallowRef<CharacterOverview | null>(null)
  const detailResponse = shallowRef<CharacterReadResponse | null>(null)
  const detailFailure = ref<ReadFailure | null>(null)
  const detailPhase = ref<ReadPhase>('idle')
  let detailId = 0

  const equipmentPhase = ref<ReadPhase>('idle')
  const equipmentResponse = shallowRef<CharacterEquipmentTabsResponse | null>(null)
  /** Last equipment answer with tabs, per character name; account-bound because this state is. */
  const lastGoodEquipment = shallowRef<Map<string, CharacterEquipmentTabsResponse>>(new Map())
  const equipmentFailure = ref<ReadFailure | null>(null)
  const inspectedTab = ref<number | null>(null)
  let equipmentId = 0

  // Inventory: a lazily loaded section, read only when first inspected for the selected character.
  const section = ref<'equipment' | 'inventory'>('equipment')
  const inventoryPhase = ref<ReadPhase>('idle')
  const inventoryResponse = shallowRef<CharacterInventoryResponse | null>(null)
  const lastGoodInventory = shallowRef<Map<string, CharacterInventoryResponse>>(new Map())
  const inventoryFailure = ref<ReadFailure | null>(null)
  let inventoryId = 0

  // Saved builds: read only for the selected character, solely to name its ACTIVE elite specialization
  // in the header. Not retained across characters; any doubt falls back to the base profession.
  const buildPhase = ref<ReadPhase>('idle')
  const buildResponse = shallowRef<CharacterBuildTabsResponse | null>(null)
  let buildId = 0
  let buildRecoveryTried = false

  const stillSelected = (name: string, id: number, newest: number): boolean =>
    current() && selectedName.value === name && id === newest

  async function loadDetail(name: string): Promise<void> {
    if (!current()) return
    const id = ++detailId
    detailPhase.value = 'loading'
    detailFailure.value = null
    try {
      const response = await api.loadCharacter(name)
      if (!stillSelected(name, id, detailId)) return
      detailResponse.value = response
      detail.value = HAS_DATA.includes(response.state) && response.character ? response.character : null
    } catch (cause) {
      if (!stillSelected(name, id, detailId)) return
      detailFailure.value = failureOf(cause)
    }
    detailPhase.value = 'loaded'
  }

  async function loadEquipment(name: string): Promise<void> {
    if (!current()) return
    const id = ++equipmentId
    equipmentPhase.value = 'loading'
    equipmentFailure.value = null
    try {
      const response = await api.loadEquipmentTabs(name)
      if (!stillSelected(name, id, equipmentId)) return
      equipmentResponse.value = response
      if (HAS_DATA.includes(response.state) && response.tabs) {
        lastGoodEquipment.value = new Map(lastGoodEquipment.value).set(name, response)
        const tabs = response.tabs
        if (inspectedTab.value === null || !tabs.some((t) => t.tab === inspectedTab.value)) {
          inspectedTab.value = tabs.find((t) => t.active)?.tab ?? tabs[0]?.tab ?? null
        }
      }
    } catch (cause) {
      if (!stillSelected(name, id, equipmentId)) return
      equipmentFailure.value = failureOf(cause)
    }
    equipmentPhase.value = 'loaded'
    reconcile(name)
    if (equipmentResponse.value?.state === 'PENDING' && !autoRecoveryTried) {
      autoRecoveryTried = true
      void refreshEquipment()
    }
  }

  async function loadBuildTabs(name: string): Promise<void> {
    if (!current()) return
    const id = ++buildId
    buildPhase.value = 'loading'
    try {
      const response = await api.loadBuildTabs(name)
      if (!stillSelected(name, id, buildId)) return
      buildResponse.value = response
    } catch {
      if (!stillSelected(name, id, buildId)) return
      buildResponse.value = null // transport failure: no decoration, base profession is shown
    }
    buildPhase.value = 'loaded'
    // Optional source not covered yet: one automatic recovery attempt per selection, like equipment.
    if (buildResponse.value?.state === 'PENDING' && !buildRecoveryTried && !refreshing.value) {
      buildRecoveryTried = true
      void recoverBuilds(name)
    }
  }

  async function recoverBuilds(name: string): Promise<void> {
    try {
      const accepted = await api.startBuildTabsRefresh()
      if (!stillSelected(name, buildId, buildId) || !(await awaitTask(accepted.statusUrl))) return
      if (stillSelected(name, buildId, buildId) && accepted.coversRequest !== false) await loadBuildTabs(name)
    } catch { /* optional decoration only: stay on the base profession */ }
  }

  /**
   * The ACTIVE elite specialization's name for the header, or null for the base-profession fallback.
   * Requires: this character's AVAILABLE/STALE build answer covering the current parent generation
   * (and no newer parent known from equipment/inventory); exactly one tab marked active; exactly one
   * resolved elite specialization in it whose parent profession is this character's profession.
   */
  const activeElite = computed<string | null>(() => {
    const r = buildResponse.value
    const d = shownDetail.value
    if (!r || !d || !HAS_DATA.includes(r.state) || !r.tabs) return null
    if (r.character !== undefined && r.character !== selectedName.value) return null
    const parent = r.parent?.generation
    if (parent === undefined || r.coveredParentGeneration !== parent) return null
    const known = newest(knownEquipmentParent.value, knownInventoryParent.value)
    if (known !== undefined && known > parent) return null
    const active = r.tabs.filter((t) => t.active)
    if (active.length !== 1) return null
    const elites = active[0]!.specializations
      .map((s) => s.specialization)
      .filter((s) => s && s.resolved && s.elite === true && s.name && s.profession?.toLowerCase() === d.profession.toLowerCase())
    return elites.length === 1 ? elites[0]!.name! : null
  })

  async function loadInventory(name: string): Promise<void> {
    if (!current()) return
    const id = ++inventoryId
    inventoryPhase.value = 'loading'
    inventoryFailure.value = null
    try {
      const response = await api.loadInventory(name)
      if (!stillSelected(name, id, inventoryId)) return
      inventoryResponse.value = response
      if (HAS_DATA.includes(response.state) && response.bags) {
        lastGoodInventory.value = new Map(lastGoodInventory.value).set(name, response)
      }
    } catch (cause) {
      if (!stillSelected(name, id, inventoryId)) return
      inventoryFailure.value = failureOf(cause)
    }
    inventoryPhase.value = 'loaded'
    reconcile(name)
  }

  // Bounded generation reconciliation between the two CHARACTERS-derived sections: each target
  // (character + section + known newer parent generation) is reread at most once automatically.
  const reconciled = new Set<string>()
  function reconcile(name: string): void {
    if (!current() || selectedName.value !== name) return
    const ig = knownInventoryParent.value
    const eg = knownEquipmentParent.value
    if (ig === undefined || eg === undefined || ig === eg) return
    if (eg < ig) {
      const key = `eq:${name}:${ig}`
      if (reconciled.has(key) || equipmentPhase.value === 'loading') return
      reconciled.add(key)
      void loadEquipment(name)
    } else {
      const key = `inv:${name}:${eg}`
      if (reconciled.has(key) || inventoryPhase.value !== 'loaded') return
      reconciled.add(key)
      void loadInventory(name)
    }
  }

  /**
   * Switch section. Inventory is read the first time it is opened, and again on re-entry when the
   * last read gave no layout (user-driven, one request per entry); returning to a loaded layout
   * costs no request unless the other section proved it describes an older parent generation.
   */
  function showSection(next: 'equipment' | 'inventory'): void {
    section.value = next
    const name = selectedName.value
    if (name === null) return
    if (next === 'inventory' && inventoryPhase.value !== 'loading') {
      const r = inventoryResponse.value
      const usable = r !== null && HAS_DATA.includes(r.state) && r.bags !== undefined && inventoryFailure.value === null
      if (inventoryPhase.value === 'idle' || !usable) { void loadInventory(name); return }
    }
    reconcile(name)
  }

  const inventorySource = computed<CharacterInventoryResponse | null>(() => {
    const now = inventoryResponse.value
    if (now && HAS_DATA.includes(now.state) && now.bags) return now
    return selectedName.value === null ? null : (lastGoodInventory.value.get(selectedName.value) ?? null)
  })
  const inventoryIsRetained = computed(
    () => inventorySource.value !== null &&
      (inventoryFailure.value !== null || inventorySource.value !== inventoryResponse.value)
  )
  /**
   * Newest CHARACTERS parent generation observed per section, from the current envelope (even a
   * non-data PENDING/UNAVAILABLE one that carries parent metadata) and from the retained display data.
   */
  const newest = (...gs: Array<number | undefined>): number | undefined => {
    const k = gs.filter((g): g is number => typeof g === 'number')
    return k.length ? Math.max(...k) : undefined
  }
  const knownEquipmentParent = computed(() =>
    newest(equipmentResponse.value?.parent?.generation, equipmentSource.value?.parent?.generation))
  const knownInventoryParent = computed(() =>
    newest(inventoryResponse.value?.generation, inventorySource.value?.generation))

  /** Retained equipment describes an older parent than the newest one known. */
  const equipmentOlderThanInventory = computed(() => {
    const g = knownInventoryParent.value
    const eg = knownEquipmentParent.value
    return g !== undefined && eg !== undefined && eg < g
  })

  /** The inventory shown describes an older parent than the newest one known from equipment. */
  const inventoryOlderThanEquipment = computed(() => {
    const g = inventorySource.value?.generation
    const eg = knownEquipmentParent.value
    return g !== undefined && eg !== undefined && g < eg
  })

  /**
   * Core fields to show: the character's own detail when it carried data, else — only once the detail
   * read has finished without data — the same character's entry in the retained same-account roster.
   * Never presented as fresh (see `detailIsRetained`).
   */
  const shownDetail = computed<CharacterOverview | null>(() => {
    if (detail.value) return detail.value
    if (detailPhase.value !== 'loaded' || selectedName.value === null) return null
    return roster.value?.characters?.find((ch) => ch.name === selectedName.value) ?? null
  })
  const detailIsRetained = computed(() => shownDetail.value !== null && shownDetail.value !== detail.value)

  function resetInventory(): void {
    inventoryId += 1
    inventoryResponse.value = null
    inventoryFailure.value = null
    inventoryPhase.value = 'idle'
    section.value = 'equipment'
  }

  function resetBuilds(): void {
    buildId += 1
    buildResponse.value = null
    buildPhase.value = 'idle'
    buildRecoveryTried = false
  }

  function select(name: string): void {
    if (selectedName.value === name) return
    selectedName.value = name
    detail.value = null
    detailResponse.value = null
    equipmentResponse.value = null
    resetInventory()
    inspectedTab.value = initialInspection(name)
    refreshMessage.value = null
    // Invalidate anything in flight for the previous character.
    detailId += 1
    equipmentId += 1
    resetBuilds()
    void loadDetail(name)
    void loadEquipment(name)
    void loadBuildTabs(name)
  }

  /** Inspection for a (re)opened character: its retained tabs' actual active tab, else nothing yet. */
  function initialInspection(name: string): number | null {
    const tabs = lastGoodEquipment.value.get(name)?.tabs
    return tabs?.find((t) => t.active)?.tab ?? tabs?.[0]?.tab ?? null
  }

  function back(): void {
    selectedName.value = null
    detailId += 1
    equipmentId += 1
    detail.value = null
    detailResponse.value = null
    equipmentResponse.value = null
    resetInventory()
    inspectedTab.value = null
    detailPhase.value = 'idle'
    equipmentPhase.value = 'idle'
    resetBuilds()
  }

  function inspect(tab: number): void {
    if (equipmentTabs.value?.some((t) => t.tab === tab)) inspectedTab.value = tab
  }

  /** The tabs to present: the newest answer's, else the retained last-good ones for this character. */
  const equipmentSource = computed<CharacterEquipmentTabsResponse | null>(() => {
    const now = equipmentResponse.value
    if (now && HAS_DATA.includes(now.state) && now.tabs) return now
    return selectedName.value === null ? null : (lastGoodEquipment.value.get(selectedName.value) ?? null)
  })
  const equipmentTabs = computed<EquipmentTab[] | null>(() => equipmentSource.value?.tabs ?? null)
  const equipmentIsRetained = computed(
    () => equipmentSource.value !== null &&
      (equipmentFailure.value !== null || equipmentSource.value !== equipmentResponse.value)
  )
  const activeTab = computed(() => equipmentTabs.value?.find((t) => t.active)?.tab ?? null)
  const shownTab = computed(() => equipmentTabs.value?.find((t) => t.tab === inspectedTab.value) ?? null)

  // Refresh of the optional equipment source, through the existing operation.
  const refreshing = ref(false)
  const refreshMessage = ref<string | null>(null)
  let autoRecoveryTried = false

  async function refreshEquipment(): Promise<void> {
    const name = selectedName.value
    if (refreshing.value || name === null) return
    refreshing.value = true
    refreshMessage.value = null
    try {
      for (let attempt = 0; attempt <= EQUIPMENT_MAX_COVER_RETRIES; attempt += 1) {
        if (!current()) return
        const accepted = await api.startEquipmentTabsRefresh()
        if (!current()) return
        if (!(await awaitTask(accepted.statusUrl))) return
        if (!current()) return
        if (accepted.coversRequest !== false) break
        if (attempt === EQUIPMENT_MAX_COVER_RETRIES) throw new Error('Another refresh kept running. Try again later.')
      }
      // The refresh publishes every character: re-read whichever one is selected now, not the one
      // that started it. `current()` above still gates this on the same account and workspace.
      const selectedNow = selectedName.value
      if (current() && selectedNow !== null) await loadEquipment(selectedNow)
    } catch (cause) {
      if (!current()) return
      const failure = failureOf(cause)
      refreshMessage.value = failure.code === 'PREREQUISITE_UNAVAILABLE'
        ? 'The character list has not been synchronized yet, so equipment cannot be updated.'
        : `Equipment could not be updated: ${failure.message}`
    } finally {
      refreshing.value = false
    }
  }

  /** Resolves true on success, false when the workspace/account is no longer current. */
  async function awaitTask(statusUrl: string): Promise<boolean> {
    for (let poll = 0; poll < EQUIPMENT_MAX_POLLS; poll += 1) {
      if (!current()) return false
      const status = await tasks.readTaskStatus(statusUrl)
      if (!current()) return false
      if (status.state === 'SUCCEEDED') return true
      if (status.state === 'FAILED') {
        throw new Error(status.failure ? status.failure.message : 'The equipment update failed.')
      }
      await sleep(EQUIPMENT_POLL_MS)
      if (!current()) return false
    }
    throw new Error('The equipment update is taking longer than expected.')
  }

  return {
    rosterPhase, rosterResponse, rosterFailure, roster, rosterIsRetained, loadRoster,
    selectedName, detail, shownDetail, detailIsRetained, detailResponse, detailFailure, detailPhase, select, back, loadDetail,
    equipmentPhase, equipmentResponse, equipmentFailure, equipmentTabs, equipmentIsRetained, equipmentSource,
    activeTab, inspectedTab, shownTab, inspect, loadEquipment,
    refreshing, refreshMessage, refreshEquipment,
    section, showSection, inventoryPhase, inventoryResponse, inventoryFailure, inventorySource, inventoryIsRetained,
    equipmentOlderThanInventory, inventoryOlderThanEquipment, loadInventory,
    buildPhase, buildResponse, activeElite, loadBuildTabs
  }
}
