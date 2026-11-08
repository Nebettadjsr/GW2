<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { connection } from '@/api/accountSession'
import { charactersApi, type CharactersApi } from '@/api/charactersApi'
import { syncApi } from '@/api/syncApi'
import type { CharacterOverview } from '@/api/types'
import PageHeader from '@/shell/PageHeader.vue'
import EquipmentPanel from './EquipmentPanel.vue'
import EquipmentTabSelector from './EquipmentTabSelector.vue'
import CharacterSummary from './CharacterSummary.vue'
import { characterIcon } from './characterIcons'
import InventoryPanel from './InventoryPanel.vue'
import { useCharacters } from './useCharacters'
import AutomaticRefreshStatus from '@/account/AutomaticRefreshStatus.vue'
import { useAutomaticAccountRefresh } from '@/account/useAutomaticAccountRefresh'

/**
 * Characters: a compact roster of the fields the backend supplies, and the selected character's
 * equipment (STORY-CHAR-002). Read-only. Detail and equipment load only for the selected character.
 * Roster and equipment coverage are independent: unavailable equipment never hides the roster.
 */
const props = withDefaults(defineProps<{ api?: CharactersApi }>(), { api: () => charactersApi })
const c = useCharacters(props.api, syncApi, undefined, true)
const needsRefresh = (state?: string) => state === 'STALE' || state === 'PENDING'
const parentNeedsRefresh = () => needsRefresh(c.rosterResponse.value?.state)
  || (c.section.value === 'inventory' && needsRefresh(c.inventoryResponse.value?.state))
const optionalNeedsRefresh = () => c.selectedName.value !== null && (
  (c.section.value === 'equipment' && needsRefresh(c.equipmentResponse.value?.state))
  || needsRefresh(c.buildResponse.value?.state))
async function loadVisible(): Promise<void> {
  await c.loadRoster()
  const name = c.selectedName.value
  if (name === null) return
  await Promise.all([c.loadDetail(name), c.loadBuildTabs(name),
    ...(c.section.value === 'equipment' ? [c.loadEquipment(name)] : []),
    ...(c.section.value === 'inventory' ? [c.loadInventory(name)] : [])])
}
const automatic = useAutomaticAccountRefresh({
  operation: 'CHARACTERS_SYNC',
  load: loadVisible,
  stale: () => parentNeedsRefresh() || optionalNeedsRefresh(),
  timestamps: () => [c.roster.value?.freshness?.completedAt,
    ...(c.selectedName.value === null ? [] : [c.buildResponse.value?.builds?.completedAt,
      ...(c.section.value === 'equipment' ? [c.equipmentSource.value?.equipment?.completedAt] : [])]),
    ...(c.section.value === 'inventory' ? [c.inventorySource.value?.completedAt] : [])],
  readFailed: () => c.rosterFailure.value !== null || (c.selectedName.value !== null && (
    c.detailFailure.value !== null || (c.section.value === 'equipment' && c.equipmentFailure.value !== null)
    || (c.section.value === 'inventory' && c.inventoryFailure.value !== null))),
  refresh: async () => {
    if (parentNeedsRefresh()) { await automatic.refreshSource(); if (!live()) return; await loadVisible() }
    if (!live()) return
    if (c.section.value === 'equipment' && needsRefresh(c.equipmentResponse.value?.state)) {
      await c.refreshEquipment()
      if (c.refreshMessage.value) throw new Error(c.refreshMessage.value)
    }
    const name = c.selectedName.value
    if (live() && name !== null && needsRefresh(c.buildResponse.value?.state)) await c.recoverBuilds(name)
  }
})
onMounted(() => { void c.loadRoster() })

// Return from detail to the same place in a long roster: remember the row and page scroll when a
// character is opened, then restore selection focus and scroll once the roster is rendered again.
const generation = connection.generation
let unmounted = false
onBeforeUnmount(() => { unmounted = true })
const live = (): boolean => !unmounted && automatic.active.value && generation === connection.generation
const rowRefs = new Map<string, HTMLElement>()
function setRow(name: string, el: unknown): void {
  if (el instanceof HTMLElement) rowRefs.set(name, el)
  else rowRefs.delete(name)
}
const headerRef = ref<InstanceType<typeof PageHeader> | null>(null)
let origin: { name: string; scrollY: number } | null = null
async function open(name: string): Promise<void> {
  origin = { name, scrollY: window.scrollY }
  c.select(name)
  await nextTick()
  // Move keyboard focus into the opened detail (its heading) once it is rendered.
  if (live() && c.selectedName.value === name) headerRef.value?.focusHeading({ preventScroll: true })
}
async function back(): Promise<void> {
  const from = origin
  c.back()
  await nextTick()
  if (!from || !live() || c.selectedName.value !== null) return
  rowRefs.get(from.name)?.focus({ preventScroll: true })
  window.scrollTo(0, from.scrollY) // the browser clamps this when the roster is shorter
}

