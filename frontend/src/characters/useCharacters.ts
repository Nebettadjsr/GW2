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
  CharacterEquipmentTabsResponse, CharacterOverview, CharacterReadResponse, EquipmentTab
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
    if (equipmentResponse.value?.state === 'PENDING' && !autoRecoveryTried) {
      autoRecoveryTried = true
      void refreshEquipment()
    }
  }

  function select(name: string): void {
    if (selectedName.value === name) return
    selectedName.value = name
    detail.value = null
    detailResponse.value = null
    equipmentResponse.value = null
    inspectedTab.value = initialInspection(name)
    refreshMessage.value = null
    // Invalidate anything in flight for the previous character.
    detailId += 1
    equipmentId += 1
    void loadDetail(name)
    void loadEquipment(name)
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
    inspectedTab.value = null
    detailPhase.value = 'idle'
    equipmentPhase.value = 'idle'
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
      if (current() && selectedName.value === name) await loadEquipment(name)
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
    selectedName, detail, detailResponse, detailFailure, detailPhase, select, back, loadDetail,
    equipmentPhase, equipmentResponse, equipmentFailure, equipmentTabs, equipmentIsRetained, equipmentSource,
    activeTab, inspectedTab, shownTab, inspect, loadEquipment,
    refreshing, refreshMessage, refreshEquipment
  }
}
