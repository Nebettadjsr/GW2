<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { BuildIdRef, BuildLegendRef, BuildSkills, BuildSpecialization, BuildTab, BuildTraitRef } from '@/api/types'

/**
 * Read-only saved build tabs (STORY-CHAR-010). Tabs follow the backend's numeric order; the build the
 * game has active is marked separately from the one being inspected. Specialization rows show every
 * supplied minor/major choice, placed only by the backend's tier/order for ORDINARY placements, with
 * the selected traits highlighted. Nothing is edited, inferred or fabricated: unresolved ids stay as ids.
 */
const props = defineProps<{ tabs: BuildTab[]; selected?: number }>()
const emit = defineEmits<{ inspect: [tab: number] }>()
const buttons = ref<HTMLElement[]>([])
const shown = computed(() => props.tabs.find((t) => t.tab === props.selected) ?? null)

const tabName = (t: BuildTab): string => t.name?.trim() || `Build ${t.tab}`
async function onKey(event: KeyboardEvent, index: number): Promise<void> {
  const last = props.tabs.length - 1
  let next = index
  if (event.key === 'ArrowRight') next = index === last ? 0 : index + 1
  else if (event.key === 'ArrowLeft') next = index === 0 ? last : index - 1
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = last
  else return
  event.preventDefault()
  const target = props.tabs[next]
  if (!target) return
  emit('inspect', target.tab)
  await nextTick()
  buttons.value[next]?.focus()
}

const refLabel = (r: BuildIdRef | null | undefined, kind: string): string =>
  !r ? `No ${kind} saved` : r.resolved && r.name ? r.name : `${kind} #${r.id} (details unavailable)`
const legendLabel = (l: BuildLegendRef | null): string =>
  !l ? 'No legend saved' : l.resolved && l.swapSkill ? refLabel(l.swapSkill, 'Skill') : `Legend ${l.id} (details unavailable)`

function groups(s: BuildSkills | undefined): Array<{ key: string; kind: string; ref: BuildIdRef | null }> {
  if (!s) return []
  return [{ key: 'heal', kind: 'Heal', ref: s.heal }, ...(s.utilities ?? []).map((u, i) => ({ key: `utility-${i}`, kind: 'Utility', ref: u })),
    { key: 'elite', kind: 'Elite', ref: s.elite }]
}

interface TraitRow { tier: number; minor: BuildTraitRef[]; major: BuildTraitRef[] }
/** Ordinary choices by backend tier/order; everything else is listed unplaced, never by array position. */
function layout(spec: BuildSpecialization): { rows: TraitRow[]; other: BuildTraitRef[] } {
  const all = [...(spec.minorTraits ?? []), ...(spec.majorTraits ?? [])]
  const rows = new Map<number, TraitRow>()
  const other: BuildTraitRef[] = []
  for (const t of all) {
    if (t.placement !== 'ORDINARY' || t.tier === undefined || t.order === undefined) { other.push(t); continue }
    const row = rows.get(t.tier) ?? { tier: t.tier, minor: [], major: [] }
    ;(t.slot === 'Minor' ? row.minor : row.major).push(t)
    rows.set(t.tier, row)
  }
  const sorted = [...rows.values()].sort((a, b) => a.tier - b.tier)
  for (const r of sorted) { r.minor.sort((a, b) => a.order! - b.order!); r.major.sort((a, b) => a.order! - b.order!) }
  return { rows: sorted, other }
}
const selectedIds = (spec: BuildSpecialization): Set<number> =>
  new Set((spec.traits ?? []).filter((t): t is BuildTraitRef => t !== null).map((t) => t.id))
const traitLabel = (t: BuildTraitRef): string => (t.resolved && t.name ? t.name : `Trait #${t.id} (details unavailable)`)
const specLabel = (s: BuildSpecialization): string => {
  const r = s.specialization
  return !r ? 'No specialization saved' : r.resolved && r.name ? r.name : `Specialization #${r.id} (details unavailable)`
}
/** Selected traits that the offered choices do not show (no choice metadata): still listed, honestly. */
function unshownSelected(spec: BuildSpecialization): BuildTraitRef[] {
  const offered = new Set([...(spec.minorTraits ?? []), ...(spec.majorTraits ?? [])].map((t) => t.id))
  return (spec.traits ?? []).filter((t): t is BuildTraitRef => t !== null && !offered.has(t.id))
}
</script>