type SectionId = 'equipment' | 'inventory'
const SECTIONS: { id: SectionId; label: string }[] = [{ id: 'equipment', label: 'Equipment' }, { id: 'inventory', label: 'Inventory' }]
const tabRefs = new Map<SectionId, HTMLElement>()
function setTab(id: SectionId, el: unknown): void {
  if (el instanceof HTMLElement) tabRefs.set(id, el)
  else tabRefs.delete(id)
}
/** Arrow/Home/End move between section tabs (roving tabindex); selection follows focus. */
async function onTabKey(event: KeyboardEvent): Promise<void> {
  const i = SECTIONS.findIndex((t) => t.id === c.section.value)
  let n = i
  if (event.key === 'ArrowRight') n = (i + 1) % SECTIONS.length
  else if (event.key === 'ArrowLeft') n = (i - 1 + SECTIONS.length) % SECTIONS.length
  else if (event.key === 'Home') n = 0
  else if (event.key === 'End') n = SECTIONS.length - 1
  else return
  event.preventDefault()
  const target = SECTIONS[n]!.id
  const name = c.selectedName.value
  c.showSection(target)
  await nextTick()
  if (live() && c.selectedName.value === name) tabRefs.get(target)?.focus()
}

type RosterSort = 'playtime' | 'birthday' | 'age' | 'deaths' | 'name' | 'profession'
const rosterSort = ref<RosterSort>('playtime')
const ROSTER_SORTS: { value: RosterSort; label: string }[] = [
  { value: 'playtime', label: 'Playtime' },
  { value: 'birthday', label: 'Next birthday' },
  { value: 'age', label: 'Age' },
  { value: 'deaths', label: 'Deaths' },
  { value: 'name', label: 'Alphabetical' },
  { value: 'profession', label: 'Profession' }
]
function dateOrder(value: string | null | undefined): number | null {
  if (!value) return null
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : null
}
function compareNumbers(left: number | null | undefined, right: number | null | undefined, descending = false): number {
  const leftKnown = typeof left === 'number' && Number.isFinite(left)
  const rightKnown = typeof right === 'number' && Number.isFinite(right)
  if (!leftKnown) return rightKnown ? 1 : 0
  if (!rightKnown) return -1
  return descending ? right - left : left - right
}
function compareCharacters(left: CharacterOverview, right: CharacterOverview): number {
  let order = 0
  switch (rosterSort.value) {
    case 'playtime': order = compareNumbers(left.ageSeconds, right.ageSeconds, true); break
    case 'birthday': order = compareNumbers(dateOrder(left.nextBirthdayAt), dateOrder(right.nextBirthdayAt)); break
    case 'age': order = compareNumbers(dateOrder(left.createdAt), dateOrder(right.createdAt)); break
    case 'deaths': order = compareNumbers(left.deaths, right.deaths, true); break
    case 'profession': order = left.profession.localeCompare(right.profession); break
  }
  return order || left.name.localeCompare(right.name)
}
const characters = computed(() => [...(c.roster.value?.characters ?? [])].sort(compareCharacters))
const professionColors: Record<string, string> = {
  Guardian: '#72c1d9', Warrior: '#ffd166', Engineer: '#d09c59', Ranger: '#8cdc82',
  Thief: '#c08f95', Elementalist: '#f68a87', Mesmer: '#b679d5', Necromancer: '#78d0b0', Revenant: '#d16e5a'
}
const rosterState = computed(() => c.rosterResponse.value)
const equipmentState = computed(() => c.equipmentResponse.value)
const equipmentProblem = computed(() => {
  const r = equipmentState.value
  return r !== null && !(r.state === 'AVAILABLE' || r.state === 'STALE')
})
const inventoryState = computed(() => c.inventoryResponse.value)
const inventoryProblem = computed(() => {
  const r = inventoryState.value
  return r !== null && !(r.state === 'AVAILABLE' || r.state === 'STALE')
})
</script>

