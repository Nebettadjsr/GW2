<script setup lang="ts">
import { computed, ref } from 'vue'
import type { EquipmentStats } from '@/api/types'

const props = defineProps<{ stats?: EquipmentStats }>()
const optionsOpen = ref(false)
const mode = ref('PVE')
const reinforced = ref(false)
const profile = computed(() => `${mode.value}${reinforced.value ? '_REINFORCED' : ''}`)
const summary = computed(() => props.stats?.profiles?.[profile.value]?.A ?? props.stats?.weaponSets.A)
const icons = import.meta.glob('./attributes/*.png', { eager: true, query: '?url', import: 'default' }) as Record<string, string>
const aliases: Record<string, string> = { Expertise: 'ConditionDuration', Concentration: 'BoonDuration' }
const labels: Record<string, string> = {
  Power: 'Power', Toughness: 'Toughness', Armor: 'Armor', Vitality: 'Vitality', Health: 'Health',
  Precision: 'Precision', CriticalChance: 'Critical chance', Ferocity: 'Ferocity', CriticalDamage: 'Critical damage',
  ConditionDamage: 'Condition damage', Expertise: 'Expertise', ConditionDuration: 'Condition duration',
  Concentration: 'Concentration', BoonDuration: 'Boon duration', HealingPower: 'Healing power', AgonyResistance: 'Agony resistance'
}
const rows = [
  ['Power', null], ['Toughness', 'Armor'], ['Vitality', 'Health'], ['Precision', 'CriticalChance'],
  ['Ferocity', 'CriticalDamage'], ['ConditionDamage', 'HealingPower'], ['Expertise', 'ConditionDuration'],
  ['Concentration', 'BoonDuration'], ['AgonyResistance', null]
]
const percentages = new Set(['CriticalChance', 'CriticalDamage', 'ConditionDuration', 'BoonDuration'])
function icon(key: string): string | undefined { return icons[`./attributes/${aliases[key] ?? key}.png`] }
function formatted(key: string): string {
  const value = summary.value?.available ? summary.value.values[key] : undefined
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return new Intl.NumberFormat('en-US', { minimumFractionDigits: percentages.has(key) && key !== 'CriticalDamage' ? 2 : 0, maximumFractionDigits: percentages.has(key) ? 2 : 0 }).format(value) + (percentages.has(key) ? '%' : '')
}
</script>

<template>
  <section class="equipment-stats" aria-label="Equipment stats" @keydown.esc="optionsOpen = false">
    <button class="equipment-stats__settings" type="button" aria-label="Attribute calculation options" :aria-expanded="optionsOpen" @click="optionsOpen = !optionsOpen">⚙</button>
    <div v-if="optionsOpen" class="equipment-stats__options">
      <label>Game mode <select v-model="mode"><option value="PVE">PvE</option><option value="WVW">WvW</option></select></label>
      <label><input v-model="reinforced" type="checkbox" /> Reinforced Armor</label>
      <small>Anvil buff — select if active in game. Its status is not provided by the API.</small>
      <small>Uses saved land weapon set I and supported passive traits. Trait support is currently limited. Food, boons and temporary combat effects are not included.</small>
    </div>
    <div v-for="(row, index) in rows" :key="index" class="equipment-stats__row">
      <template v-for="(key, column) in row" :key="column">
        <div v-if="key" class="equipment-stats__value" :title="labels[key]">
          <img v-if="icon(key)" :src="icon(key)" alt="" width="23" height="23" />
          <span class="sr-only">{{ labels[key] }}: </span><span>{{ formatted(key) }}</span>
        </div>
        <div v-else />
      </template>
    </div>
  </section>
</template>

<style scoped>
.equipment-stats { color: var(--color-text); width: 100%; position: relative; }
.equipment-stats__settings { position: absolute; right: 0; top: -4px; border: 0; padding: 2px 6px; background: transparent; color: var(--color-text); font-size: 21px; cursor: pointer; opacity: .7; }
.equipment-stats__settings:hover, .equipment-stats__settings:focus-visible { opacity: 1; }
.equipment-stats__options { position: absolute; right: 0; top: 32px; z-index: 20; width: 250px; padding: 16px; border: 1px solid #617581; border-radius: 8px; background: #1d2830; box-shadow: 0 6px 24px #0008; display: grid; gap: 12px; }
.equipment-stats__options label { display: flex; align-items: center; gap: 10px; }
.equipment-stats__options select { margin-left: auto; }
.equipment-stats__options small { line-height: 1.4; color: #b8cbd6; }
.equipment-stats__row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-bottom: 9px; }
.equipment-stats__value { display: flex; align-items: center; gap: 13px; font-size: 18px; font-variant-numeric: tabular-nums; }
.equipment-stats__value img { object-fit: contain; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }
</style>
