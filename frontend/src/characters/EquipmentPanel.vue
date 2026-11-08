<script setup lang="ts">
import { computed } from 'vue'
import ItemIcon from '@/items/ItemIcon.vue'
import EquipmentTile from './EquipmentTile.vue'
import EquipmentStats from './EquipmentStats.vue'
import type { EquipmentEntry, EquipmentItemRef, EquipmentTab } from '@/api/types'

/**
 * Read-only equipment view for the template selected in the page navigation.
 * Only supplied entries are listed; no empty or locked slots are invented.
 */
const props = defineProps<{ tabs: EquipmentTab[]; shown: EquipmentTab | null; profession?: string; sharedEquipment?: EquipmentEntry[] }>()
const professionArt = import.meta.glob('./art/*.png', { eager: true, query: '?url', import: 'default' }) as Record<string, string>
const artwork = computed(() => professionArt[`./art/${props.profession?.toLowerCase()}.png`])
const armor = ['Helm', 'Shoulders', 'Coat', 'Gloves', 'Leggings', 'Boots']
const weapons = ['WeaponA1', 'WeaponA2', 'WeaponB1', 'WeaponB2']
const trinkets = ['Backpack', 'Accessory1', 'Accessory2', 'Relic', 'Amulet', 'Ring1', 'Ring2']
const aquatic = ['HelmAquatic', 'WeaponAquaticA', 'WeaponAquaticB']
const tools = ['Sickle', 'Axe', 'Pick']
const fishing = ['FishingRod', 'FishingBait', 'FishingLure']
const jadeBot = ['PowerCore', 'SensoryArray', 'ServiceChip']
const placed = new Set([...armor, ...weapons, ...trinkets, ...aquatic, ...tools, ...fishing, ...jadeBot, 'Backpack'])
const additional = computed(() => props.shown?.equipment.filter(e => !e.slot || !placed.has(e.slot)) ?? [])
function itemAt(slot: string): EquipmentEntry | undefined {
  // Prefer a template's own relic when supplied; otherwise show the character-wide equipped relic.
  if (slot === 'Relic') return props.shown?.equipment.find(e => e.slot === slot) ?? props.sharedEquipment?.find(e => e.slot === slot)
  return props.sharedEquipment?.find(e => e.slot === slot) ?? props.shown?.equipment.find(e => e.slot === slot)
}
function pretty(slot: string): string { return slot.replace(/([a-z])([A-Z])/g, '$1 $2').replace('Weapon ', '') }

const RARITIES = ['Junk', 'Basic', 'Fine', 'Masterwork', 'Rare', 'Exotic', 'Ascended', 'Legendary']
/** Colour class only for a rarity this UI knows; the rarity text is always shown beside it. */
function rarityClass(rarity?: string): string {
  return rarity !== undefined && RARITIES.includes(rarity) ? `rarity--${rarity.toLowerCase()}` : 'rarity--unknown'
}
function label(item: EquipmentItemRef): string {
  return item.resolved && item.name ? item.name : `Item #${item.itemId} (details unavailable)`
}
function slotLabel(entry: EquipmentEntry): string {
  return entry.slot ? entry.slot.replace(/([a-z])([A-Z])/g, '$1 $2') : 'Unspecified slot'
}
function stats(entry: EquipmentEntry): string[] {
  return Object.entries(entry.statAttributes ?? {}).map(([k, v]) => `${k} +${v}`)
}
</script>

