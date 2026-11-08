<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId } from 'vue'
import ItemIcon from '@/items/ItemIcon.vue'
import EquipmentUpgradeDetails from './EquipmentUpgradeDetails.vue'
import type { EquipmentEntry, EquipmentItemRef } from '@/api/types'

const props = defineProps<{ entry?: EquipmentEntry; slot: string }>()
const tooltipId = useId()
const opened = ref(false)
const tooltip = ref<HTMLElement | null>(null)
const position = ref({ left: '0px', top: '0px' })
let closing: ReturnType<typeof setTimeout> | undefined
function keepOpen(): void { clearTimeout(closing) }
function closeSoon(): void { keepOpen(); closing = setTimeout(() => { opened.value = false }, 150) }
onBeforeUnmount(keepOpen)
function label(item: EquipmentItemRef): string {
  return item.resolved && item.name ? item.name : `Item #${item.itemId} (details unavailable)`
}
const colors: Record<string, string> = { Junk: '#aaa', Basic: '#eee', Fine: '#5599ff', Masterwork: '#33cc11', Rare: '#ffdd22', Exotic: '#ffaa00', Ascended: '#ff55aa', Legendary: '#aa77ff' }
const attributeNames: Record<string, string> = { CritDamage: 'Ferocity', ConditionDuration: 'Expertise', BoonDuration: 'Concentration', Healing: 'Healing Power', ConditionDamage: 'Condition Damage', AgonyResistance: 'Agony Resistance' }
function coin(value: number): string {
  const gold = Math.floor(value / 10000), silver = Math.floor(value % 10000 / 100), copper = value % 100
  return `${gold ? `${gold}g ` : ''}${silver ? `${silver}s ` : ''}${copper}c`
}
async function show(event: MouseEvent | FocusEvent): Promise<void> {
  keepOpen()
  if (!props.entry) return
  const tile = (event.currentTarget as HTMLElement).getBoundingClientRect()
  position.value = { left: `${Math.min(tile.right + 12, window.innerWidth - 364)}px`, top: `${tile.top}px` }
  opened.value = true
  await nextTick()
  if (!tooltip.value) return
  const box = tooltip.value.getBoundingClientRect()
  const left = tile.right + 12 + box.width <= window.innerWidth - 12 ? tile.right + 12 : Math.max(12, tile.left - box.width - 12)
  position.value = { left: `${left}px`, top: `${Math.max(12, Math.min(tile.top, window.innerHeight - box.height - 12))}px` }
}
</script>

