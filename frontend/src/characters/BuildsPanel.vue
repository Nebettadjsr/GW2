<script setup lang="ts">
import { computed } from 'vue'
import type { BuildIdRef, BuildLegendRef, BuildSkills, BuildSpecialization, BuildTab, BuildTraitRef } from '@/api/types'
import BuildReferenceTile from './BuildReferenceTile.vue'

/**
 * Read-only saved build tabs (STORY-CHAR-010). Tabs follow the backend's numeric order; the build the
 * game has active is marked separately from the one being inspected. Specialization rows show every
 * supplied minor/major choice, placed only by the backend's tier/order for ORDINARY placements, with
 * the selected traits highlighted. Nothing is edited, inferred or fabricated: unresolved ids stay as ids.
 */
const props = defineProps<{ tabs: BuildTab[]; selected?: number }>()
const shown = computed(() => props.tabs.find((t) => t.tab === props.selected) ?? null)

const tabName = (t: BuildTab): string => t.name?.trim() || `Build ${t.tab}`

const refLabel = (r: BuildIdRef | null | undefined, kind: string): string =>
  !r ? `No ${kind} saved` : r.resolved && r.name ? r.name : `${kind} #${r.id} (details unavailable)`
const legendLabel = (l: BuildLegendRef | null): string =>
  !l ? 'No legend saved' : l.resolved && l.swapSkill ? refLabel(l.swapSkill, 'Skill') : `Legend ${l.id} (details unavailable)`