<template>
  <div class="screen characters" data-test="characters-screen">
    <PageHeader ref="headerRef" :heading="c.selectedName.value ?? 'Characters'">
      <template v-if="c.selectedName.value !== null && c.shownDetail.value && characterIcon(c.shownDetail.value.profession)" #icon>
        <img :src="characterIcon(c.shownDetail.value.profession)!" width="28" height="28" alt="" data-test="profession-glyph" />
      </template>
      <template v-if="c.selectedName.value !== null && c.shownDetail.value" #subtitle>
        <span :style="{ color: professionColors[c.shownDetail.value.profession] }" data-test="character-subtitle">Lvl {{ c.shownDetail.value.level ?? '?' }} {{ c.shownDetail.value.race }} {{ c.activeElite.value ?? c.shownDetail.value.profession }}</span>
      </template>
      <template v-if="c.selectedName.value !== null || characters.length > 0" #actions>
        <button v-if="c.selectedName.value !== null" type="button" data-test="character-back" @click="back()">← All characters</button>
        <label v-else class="roster-sort">
          <span>Sort by</span>
          <select v-model="rosterSort" data-test="roster-sort">
            <option v-for="sort in ROSTER_SORTS" :key="sort.value" :value="sort.value">{{ sort.label }}</option>
          </select>
        </label>
      </template>
    </PageHeader>
    <AutomaticRefreshStatus :updating="automatic.updating.value" :failed="automatic.failed.value" :has-data="!!c.roster.value" />

    <!-- Roster -->
    <p v-if="c.rosterPhase.value === 'loading' && !c.roster.value" class="notice notice--info" role="status" data-test="roster-loading">Loading characters…</p>
    <div v-if="c.rosterFailure.value" class="notice notice--error" role="alert" data-test="roster-error">
      {{ c.roster.value ? 'Could not update the character list. Showing the previous saved list; it may be out of date.' : 'Could not load characters. This does not mean you have none.' }}
      <details><summary>Error details</summary>{{ c.rosterFailure.value.code }} — {{ c.rosterFailure.value.message }}</details>
      <button type="button" data-test="roster-retry" @click="c.loadRoster()">Try again</button>
    </div>
    <p v-else-if="rosterState && !c.roster.value" class="notice" role="status" data-test="roster-state">
      <template v-if="rosterState.state === 'PENDING'">Characters have not been synchronized yet.</template>
      <template v-else>{{ rosterState.message ?? 'Characters are unavailable.' }}</template>
      <span class="meta"> ({{ rosterState.state }}<template v-if="rosterState.reason">: {{ rosterState.reason }}</template>)</span>
    </p>
    <p v-else-if="c.rosterIsRetained.value && !c.rosterFailure.value" class="notice" data-test="roster-retained">
      Showing the previous saved character list; the latest read reported {{ rosterState?.state }}.
    </p>

    <p v-if="c.roster.value && characters.length === 0" class="notice" data-test="roster-empty">No characters were found on this account.</p>

    <div v-if="c.selectedName.value === null && characters.length > 0" class="roster-scroll">
      <ul class="roster" data-test="roster">
        <li v-for="(ch, index) in characters" :key="ch.name" class="roster__row" data-test="roster-row">
          <button type="button" class="roster__item" data-test="roster-item" :data-name="ch.name"
            :aria-describedby="`roster-summary-${index}`" :ref="(el) => setRow(ch.name, el)" @click="open(ch.name)">
            <strong class="roster__name">
              <img v-if="characterIcon(ch.profession)" :src="characterIcon(ch.profession)!" width="24" height="24" alt="" />
              {{ ch.name }}
            </strong>
            <span :style="{ color: professionColors[ch.profession] }">Lvl {{ ch.level ?? '?' }} {{ ch.race }} {{ ch.profession }}</span>
          </button>
          <CharacterSummary :id="`roster-summary-${index}`" :detail="ch" roster />
        </li>
      </ul>
    </div>

    <!-- Selected character -->
    <section v-if="c.selectedName.value !== null" class="detail" data-test="character-detail">
      <p v-if="c.detailPhase.value === 'loading'" class="meta" role="status" data-test="detail-loading">Loading character…</p>
      <p v-if="c.detailFailure.value" class="notice notice--error" role="alert" data-test="detail-error">
        Could not load this character's details: {{ c.detailFailure.value.message }}
      </p>
      <p v-else-if="c.detailResponse.value && !c.detail.value" class="notice" data-test="detail-state">
        {{ c.detailResponse.value.message ?? 'Character details are unavailable.' }} ({{ c.detailResponse.value.state }})
      </p>
      <p v-if="c.detailIsRetained.value" class="notice" role="status" data-test="detail-retained">
        Showing the saved character list entry; the latest detail read did not succeed, so these facts may be out of date.
      </p>
      <CharacterSummary v-if="c.shownDetail.value" :detail="c.shownDetail.value" />

      <div class="character-navigation">
      <div class="sections" role="tablist" aria-label="Character sections" data-test="section-tablist" @keydown="onTabKey">
        <button v-for="t in SECTIONS" :key="t.id" :ref="(el) => setTab(t.id, el)" type="button" role="tab" :id="`section-tab-${t.id}`"
          aria-controls="section-panel" :aria-selected="c.section.value === t.id" :tabindex="c.section.value === t.id ? 0 : -1"
          :class="{ 'sections__tab--selected': c.section.value === t.id }" :data-test="`section-${t.id}`" @click="c.showSection(t.id)">{{ t.label }}</button>
      </div>
      <EquipmentTabSelector v-if="c.section.value === 'equipment' && c.equipmentTabs.value?.length"
        :tabs="c.equipmentTabs.value" :selected="c.shownTab.value?.tab" @inspect="c.inspect" />
      </div>
      <div id="section-panel" role="tabpanel" :aria-labelledby="`section-tab-${c.section.value}`">
      <template v-if="c.section.value === 'equipment'">
      <p v-if="c.equipmentOlderThanInventory.value && !automatic.updating.value" class="notice" role="status" data-test="equipment-older">
        {{ c.equipmentPhase.value === 'loading'
          ? 'The equipment shown may describe an older saved character list than the inventory. Reading it again…'
          : 'The equipment shown may describe an older saved character list than the inventory, and it could not be brought up to date yet.' }}
      </p>
      <p v-if="c.equipmentPhase.value === 'loading' && !c.equipmentSource.value" class="meta" role="status" data-test="equipment-loading">
        {{ c.equipmentSource.value ? 'Updating equipment. Showing the previous saved data below.' : 'Loading equipment…' }}
      </p>
      <div v-if="c.equipmentFailure.value" class="notice notice--error" role="alert" data-test="equipment-error">
        Could not read equipment{{ c.equipmentSource.value ? '; showing the previous saved data, which may be out of date' : '' }}.
        <details><summary>Error details</summary>{{ c.equipmentFailure.value.code }} — {{ c.equipmentFailure.value.message }}</details>
      </div>
      <div v-else-if="equipmentProblem && !automatic.updating.value" class="notice" role="status" data-test="equipment-state">
        <template v-if="equipmentState?.state === 'GRANT_REQUIRED'">Your API key does not allow reading equipment. </template>
        <template v-else-if="equipmentState?.state === 'ENDPOINT_RESTRICTED'">Equipment is restricted for this account. </template>
        <template v-else-if="equipmentState?.state === 'PENDING'">Equipment has not been synchronized yet. </template>
        <template v-else-if="equipmentState?.state === 'NOT_FOUND'">This character was not found. </template>
        {{ equipmentState?.message }}
        <span class="meta">({{ equipmentState?.state }}<template v-if="equipmentState?.reason">: {{ equipmentState.reason }}</template>)</span>
        <template v-if="c.equipmentSource.value"> Showing the previous saved data below; it may be out of date.</template>
      </div>
      <p v-else-if="c.equipmentIsRetained.value && !automatic.updating.value" class="notice" data-test="equipment-retained">Showing previously saved equipment.</p>
      <p class="meta" v-if="c.refreshMessage.value" role="alert" data-test="equipment-refresh-error">{{ c.refreshMessage.value }}</p>
      <p v-if="c.refreshMessage.value || c.equipmentFailure.value || equipmentState?.state === 'UNAVAILABLE'" class="meta">
        <a href="#/synchronization">Manage account refresh in System Status</a>
      </p>

      <EquipmentPanel v-if="c.equipmentTabs.value" :tabs="c.equipmentTabs.value" :shown="c.shownTab.value" :profession="c.shownDetail.value?.profession" :shared-equipment="c.equipmentSource.value?.sharedEquipment" />
      </template>
      <template v-else>
        <p v-if="c.inventoryOlderThanEquipment.value && !automatic.updating.value" class="notice" role="status" data-test="inventory-older">
          {{ c.inventoryPhase.value === 'loading'
            ? 'The inventory shown may describe an older saved character list than the equipment. Reading it again…'
            : 'The inventory shown may describe an older saved character list than the equipment, and it could not be brought up to date yet.' }}
        </p>
        <p v-if="c.inventoryPhase.value === 'loading' && !c.inventorySource.value" class="meta" role="status" data-test="inventory-loading">
          {{ c.inventorySource.value ? 'Updating inventory. Showing the previous saved data below.' : 'Loading inventory…' }}
        </p>
        <div v-if="c.inventoryFailure.value" class="notice notice--error" role="alert" data-test="inventory-error">
          Could not read inventory{{ c.inventorySource.value ? '; showing the previous saved data, which may be out of date' : '' }}.
          <details><summary>Error details</summary>{{ c.inventoryFailure.value.code }} — {{ c.inventoryFailure.value.message }}</details>
          <button type="button" data-test="inventory-retry" @click="c.loadInventory(c.selectedName.value!)">Try again</button>
        </div>
        <div v-else-if="inventoryProblem && !automatic.updating.value" class="notice" role="status" data-test="inventory-state">
          <template v-if="inventoryState?.state === 'GRANT_REQUIRED'">Your API key does not allow reading inventory. </template>
          <template v-else-if="inventoryState?.state === 'ENDPOINT_RESTRICTED'">Inventory is restricted for this account. </template>
          <template v-else-if="inventoryState?.state === 'PENDING'">
            <template v-if="inventoryState.reason === 'REFRESH_IN_PROGRESS'">An account refresh is in progress. Check again when it has finished. </template>
            <template v-else>Inventory has not been synchronized yet. </template>
          </template>
          <template v-else-if="inventoryState?.state === 'UNAVAILABLE' && inventoryState.reason === 'LAYOUT_REFRESH_REQUIRED'">
            The saved character data needs an account refresh before inventory can be shown. Start one from
            <a href="#/synchronization" data-test="inventory-system-status">System Status</a>, then check again.
          </template>
          <template v-else-if="inventoryState?.state === 'UNAVAILABLE' && inventoryState.reason === 'REFRESH_FAILED'">
            The first inventory refresh failed. Try an account refresh from
            <a href="#/synchronization" data-test="inventory-system-status">System Status</a>, then check again.
          </template>
          <template v-else-if="inventoryState?.state === 'NOT_FOUND'">This character was not found. </template>
          {{ inventoryState?.message }}
          <span class="meta">({{ inventoryState?.state }}<template v-if="inventoryState?.reason">: {{ inventoryState.reason }}</template>)</span>
          <template v-if="c.inventorySource.value"> Showing the previous saved data below; it may be out of date.</template>
          <button v-if="inventoryState?.state !== 'NOT_FOUND'" type="button" data-test="inventory-recheck" :disabled="c.inventoryPhase.value === 'loading'"
            @click="c.loadInventory(c.selectedName.value!)">Check again</button>
        </div>
        <p v-else-if="c.inventoryIsRetained.value && !automatic.updating.value" class="notice" data-test="inventory-retained">Showing previously saved inventory.</p>
        <p v-if="inventoryState?.state === 'STALE' && inventoryState.reason === 'REFRESH_FAILED'" class="notice" role="status" data-test="inventory-stale-refresh-failed">
          The latest account refresh failed{{ inventoryState.message ? ': ' + inventoryState.message : '.' }}
          Try an account refresh from <a href="#/synchronization" data-test="inventory-system-status">System Status</a>, then
          <button type="button" data-test="inventory-recheck" :disabled="c.inventoryPhase.value === 'loading'" @click="c.loadInventory(c.selectedName.value!)">check again</button>.
        </p>
        <p v-if="c.inventorySource.value?.contents === 'ALL_SLOTS_EMPTY'" class="meta" data-test="inventory-all-empty">All listed bag slots are empty.</p>
        <InventoryPanel v-if="c.inventorySource.value?.bags" :bags="c.inventorySource.value.bags" />
      </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.screen { max-width: 1280px; margin-inline: auto; }