<template>
  <div class="equipment" data-test="equipment-panel">
    <p v-if="tabs.length === 0" class="notice" data-test="equipment-no-tabs">
      The saved data lists no equipment tabs for this character.
    </p>
    <template v-else>
      <div v-if="shown" id="equipment-tabpanel" role="tabpanel" :aria-labelledby="`equipment-tab-${shown.tab}`"
        class="equipment__panel" data-test="equipment-tabpanel">
        <p v-if="shown.equipment.length === 0" class="notice" data-test="equipment-empty">
          No equipment entries were supplied for this saved tab. This does not show which slots exist or are empty.
        </p>
        <div v-else class="equipment__hero">
          <div class="equipment__art" aria-hidden="true">
            <img v-if="artwork" :src="artwork" alt="" :class="{ 'equipment__necromancer-art': profession === 'Necromancer' }" />
          </div>
          <div class="equipment__stats"><EquipmentStats :key="shown.tab" :stats="shown.stats" /></div>
          <div class="equipment__armor" aria-label="Armor">
            <EquipmentTile v-for="slot in armor" :key="slot" :slot="pretty(slot)" :entry="itemAt(slot)" />
          </div>
          <div class="equipment__weapons" aria-label="Weapon sets">
            <EquipmentTile v-for="slot in weapons" :key="slot" :slot="pretty(slot)" :entry="itemAt(slot)" />
          </div>
          <div class="equipment__trinkets" aria-label="Trinkets">
            <EquipmentTile v-for="slot in trinkets" :key="slot" :slot="pretty(slot)" :entry="itemAt(slot)" />
          </div>
          <div class="equipment__aquatic" aria-label="Aquatic equipment">
            <EquipmentTile v-for="slot in aquatic" :key="slot" :slot="pretty(slot)" :entry="itemAt(slot)" />
          </div>
          <div class="equipment__tools" aria-label="Gathering tools">
            <EquipmentTile v-for="slot in tools" :key="slot" :slot="pretty(slot)" :entry="itemAt(slot)" />
          </div>
          <div class="equipment__utility" aria-label="Fishing and jade bot equipment">
            <div class="equipment__utility-row" aria-label="Fishing equipment">
              <EquipmentTile v-for="slot in fishing" :key="slot" :slot="pretty(slot)" :entry="itemAt(slot)" />
            </div>
            <div class="equipment__utility-row" aria-label="Jade bot equipment">
              <EquipmentTile v-for="slot in jadeBot" :key="slot" :slot="pretty(slot)" :entry="itemAt(slot)" />
            </div>
          </div>
        </div>
        <ul v-if="additional.length" class="equipment__list">
          <li v-for="(entry, i) in additional" :key="`${entry.slot ?? 'unspecified'}-${i}`" class="equipment__entry"
            data-test="equipment-entry">
            <ItemIcon :icon-url="entry.item.icon ?? null" :item-id="entry.item.itemId" :size="32" />
            <div class="equipment__body">
              <div class="meta" data-test="equipment-slot">{{ slotLabel(entry) }}<template v-if="entry.location"> · {{ entry.location }}</template></div>
              <div class="equipment__name" data-test="equipment-item">{{ label(entry.item) }}</div>
              <div v-if="entry.item.rarity || entry.item.type" class="meta">
                <span v-if="entry.item.rarity" :class="['rarity', rarityClass(entry.item.rarity)]" data-test="equipment-rarity">{{ entry.item.rarity }}</span>
                <span v-if="entry.item.type" class="equipment__type">{{ entry.item.type }}</span>
              </div>
              <div v-if="entry.statId !== undefined || stats(entry).length > 0" class="meta" data-test="equipment-stats">
                <template v-if="stats(entry).length > 0">{{ stats(entry).join(', ') }}</template>
                <template v-else>Stat set #{{ entry.statId }}</template>
              </div>
              <div v-if="entry.binding" class="meta">Binding: {{ entry.binding }}<template v-if="entry.boundTo"> ({{ entry.boundTo }})</template></div>
              <ul v-if="entry.upgrades.length > 0" class="equipment__sub" data-test="equipment-upgrades">
                <li v-for="(u, ui) in entry.upgrades" :key="`u${ui}`">Upgrade: {{ label(u) }}</li>
              </ul>
              <ul v-if="entry.infusions.length > 0" class="equipment__sub" data-test="equipment-infusions">
                <li v-for="(f, fi) in entry.infusions" :key="`i${fi}`">Infusion: {{ label(f) }}</li>
              </ul>
            </div>
          </li>
        </ul>
      </div>
    </template>
  </div>
</template>

<style scoped>
.equipment { --equipment-unit: calc(60px / 76); }
.equipment__panel { overflow-x: auto; }
.equipment__hero { position: relative; width: var(--character-panel-width, calc(980 * var(--equipment-unit))); height: calc(900 * var(--equipment-unit)); margin: calc(24 * var(--equipment-unit)) auto; }
.equipment__art { position: absolute; left: calc(84 * var(--equipment-unit)); top: 0; width: calc(640 * var(--equipment-unit)); height: calc(792 * var(--equipment-unit)); display: flex; align-items: flex-end; justify-content: center; pointer-events: none; }
.equipment__art img { width: 100%; height: 100%; max-width: 100%; object-fit: contain; object-position: center bottom; opacity: 0.5; }
.equipment__art img.equipment__necromancer-art { width: auto; max-width: none; }
.equipment__armor, .equipment__weapons, .equipment__trinkets, .equipment__aquatic, .equipment__tools { position: absolute; z-index: 1; display: grid; gap: calc(8 * var(--equipment-unit)); }
.equipment__armor { left: 0; top: 0; }
.equipment__stats { position: absolute; z-index: 1; right: 0; top: 0; width: calc(328 * var(--equipment-unit)); }
.equipment__weapons { left: 0; top: calc(528 * var(--equipment-unit)); grid-template-columns: 60px; }
.equipment__weapons > :nth-child(3) { margin-top: calc(24 * var(--equipment-unit)); }
.equipment__trinkets { right: 0; top: calc(440 * var(--equipment-unit)); grid-template-columns: repeat(4, 60px); }
.equipment__trinkets > :nth-child(5) { grid-column: 2; }
.equipment__aquatic { right: 0; top: calc(628 * var(--equipment-unit)); grid-template-columns: repeat(3, 60px); }
.equipment__tools { right: 0; top: calc(804 * var(--equipment-unit)); grid-template-columns: repeat(3, 60px); }
.equipment__utility { position: absolute; z-index: 1; left: calc(132 * var(--equipment-unit)); top: calc(804 * var(--equipment-unit)); display: flex; gap: calc(20 * var(--equipment-unit)); }
.equipment__utility-row { display: grid; grid-template-columns: repeat(3, 60px); gap: calc(8 * var(--equipment-unit)); }
.equipment__list { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px; }
.equipment__entry { display: flex; gap: 8px; padding: 8px; border: 1px solid var(--border, #888); min-width: 0; }
.equipment__body { min-width: 0; overflow-wrap: anywhere; }
.equipment__name { font-weight: 600; }
.equipment__sub { margin: 2px 0 0; padding-left: 16px; font-size: 0.9em; }
.rarity { font-weight: 600; }
.rarity--junk { color: #b0b0b0 } .rarity--basic { color: #e0e0e0 } .rarity--fine { color: #62a4f0 }
.rarity--masterwork { color: #4cc94a } .rarity--rare { color: #f0c020 } .rarity--exotic { color: #ffa010 }
.rarity--ascended { color: #ff5fa8 } .rarity--legendary { color: #b98cff } .rarity--unknown { color: inherit }
.rarity + .equipment__type { margin-left: 0.5em }
</style>