function groups(s: BuildSkills | undefined): Array<{ key: string; kind: string; ref: BuildIdRef | null }> {
  if (!s) return []
  return [{ key: 'heal', kind: 'Heal', ref: s.heal }, ...Array.from({ length: 3 }, (_, i) => ({ key: `utility-${i}`, kind: 'Utility', ref: s.utilities?.[i] ?? null })),
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
    <section v-if="shown" id="build-tabpanel" role="tabpanel" :aria-labelledby="`build-tab-${shown.tab}`" class="build" data-test="build-view">
      <h3 class="sr-only" data-test="build-title">{{ tabName(shown) }}</h3>

      <div class="build__skills">
        <h3 class="build__heading">Skills</h3>
        <div v-for="g in [{ id: 'land', label: 'Land skills', s: shown.skills }, { id: 'aquatic', label: 'Aquatic skills', s: shown.aquaticSkills }]" :key="g.id"
          class="skills-row" :aria-label="g.label" :data-test="`skills-${g.id}`">
          <span class="skills-row__environment" :class="`skills-row__environment--${g.id}`" :title="g.label" aria-hidden="true">
            <svg v-if="g.id === 'land'" viewBox="0 0 32 32"><path d="m5 24 6-12 4 5 5-11 8 18z" fill="currentColor" /><path d="m17 12 3-6 4 10-4-3-2 2z" fill="#d9cfab" /></svg>
            <svg v-else viewBox="0 0 32 32"><path d="M16 3C13 10 6 16 6 22a10 10 0 0 0 20 0c0-6-7-12-10-19z" fill="currentColor" /><path d="M10 21c0 5 3 7 6 7" fill="none" stroke="#b4d9ef" stroke-width="2" /></svg>
          </span>
          <ul v-if="g.s" class="skills">
            <li v-for="x in groups(g.s)" :key="x.key" :class="{ 'skill--separated': x.key === 'heal' || x.key === 'elite' }">
              <BuildReferenceTile :reference="x.ref" :label="`${x.kind}: ${refLabel(x.ref, 'Skill')}`" class="skill" data-test="skill" />
            </li>
          </ul>
          <p v-else class="meta">No skills saved.</p>
        </div>
      </div>

      <h3 class="build__heading build__heading--traits">Traits</h3>
      <div class="build__specs" data-test="build-specializations">
        <article v-for="(spec, i) in shown.specializations" :key="i" class="spec" :aria-label="specLabel(spec)" data-test="build-spec">
          <img v-if="spec.specialization?.backgroundUrl" :src="spec.specialization.backgroundUrl" alt="" class="spec__art" />
          <header class="spec__head">
            <img v-if="spec.specialization?.iconUrl" :src="spec.specialization.iconUrl" :title="specLabel(spec)" alt="" class="spec__emblem" />
            <strong :class="{ 'sr-only': spec.specialization?.iconUrl }" data-test="build-spec-name">{{ specLabel(spec) }}</strong>
          </header>
          <div class="spec__choices">
          <template v-if="spec.minorTraits || spec.majorTraits">
            <div v-for="row in layout(spec).rows" :key="row.tier" class="trait-row" :data-tier="row.tier" data-test="trait-row">
              <div class="trait-minors">
                <BuildReferenceTile v-for="t in row.minor" :key="t.id" :reference="t" :label="`${traitLabel(t)}, minor`" class="trait trait--minor" data-test="trait" />
              </div>
              <div class="trait-majors">
                <BuildReferenceTile v-for="t in row.major" :key="t.id" :reference="t" :selected="selectedIds(spec).has(t.id)"
                  :label="`${traitLabel(t)}, major${selectedIds(spec).has(t.id) ? ', selected' : ''}`" class="trait trait--major"
                  :class="{ 'trait--selected': selectedIds(spec).has(t.id) }" :style="{ gridRow: t.order! + 1 }" data-test="trait" />
              </div>
            </div>
            <ul v-if="layout(spec).other.length" class="spec__other meta" aria-label="Choices without a known position" data-test="trait-unplaced">
              <li v-for="t in layout(spec).other" :key="t.id" :class="{ 'trait--selected-text': selectedIds(spec).has(t.id) }">
                {{ traitLabel(t) }}{{ selectedIds(spec).has(t.id) ? ' · selected' : '' }}
              </li>
            </ul>
          </template>
          <ul v-if="unshownSelected(spec).length" class="spec__other meta" aria-label="Selected traits" data-test="trait-selected-list">
            <li v-for="t in unshownSelected(spec)" :key="t.id">{{ traitLabel(t) }} · selected<template v-if="!(spec.minorTraits || spec.majorTraits)"> (choices not available)</template></li>
          </ul>
          <p v-if="!spec.traits" class="meta" data-test="trait-none">No traits saved.</p>
          </div>
        </article>
        <p v-if="shown.specializations.length === 0" class="meta" data-test="build-no-specs">No specializations saved.</p>
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
.builds { --trait-unit: calc(var(--character-panel-width, calc(980 * 60px / 76)) / 950); width: min(100%, calc(950 * var(--trait-unit))); margin-inline: auto; }
.build__heading { margin: 0 0 16px; font-size: 1.5rem; }
.build__heading--traits { margin-top: 32px; }
.build__specs { display: flex; flex-direction: column; gap: calc(14 * var(--trait-unit)); }
.spec { position: relative; isolation: isolate; display: grid; grid-template-columns: 27% 1fr; min-height: calc(206 * var(--trait-unit)); box-sizing: border-box; padding: calc(12 * var(--trait-unit)) 8%; background: #0a1010; border: 1px solid #23312e; overflow: hidden; }
.spec__art { position: absolute; z-index: -1; bottom: 0; left: 0; width: 158%; height: 124%; max-width: none; pointer-events: none; }
.spec__head { display: flex; align-items: center; justify-content: flex-start; }
.spec__emblem { width: calc(160 * var(--trait-unit)); max-width: 95%; height: auto; filter: drop-shadow(0 4px 3px #0009); }
.spec__choices { display: flex; align-items: center; flex-wrap: wrap; gap: calc(8 * var(--trait-unit)); }
.trait-row { display: grid; grid-template-columns: 1fr 1fr; align-items: center; flex: 1; min-width: 0; gap: calc(10 * var(--trait-unit)); }
.trait-minors { display: flex; align-items: center; justify-content: center; }
.trait-majors { display: grid; grid-template-rows: repeat(3, calc(52 * var(--trait-unit))); gap: calc(8 * var(--trait-unit)); justify-items: center; }
.trait { width: calc(52 * var(--trait-unit)); height: calc(52 * var(--trait-unit)); }
.trait--minor { clip-path: polygon(25% 0, 75% 0, 100% 25%, 100% 75%, 75% 100%, 25% 100%, 0 75%, 0 25%); background: #051007; }
.trait--major { border: 1px solid #1d3726; background: #051007; opacity: .3; }
.trait--selected { opacity: 1; border: 1px solid #102518; box-shadow: 0 0 3px #000; }
.trait:hover, .trait:focus-visible { opacity: 1; }
.trait--selected-text { color: #dfbc6c; }
.spec__other { flex-basis: 100%; margin: 0; padding-left: 18px; background: #000b; }
.build__skills { display: flex; flex-direction: column; gap: 16px; padding-top: 18px; }
.build__skills .build__heading { margin-bottom: 0; }
.build h4 { margin: 0 0 6px; }
.skills-row { display: flex; align-items: center; gap: 20px; }
.skills-row__environment { display: inline-flex; flex: 0 0 36px; width: 36px; height: 36px; border: 1px solid #948f78; background: linear-gradient(140deg, #937543, #2f2519); color: #3f291c; }
.skills-row__environment--aquatic { color: #0d3449; background: linear-gradient(140deg, #a1bedc, #334766); border-color: #7b98b8; }
.skills-row__environment svg { width: 100%; height: 100%; }
.skills { list-style: none; margin: 0; padding: 0; display: flex; gap: 6px; }
.skills li { display: flex; }
.skills li:first-child { margin-right: 8px; }
.skills li:last-child { margin-left: 8px; }
.skill { --build-icon-scale: 1.16; width: 60px; height: 60px; padding: 1px; overflow: hidden; border: 2px solid #c5c9c9; background: #070a0b; }
.build__facts { margin-top: 24px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
@media (max-width: 720px) {
  .spec { padding: 10px 2%; grid-template-columns: 23% 1fr; min-height: 162px; }
  .trait { width: 38px; height: 38px; }
  .trait-row { gap: 3px; }
  .trait-majors { grid-template-rows: repeat(3, 38px); gap: 6px; }
}
@media (max-width: 460px) {
  .spec { padding: 8px 1%; grid-template-columns: 20% 1fr; min-height: 134px; }
  .trait { width: 29px; height: 29px; }
  .trait-majors { grid-template-rows: repeat(3, 29px); gap: 5px; }
  .skills-row { gap: 10px; }
  .skills-row__environment { flex-basis: 26px; width: 26px; height: 26px; }
  .skill { width: 44px; height: 44px; }
  .skills { gap: 4px; }
  .skills li:first-child { margin-right: 3px; }
  .skills li:last-child { margin-left: 3px; }
}
</style>