<template>
  <div class="builds" data-test="builds-panel">
    <div class="build-selector">
      <span class="build-selector__label">Build:</span>
      <div class="build-selector__tabs" role="tablist" aria-label="Saved build tabs" data-test="build-tablist">
        <button v-for="(tab, index) in tabs" :key="tab.tab" :ref="el => { if (el) buttons[index] = el as HTMLElement }" type="button" role="tab"
          :id="`build-tab-${tab.tab}`" aria-controls="build-tabpanel" :aria-selected="selected === tab.tab" :tabindex="selected === tab.tab ? 0 : -1"
          :aria-label="`${tabName(tab)}${tab.active ? ' · In use in game' : ''}`" :title="`${tabName(tab)}${tab.active ? ' · In use in game' : ''}`"
          :class="{ 'build-selector__selected': selected === tab.tab }" data-test="build-tab" @click="emit('inspect', tab.tab)" @keydown="onKey($event, index)">
          {{ tab.tab }}<span v-if="tab.active" class="build-selector__active" aria-hidden="true" data-test="build-tab-active">●</span>
        </button>
      </div>
    </div>

    <section v-if="shown" id="build-tabpanel" role="tabpanel" :aria-labelledby="`build-tab-${shown.tab}`" class="build" data-test="build-view">
      <h3 class="build__title" data-test="build-title">{{ tabName(shown) }}
        <span v-if="shown.profession" class="meta" data-test="build-profession">{{ shown.profession }}</span>
        <span v-if="shown.active" class="build__badge" data-test="build-active-badge">In use in game</span>
      </h3>

      <div class="build__specs" data-test="build-specializations">
        <article v-for="(spec, i) in shown.specializations" :key="i" class="spec" data-test="build-spec">
          <header class="spec__head">
            <img v-if="spec.specialization?.iconUrl" :src="spec.specialization.iconUrl" width="32" height="32" alt="" />
            <strong data-test="build-spec-name">{{ specLabel(spec) }}</strong>
            <span v-if="spec.specialization?.elite" class="meta">Elite</span>
          </header>
          <template v-if="spec.minorTraits || spec.majorTraits">
            <div v-for="row in layout(spec).rows" :key="row.tier" class="trait-row" :data-tier="row.tier" data-test="trait-row">
              <span v-for="t in row.minor" :key="t.id" class="trait trait--minor" :class="{ 'trait--selected': selectedIds(spec).has(t.id) }"
                :title="traitLabel(t)" :aria-label="`${traitLabel(t)}, minor${selectedIds(spec).has(t.id) ? ', selected' : ''}`" tabindex="0" data-test="trait">
                <img v-if="t.iconUrl" :src="t.iconUrl" width="32" height="32" alt="" /><span v-else aria-hidden="true">?</span>
              </span>
              <span v-for="t in row.major" :key="t.id" class="trait trait--major" :class="{ 'trait--selected': selectedIds(spec).has(t.id) }"
                :title="traitLabel(t)" :aria-label="`${traitLabel(t)}, major${selectedIds(spec).has(t.id) ? ', selected' : ''}`"
                :aria-current="selectedIds(spec).has(t.id) ? 'true' : undefined" tabindex="0" data-test="trait">
                <img v-if="t.iconUrl" :src="t.iconUrl" width="32" height="32" alt="" /><span v-else aria-hidden="true">?</span>
              </span>
            </div>
            <ul v-if="layout(spec).other.length" class="spec__other meta" aria-label="Choices without a known position" data-test="trait-unplaced">
              <li v-for="t in layout(spec).other" :key="t.id" :class="{ 'trait--selected-text': selectedIds(spec).has(t.id) }">
                {{ traitLabel(t) }} ({{ t.placement.toLowerCase() }}){{ selectedIds(spec).has(t.id) ? ' · selected' : '' }}
              </li>
            </ul>
          </template>
          <ul v-if="unshownSelected(spec).length" class="spec__other meta" aria-label="Selected traits" data-test="trait-selected-list">
            <li v-for="t in unshownSelected(spec)" :key="t.id">{{ traitLabel(t) }} · selected<template v-if="!(spec.minorTraits || spec.majorTraits)"> (choices not available)</template></li>
          </ul>
          <p v-if="!spec.traits" class="meta" data-test="trait-none">No traits saved.</p>
        </article>
        <p v-if="shown.specializations.length === 0" class="meta" data-test="build-no-specs">No specializations saved.</p>
      </div>

      <div class="build__skills">
        <div v-for="g in [{ id: 'land', label: 'Land skills', s: shown.skills }, { id: 'aquatic', label: 'Aquatic skills', s: shown.aquaticSkills }]" :key="g.id"
          :data-test="`skills-${g.id}`">
          <h4>{{ g.label }}</h4>
          <ul v-if="g.s" class="skills">
            <li v-for="x in groups(g.s)" :key="x.key" :title="refLabel(x.ref, x.kind)" data-test="skill">
              <img v-if="x.ref?.iconUrl" :src="x.ref.iconUrl" width="32" height="32" alt="" />
              <span v-else class="skill__blank" aria-hidden="true">?</span>
              <span class="meta">{{ x.kind }}: {{ refLabel(x.ref, 'Skill') }}</span>
            </li>
          </ul>
          <p v-else class="meta">Not saved for this build.</p>
        </div>
      </div>

      <div v-if="shown.legends || shown.aquaticLegends" class="build__facts" data-test="build-legends">
        <h4>Legends</h4>
        <p class="meta">Land: {{ (shown.legends ?? []).map(legendLabel).join(', ') || 'none saved' }}</p>
        <p class="meta">Aquatic: {{ (shown.aquaticLegends ?? []).map(legendLabel).join(', ') || 'none saved' }}</p>
      </div>
      <div v-if="shown.pets || shown.aquaticPets" class="build__facts" data-test="build-pets">
        <h4>Pets</h4>
        <p class="meta">Land: {{ (shown.pets ?? []).map((p) => refLabel(p, 'Pet')).join(', ') || 'none saved' }}</p>
        <p class="meta">Aquatic: {{ (shown.aquaticPets ?? []).map((p) => refLabel(p, 'Pet')).join(', ') || 'none saved' }}</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.builds { display: flex; flex-direction: column; gap: 14px; }