.roster-sort { display: flex; align-items: center; gap: var(--space-2); }
.roster-sort select { min-width: 11rem; }
.roster-scroll { max-width: 100%; overflow-x: auto; }
.roster { list-style: none; margin: 0; padding: 0; min-width: 1050px; }
.roster__row { position: relative; display: grid; grid-template-columns: minmax(200px, 1.2fr) minmax(0, 5fr); gap: 24px; align-items: start; padding: 20px 8px; border-bottom: 1px solid var(--color-border); }
.roster__row:hover { background: var(--color-raised); }
.roster__item { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; width: 100%; min-height: 0; text-align: left; padding: 0; border: 0; background: transparent; }
.roster__item::after { content: ''; position: absolute; inset: 0; z-index: 1; }
.roster__name { display: flex; align-items: center; gap: 8px; font-size: var(--text-lg); line-height: 1.3; }
.roster__name img { flex-shrink: 0; }
.character-navigation { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 12px 0 8px; border-bottom: 1px solid var(--border, #888); }
.sections { display: flex; align-self: stretch; gap: 2px; }
.sections button.sections__tab--selected { font-weight: 700; background: var(--color-raised); }
.sections button { border: 1px solid var(--border, #888); border-bottom: 0; background: transparent; padding: 6px 12px; cursor: pointer; }
</style>
