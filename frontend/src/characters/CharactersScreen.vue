<script setup lang="ts">
import { computed, nextTick, onMounted } from 'vue'
import { charactersApi, type CharactersApi } from '@/api/charactersApi'
import { syncApi } from '@/api/syncApi'
import PageHeader from '@/shell/PageHeader.vue'
import EquipmentPanel from './EquipmentPanel.vue'
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
let origin: { name: string; scrollY: number } | null = null
function open(name: string): void {
  origin = { name, scrollY: window.scrollY }
  c.select(name)
}
async function back(): Promise<void> {
  const from = origin
  c.back()
  await nextTick()
  if (!from) return
  const row = Array.from(document.querySelectorAll<HTMLElement>('[data-test="roster-item"]'))
    .find((el) => el.dataset.name === from.name)
  row?.focus({ preventScroll: true })
  window.scrollTo(0, from.scrollY) // the browser clamps this when the roster is shorter
}

const characters = computed(() => c.roster.value?.characters ?? [])
const rosterState = computed(() => c.rosterResponse.value)
const equipmentState = computed(() => c.equipmentResponse.value)
const equipmentProblem = computed(() => {
  const r = equipmentState.value
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
          <button type="button" class="roster__item" data-test="roster-item" :data-name="ch.name" @click="open(ch.name)">
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
      <button type="button" data-test="character-back" @click="back()">← All characters</button>
      <h2 data-test="character-name">{{ c.selectedName.value }}</h2>
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
      <dl v-if="c.shownDetail.value" class="facts" data-test="character-facts">
        <dt>Profession</dt><dd>{{ c.shownDetail.value.profession }}</dd>
        <dt>Race</dt><dd>{{ c.shownDetail.value.race }}</dd>
        <dt>Level</dt><dd>{{ c.shownDetail.value.level ?? 'unknown' }}</dd>
        <template v-if="fmt(c.shownDetail.value.createdAt)"><dt>Created</dt><dd>{{ fmt(c.shownDetail.value.createdAt) }}</dd></template>
        <template v-if="c.shownDetail.value.craftingDisciplines.length > 0">
          <dt>Crafting</dt>
          <dd>{{ c.shownDetail.value.craftingDisciplines.map((d) => `${d.discipline} ${d.rating}${d.active ? '' : ' (inactive)'}`).join(', ') }}</dd>
        </template>
      </dl>

      <h3>Equipment</h3>
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
      <p v-if="equipmentState?.equipment?.completedAt" class="meta" data-test="equipment-freshness">
        Equipment last synchronized: {{ fmt(equipmentState.equipment.completedAt) ?? 'unknown' }}.
      </p>
      <p class="meta" v-if="c.refreshMessage.value" role="alert" data-test="equipment-refresh-error">{{ c.refreshMessage.value }}</p>
      <button v-if="canRefresh" type="button" :disabled="c.refreshing.value" data-test="equipment-refresh" @click="c.refreshEquipment()">
        {{ c.refreshing.value ? 'Updating equipment…' : 'Update equipment' }}
      </button>

      <EquipmentPanel v-if="c.equipmentTabs.value" :tabs="c.equipmentTabs.value" :shown="c.shownTab.value" @inspect="c.inspect" />
    </section>
  </div>
</template>

<style scoped>
.screen { max-width: 960px; margin-inline: auto; }
.roster { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 8px; }
.roster__item { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; width: 100%; text-align: left; padding: 8px; }
.facts { display: grid; grid-template-columns: max-content 1fr; gap: 2px 12px; }
.facts dd { margin: 0; }
</style>