.build-selector { display: flex; align-items: center; gap: 10px; }
.build-selector__label { color: var(--color-text-muted, #a9bcc8); font-size: .9rem; }
.build-selector__tabs { display: flex; gap: 4px; }
.build-selector button { min-width: 36px; padding: 5px 10px; border: 1px solid var(--border, #687780); border-radius: 6px; background: transparent; cursor: pointer; }
.build-selector button.build-selector__selected { background: var(--color-raised); font-weight: 700; }
.build-selector button:focus-visible, .trait:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }
.build-selector__active { margin-left: 6px; color: #dfbc6c; font-size: .55em; vertical-align: middle; }
.build__title { margin: 0; display: flex; gap: 10px; align-items: baseline; }
.build__badge { color: #dfbc6c; font-size: .8rem; }
.build__specs { display: flex; flex-wrap: wrap; gap: 16px; }
.spec { min-width: 240px; padding: 10px; border: 1px solid var(--color-border); border-radius: 6px; display: flex; flex-direction: column; gap: 8px; }
.spec__head { display: flex; align-items: center; gap: 8px; }
.trait-row { display: flex; align-items: center; gap: 10px; }
.trait { width: 36px; height: 36px; box-sizing: border-box; display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--color-border); border-radius: 50%; opacity: .55; }
.trait--major { border-radius: 4px; }
.trait--selected { opacity: 1; border: 2px solid #dfbc6c; box-shadow: 0 0 6px #dfbc6c; }
.trait--selected-text { color: #dfbc6c; }
.spec__other { margin: 0; padding-left: 18px; }
.build__skills { display: flex; flex-wrap: wrap; gap: 24px; }
.build h4 { margin: 0 0 6px; }
.skills { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 8px; }
.skills li { display: flex; align-items: center; gap: 6px; }
.skill__blank { width: 32px; height: 32px; border: 1px dashed var(--color-border); display: inline-flex; align-items: center; justify-content: center; }
</style>
