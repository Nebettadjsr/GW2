<script setup lang="ts">
import { nextTick, ref } from 'vue'
import ItemIcon from '@/items/ItemIcon.vue'
import type { EquipmentEntry, EquipmentItemRef, EquipmentTab } from '@/api/types'

/**
 * Adjacent horizontal equipment tabs in the order the backend supplied (in-game order). The tab the
 * game marks active is labelled "In use" and stays identified while another tab is inspected. Purely
 * read-only: selecting a tab only changes what is shown. Only supplied entries are listed; no empty or
 * locked slots are invented.
 */
const props = defineProps<{ tabs: EquipmentTab[]; shown: EquipmentTab | null }>()
const emit = defineEmits<{ inspect: [tab: number] }>()
const buttons = ref<HTMLElement[]>([])

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
function tabName(tab: EquipmentTab): string {
  return tab.name && tab.name.trim() !== '' ? tab.name : `Tab ${tab.tab}`
}

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
</script>

<template>
  <div class="equipment" data-test="equipment-panel">
    <p v-if="tabs.length === 0" class="notice" data-test="equipment-no-tabs">
      The saved data lists no equipment tabs for this character.
    </p>
    <template v-else>
      <div class="equipment__tabs" role="tablist" aria-label="Equipment tabs" data-test="equipment-tablist">
        <button
          v-for="(tab, index) in tabs" :key="tab.tab" :ref="(el) => { if (el) buttons[index] = el as HTMLElement }"
          type="button" role="tab" class="equipment__tab" :id="`equipment-tab-${tab.tab}`"
          :aria-selected="shown?.tab === tab.tab" :aria-controls="'equipment-tabpanel'"
          :tabindex="shown?.tab === tab.tab ? 0 : -1"
          :class="{ 'equipment__tab--selected': shown?.tab === tab.tab }" data-test="equipment-tab"
          @click="emit('inspect', tab.tab)" @keydown="onKey($event, index)"
        >
          {{ tabName(tab) }}
          <span v-if="tab.active" class="equipment__active" data-test="equipment-tab-active">In use</span>
        </button>
      </div>
      <div v-if="shown" id="equipment-tabpanel" role="tabpanel" :aria-labelledby="`equipment-tab-${shown.tab}`"
        class="equipment__panel" data-test="equipment-tabpanel">
        <p v-if="!shown.active" class="meta" data-test="equipment-inspecting">
          Viewing a tab that is not in use in game. This view is read-only.
        </p>
        <p v-if="shown.equipment.length === 0" class="notice" data-test="equipment-empty">
          No equipment entries were supplied for this saved tab. This does not show which slots exist or are empty.
        </p>
        <ul v-else class="equipment__list">
          <li v-for="(entry, i) in shown.equipment" :key="`${entry.slot ?? 'unspecified'}-${i}`" class="equipment__entry"
            data-test="equipment-entry">
            <ItemIcon :icon-url="entry.item.icon ?? null" :item-id="entry.item.itemId" :size="32" />
            <div class="equipment__body">
              <div class="meta" data-test="equipment-slot">{{ slotLabel(entry) }}<template v-if="entry.location"> · {{ entry.location }}</template></div>
              <div class="equipment__name" data-test="equipment-item">{{ label(entry.item) }}</div>
              <div v-if="entry.item.rarity || entry.item.type" class="meta">
                <span v-if="entry.item.rarity" :class="['rarity', rarityClass(entry.item.rarity)]" data-test="equipment-rarity">{{ entry.item.rarity }}</span>
                <span v-if="entry.item.type"> {{ entry.item.type }}</span>
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
.equipment__tabs { display: flex; flex-wrap: wrap; gap: 2px; border-bottom: 1px solid var(--border, #888); }
.equipment__tab { border: 1px solid var(--border, #888); border-bottom: 0; background: transparent; padding: 6px 12px; cursor: pointer; }
.equipment__tab:focus-visible { outline: 3px solid currentColor; outline-offset: 2px; }
.equipment__tab--selected { font-weight: 700; background: rgba(128, 128, 128, 0.2); }
.equipment__active { margin-left: 6px; font-size: 0.8em; border: 1px solid currentColor; border-radius: 3px; padding: 0 4px; }
.equipment__list { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px; }
.equipment__entry { display: flex; gap: 8px; padding: 8px; border: 1px solid var(--border, #888); min-width: 0; }
.equipment__body { min-width: 0; overflow-wrap: anywhere; }
.equipment__name { font-weight: 600; }
.equipment__sub { margin: 2px 0 0; padding-left: 16px; font-size: 0.9em; }
.rarity { font-weight: 600; }
.rarity--junk { color: #7a7a7a } .rarity--basic { color: #404040 } .rarity--fine { color: #1a6fd1 }
.rarity--masterwork { color: #218c1e } .rarity--rare { color: #a07800 } .rarity--exotic { color: #b86a00 }
.rarity--ascended { color: #c2186e } .rarity--legendary { color: #7a2bd1 } .rarity--unknown { color: inherit }
</style>
