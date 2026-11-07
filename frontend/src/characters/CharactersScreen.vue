<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { connection } from '@/api/accountSession'
import { charactersApi, type CharactersApi } from '@/api/charactersApi'
import { syncApi } from '@/api/syncApi'
import PageHeader from '@/shell/PageHeader.vue'
import EquipmentPanel from './EquipmentPanel.vue'
import CharacterFacts from './CharacterFacts.vue'
import InventoryPanel from './InventoryPanel.vue'
import { useCharacters } from './useCharacters'

/**
 * Characters: a compact roster of the fields the backend supplies, and the selected character's
 * equipment (STORY-CHAR-002). Read-only. Detail and equipment load only for the selected character.
 * Roster and equipment coverage are independent: unavailable equipment never hides the roster.
 */
const props = withDefaults(defineProps<{ api?: CharactersApi }>(), { api: () => charactersApi })
const c = useCharacters(props.api, syncApi)
onMounted(() => { void c.loadRoster() })

// Return from detail to the same place in a long roster: remember the row and page scroll when a
// character is opened, then restore selection focus and scroll once the roster is rendered again.
const generation = connection.generation
let unmounted = false
onBeforeUnmount(() => { unmounted = true })
const live = (): boolean => !unmounted && generation === connection.generation
const rowRefs = new Map<string, HTMLElement>()
function setRow(name: string, el: unknown): void {
  if (el instanceof HTMLElement) rowRefs.set(name, el)
  else rowRefs.delete(name)
}
const headingRef = ref<HTMLElement | null>(null)
const backRef = ref<HTMLElement | null>(null)
let origin: { name: string; scrollY: number } | null = null
async function open(name: string): Promise<void> {
  origin = { name, scrollY: window.scrollY }
  c.select(name)
  await nextTick()
  // Move keyboard focus into the opened detail (its heading) once it is rendered.
  if (live() && c.selectedName.value === name) (headingRef.value ?? backRef.value)?.focus({ preventScroll: true })
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

const characters = computed(() => c.roster.value?.characters ?? [])
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
const canRefresh = computed(() => {
  const s = equipmentState.value?.state
  return s === 'PENDING' || s === 'STALE' || s === 'UNAVAILABLE' || c.equipmentFailure.value !== null
})
function fmt(value?: string | null): string | null {
  if (!value) return null
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? null : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}
</script>

<template>
  <div class="screen characters" data-test="characters-screen">
    <PageHeader heading="Characters" intro="Your characters and their saved equipment. Read-only." />

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
    <p v-if="c.roster.value?.state === 'STALE'" class="meta" data-test="roster-stale">This saved list may be out of date.</p>
    <p v-if="c.roster.value?.freshness?.completedAt" class="meta" data-test="roster-freshness">
      Last synchronized: {{ fmt(c.roster.value.freshness.completedAt) ?? 'unknown' }}.
    </p>

    <p v-if="c.roster.value && characters.length === 0" class="notice" data-test="roster-empty">No characters were found on this account.</p>

    <div v-if="c.selectedName.value === null && characters.length > 0">
      <ul class="roster" data-test="roster">
        <li v-for="ch in characters" :key="ch.name">
          <button type="button" class="roster__item" data-test="roster-item" :data-name="ch.name" :ref="(el) => setRow(ch.name, el)" @click="open(ch.name)">
            <strong>{{ ch.name }}</strong>
            <span>Level {{ ch.level ?? '?' }} {{ ch.race }} {{ ch.profession }}</span>
            <span v-if="ch.craftingDisciplines.length > 0" class="meta">
              {{ ch.craftingDisciplines.map((d) => `${d.discipline} ${d.rating}${d.active ? '' : ' (inactive)'}`).join(', ') }}
            </span>
          </button>
        </li>
      </ul>
    </div>

    <!-- Selected character -->
    <section v-if="c.selectedName.value !== null" class="detail" data-test="character-detail">
      <button ref="backRef" type="button" data-test="character-back" @click="back()">← All characters</button>
      <h2 ref="headingRef" tabindex="-1" data-test="character-name">{{ c.selectedName.value }}</h2>
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
      <CharacterFacts v-if="c.shownDetail.value && c.section.value !== 'inventory'" :detail="c.shownDetail.value" :created="fmt(c.shownDetail.value.createdAt)" />

      <div class="sections" role="tablist" aria-label="Character sections" data-test="section-tablist" @keydown="onTabKey">
        <button v-for="t in SECTIONS" :key="t.id" :ref="(el) => setTab(t.id, el)" type="button" role="tab" :id="`section-tab-${t.id}`"
          aria-controls="section-panel" :aria-selected="c.section.value === t.id" :tabindex="c.section.value === t.id ? 0 : -1"
          :class="{ 'sections__tab--selected': c.section.value === t.id }" :data-test="`section-${t.id}`" @click="c.showSection(t.id)">{{ t.label }}</button>
      </div>
      <div id="section-panel" role="tabpanel" :aria-labelledby="`section-tab-${c.section.value}`">
      <template v-if="c.section.value === 'equipment'">
      <p v-if="c.equipmentOlderThanInventory.value" class="notice" role="status" data-test="equipment-older">
        {{ c.equipmentPhase.value === 'loading'
          ? 'The equipment shown may describe an older saved character list than the inventory. Reading it again…'
          : 'The equipment shown may describe an older saved character list than the inventory, and it could not be brought up to date yet.' }}
      </p>
      <p v-if="c.equipmentPhase.value === 'loading'" class="meta" role="status" data-test="equipment-loading">
        {{ c.equipmentSource.value ? 'Updating equipment. Showing the previous saved data below.' : 'Loading equipment…' }}
      </p>
      <div v-if="c.equipmentFailure.value" class="notice notice--error" role="alert" data-test="equipment-error">
        Could not read equipment{{ c.equipmentSource.value ? '; showing the previous saved data, which may be out of date' : '' }}.
        <details><summary>Error details</summary>{{ c.equipmentFailure.value.code }} — {{ c.equipmentFailure.value.message }}</details>
      </div>
      <div v-else-if="equipmentProblem" class="notice" role="status" data-test="equipment-state">
        <template v-if="equipmentState?.state === 'GRANT_REQUIRED'">Your API key does not allow reading equipment. </template>
        <template v-else-if="equipmentState?.state === 'ENDPOINT_RESTRICTED'">Equipment is restricted for this account. </template>
        <template v-else-if="equipmentState?.state === 'PENDING'">Equipment has not been synchronized yet. </template>
        <template v-else-if="equipmentState?.state === 'NOT_FOUND'">This character was not found. </template>
        {{ equipmentState?.message }}
        <span class="meta">({{ equipmentState?.state }}<template v-if="equipmentState?.reason">: {{ equipmentState.reason }}</template>)</span>
        <template v-if="c.equipmentSource.value"> Showing the previous saved data below; it may be out of date.</template>
      </div>
      <p v-else-if="c.equipmentIsRetained.value" class="notice" data-test="equipment-retained">Showing previously saved equipment.</p>
      <p v-if="equipmentState?.state === 'STALE'" class="meta" data-test="equipment-stale">This saved equipment may be out of date.</p>
      <p v-if="c.equipmentSource.value?.equipment?.completedAt" class="meta" data-test="equipment-freshness">
        Equipment last synchronized: {{ fmt(c.equipmentSource.value.equipment.completedAt) ?? 'unknown' }}.
      </p>
      <p class="meta" v-if="c.refreshMessage.value" role="alert" data-test="equipment-refresh-error">{{ c.refreshMessage.value }}</p>
      <button v-if="canRefresh" type="button" :disabled="c.refreshing.value" data-test="equipment-refresh" @click="c.refreshEquipment()">
        {{ c.refreshing.value ? 'Updating equipment…' : 'Update equipment' }}
      </button>

      <EquipmentPanel v-if="c.equipmentTabs.value" :tabs="c.equipmentTabs.value" :shown="c.shownTab.value" @inspect="c.inspect" />
      </template>
      <template v-else>
        <p v-if="c.inventoryOlderThanEquipment.value" class="notice" role="status" data-test="inventory-older">
          {{ c.inventoryPhase.value === 'loading'
            ? 'The inventory shown may describe an older saved character list than the equipment. Reading it again…'
            : 'The inventory shown may describe an older saved character list than the equipment, and it could not be brought up to date yet.' }}
        </p>
        <p v-if="c.inventoryPhase.value === 'loading'" class="meta" role="status" data-test="inventory-loading">
          {{ c.inventorySource.value ? 'Updating inventory. Showing the previous saved data below.' : 'Loading inventory…' }}
        </p>
        <div v-if="c.inventoryFailure.value" class="notice notice--error" role="alert" data-test="inventory-error">
          Could not read inventory{{ c.inventorySource.value ? '; showing the previous saved data, which may be out of date' : '' }}.
          <details><summary>Error details</summary>{{ c.inventoryFailure.value.code }} — {{ c.inventoryFailure.value.message }}</details>
          <button type="button" data-test="inventory-retry" @click="c.loadInventory(c.selectedName.value!)">Try again</button>
        </div>
        <div v-else-if="inventoryProblem" class="notice" role="status" data-test="inventory-state">
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
        <p v-else-if="c.inventoryIsRetained.value" class="notice" data-test="inventory-retained">Showing previously saved inventory.</p>
        <p v-if="inventoryState?.state === 'STALE'" class="meta" data-test="inventory-stale">This saved inventory may be out of date.</p>
        <p v-if="inventoryState?.state === 'STALE' && inventoryState.reason === 'REFRESH_FAILED'" class="notice" role="status" data-test="inventory-stale-refresh-failed">
          The latest account refresh failed{{ inventoryState.message ? ': ' + inventoryState.message : '.' }}
          Try an account refresh from <a href="#/synchronization" data-test="inventory-system-status">System Status</a>, then
          <button type="button" data-test="inventory-recheck" :disabled="c.inventoryPhase.value === 'loading'" @click="c.loadInventory(c.selectedName.value!)">check again</button>.
        </p>
        <p v-if="c.inventorySource.value?.completedAt" class="meta" data-test="inventory-freshness">
          Last synchronized: {{ fmt(c.inventorySource.value.completedAt) ?? 'unknown' }}.
        </p>
        <p v-if="c.inventorySource.value?.contents === 'ALL_SLOTS_EMPTY'" class="meta" data-test="inventory-all-empty">All listed bag slots are empty.</p>
        <InventoryPanel v-if="c.inventorySource.value?.bags" :bags="c.inventorySource.value.bags">
          <template #facts><CharacterFacts v-if="c.shownDetail.value" :detail="c.shownDetail.value" :created="fmt(c.shownDetail.value.createdAt)" /></template>
        </InventoryPanel>
        <CharacterFacts v-else-if="c.shownDetail.value" :detail="c.shownDetail.value" :created="fmt(c.shownDetail.value.createdAt)" />
      </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.screen { max-width: 1280px; margin-inline: auto; }
.roster { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 8px; }
.roster__item { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; width: 100%; text-align: left; padding: 8px; }
.sections { display: flex; gap: 2px; margin: 12px 0 8px; border-bottom: 1px solid var(--border, #888); }
.sections button { border: 1px solid var(--border, #888); border-bottom: 0; background: transparent; padding: 6px 12px; cursor: pointer; }
.sections__tab--selected { font-weight: 700; background: rgba(128, 128, 128, 0.2); }
</style>