<template>
  <div class="gear-slot" :class="{ 'gear-slot--missing': !entry }" :style="{ '--rarity': colors[entry?.item.rarity ?? ''] ?? '#89939b' }"
    tabindex="0" :aria-label="`${slot}: ${entry ? label(entry.item) : 'no saved item'}`" :aria-describedby="opened ? tooltipId : undefined"
    :title="entry ? undefined : `${slot}: no saved item`" data-test="equipment-entry"
    @mouseenter="show" @mouseleave="closeSoon" @focus="show" @blur="closeSoon" @keydown.esc="opened = false">
    <ItemIcon v-if="entry" :icon-url="entry.item.icon ?? null" :item-id="entry.item.itemId" :size="56" loading="eager" />
    <span v-else class="gear-slot__hint" aria-hidden="true">{{ slot }}</span>
    <Teleport to="body">
      <div v-if="opened && entry" :id="tooltipId" ref="tooltip" role="tooltip" class="gear-tooltip" :style="position" @mouseenter="keepOpen" @mouseleave="closeSoon" @wheel.stop>
        <div class="gear-tooltip__title" :style="{ color: colors[entry.item.rarity ?? ''] }">
          <ItemIcon :icon-url="entry.item.icon ?? null" :item-id="entry.item.itemId" :size="40" />
          <strong data-test="equipment-item">{{ label(entry.item) }}</strong>
        </div>
        <p v-if="entry.item.type === 'Relic' && entry.item.details?.description" class="gear-tooltip__description">{{ entry.item.details.description }}</p>
        <div v-if="typeof entry.item.details?.defense === 'number'" class="gear-tooltip__attributes">Defense: {{ entry.item.details.defense }}</div>
        <div v-if="typeof entry.item.details?.minPower === 'number' && typeof entry.item.details?.maxPower === 'number'" class="gear-tooltip__attributes">Weapon strength: {{ entry.item.details.minPower }}–{{ entry.item.details.maxPower }}</div>
        <div v-if="entry.statAttributes && Object.keys(entry.statAttributes).length" class="gear-tooltip__attributes" data-test="equipment-stats">
          <div v-for="(value, attribute) in entry.statAttributes" :key="attribute">+{{ value }} {{ attributeNames[attribute] ?? attribute }}</div>
        </div>
        <div v-else-if="entry.item.details?.attributes && Object.keys(entry.item.details.attributes).length" class="gear-tooltip__attributes">
          <div v-for="(value, attribute) in entry.item.details.attributes" :key="attribute">+{{ value }} {{ attributeNames[attribute] ?? attribute }}</div>
        </div>
        <div v-else-if="entry.statId !== undefined" class="gear-tooltip__muted">Stat set #{{ entry.statId }}</div>
        <EquipmentUpgradeDetails v-for="(upgrade, index) in entry.upgrades" :key="`upgrade-${index}`" :item="upgrade" :rune-count="entry.runeCount" :show-description="entry.item.type !== 'Gathering'" />
        <EquipmentUpgradeDetails v-for="(infusion, index) in entry.infusions" :key="`infusion-${index}`" :item="infusion" />
        <div class="gear-tooltip__facts">
          <div v-if="entry.item.rarity" :style="{ color: colors[entry.item.rarity] }" data-test="equipment-rarity">{{ entry.item.rarity }}</div>
          <div v-if="entry.item.details?.weightClass">{{ entry.item.details.weightClass }}</div>
          <div v-if="entry.item.details?.subtype || entry.item.type">{{ entry.item.details?.subtype ?? entry.item.type }}</div>
          <div v-if="slot !== (entry.item.details?.subtype ?? entry.item.type)" data-test="equipment-slot">{{ slot }}</div>
          <div v-if="typeof entry.item.details?.level === 'number'">Required Level: {{ entry.item.details.level }}</div>
          <div v-if="entry.binding">{{ entry.binding === 'Account' ? 'Accountbound' : entry.binding === 'Character' ? 'Soulbound' : entry.binding }}<template v-if="entry.boundTo"> · {{ entry.boundTo }}</template></div>
        </div>
        <p v-if="entry.item.type !== 'Relic' && entry.item.details?.description" class="gear-tooltip__description">{{ entry.item.details.description }}</p>
        <div v-for="(effect, index) in entry.item.details?.effects" :key="`effect-${index}`" class="gear-tooltip__effect">{{ effect }}</div>
        <div v-if="typeof entry.item.details?.vendorValue === 'number'" class="gear-tooltip__vendor">Vendor price: {{ coin(entry.item.details.vendorValue) }}</div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.gear-slot { width: 60px; height: 60px; box-sizing: border-box; border: 2px solid var(--rarity); display: flex; align-items: center; justify-content: center; background: #11161b; cursor: default; }
.gear-slot--missing { border-color: #68727b; background: rgba(0, 0, 0, 0.15); }
.gear-slot__hint { font-size: 10px; line-height: 1.15; color: #858e97; text-align: center; padding: 3px; }
.gear-slot:focus-visible { outline: 2px solid var(--color-info); outline-offset: 3px; }
.gear-tooltip { position: fixed; z-index: 1000; width: 340px; max-width: calc(100vw - 24px); max-height: calc(100vh - 24px); overflow-y: auto; box-sizing: border-box; background: rgba(5, 8, 11, 0.97); color: #eee; border: 1px solid #48515a; box-shadow: 0 8px 30px #0009; padding: 16px; font-size: 15px; line-height: 1.4; }
.gear-tooltip__title { display: flex; align-items: center; gap: 12px; font-size: 19px; margin-bottom: 16px; }
.gear-tooltip__attributes { color: #55ee66; margin-bottom: 16px; }
.gear-tooltip__upgrade { color: #69baff; display: flex; align-items: center; gap: 8px; margin: 8px 0; }
.gear-tooltip__facts { margin-top: 16px; }
.gear-tooltip__description { color: #b8e3e7; margin: 14px 0; }
.gear-tooltip__effect { color: #69baff; }
.gear-tooltip__vendor { font-size: 13px; margin-top: 16px; }
.gear-tooltip__muted { color: #96999c; }
</style>
