<script setup lang="ts">
import { computed } from 'vue'
import ItemIcon from '@/items/ItemIcon.vue'
import InventoryTile from '@/account/InventoryTile.vue'
import type { InventoryBag, InventoryItemRef, InventorySlot } from '@/api/types'

/**
 * Read-only character bags as compact square icon slots (like Bank/Materials), in the backend's
 * actual bag order and slot order. Only supplied bags and cells are drawn: an uninstalled position
 * has no capacity and no cells, true empties stay as empty squares, and no locked/unlock status is
 * invented. Item identity, quantity and binding live in the accessible label/title of each slot.
 */
const props = defineProps<{ bags: InventoryBag[] }>()

/** Presentation-only: installed bags' supplied slots concatenated in supplied bag/slot order; original coordinates kept. */
const cells = computed(() => props.bags.filter((b) => b.installed).flatMap((b) => b.slots.map((slot) => ({ bag: b.bag, slot }))))
function railText(bag: InventoryBag): string {
  if (!bag.installed) return `Bag ${bag.bag + 1}: no bag installed in this position`
  const name = bag.container ? `: ${label(bag.container)}` : ''
  return `Bag ${bag.bag + 1}${name}${bag.capacity !== undefined ? `, ${bag.capacity} slots` : ''}`
}

function label(item: InventoryItemRef): string {
  return item.resolved && item.name ? item.name : `Item #${item.itemId} (details unavailable)`
}
function describe(slot: InventorySlot, bag: number): string {
  if (slot.empty) return `Bag ${bag + 1} empty slot ${slot.slot}`
  const parts = [slot.item ? label(slot.item) : 'Item details not supplied']
  if (slot.quantity !== undefined) parts.push(`quantity ${slot.quantity}`)
  if (slot.binding) parts.push(`binding ${slot.binding}${slot.boundTo ? ` (${slot.boundTo})` : ''}`)
  return `Bag ${bag + 1} slot ${slot.slot}: ${parts.join(', ')}`
}
</script>

<template>
  <div class="inventory" data-test="inventory-panel">
    <aside class="inventory__facts" data-test="inventory-facts"><slot name="facts" /></aside>
    <div class="inventory__center">
      <p v-if="bags.length === 0" class="notice" data-test="inventory-no-bags">The saved data lists no bags for this character.</p>
      <ul v-else class="inventory__slots" aria-label="Inventory slots" data-test="inventory-grid">
        <li v-for="cell in cells" :key="`${cell.bag}:${cell.slot.slot}`" class="inventory__slot" :class="{ 'inventory__slot--empty': cell.slot.empty }"
          :data-bag="cell.bag" :data-slot="cell.slot.slot" :title="describe(cell.slot, cell.bag)" :aria-label="describe(cell.slot, cell.bag)" tabindex="0" data-test="inventory-slot">
          <span v-if="cell.slot.empty" class="sr-only" data-test="inventory-slot-empty">Empty</span>
          <template v-else>
            <InventoryTile v-if="cell.slot.item && cell.slot.quantity !== undefined" :item-id="cell.slot.item.itemId" :count="cell.slot.quantity"
              :icon-url="cell.slot.item.icon ?? null" :title="describe(cell.slot, cell.bag)" show-one />
            <span v-else class="inventory__unknown" data-test="inventory-slot-unknown">?</span>
            <span class="sr-only" data-test="inventory-slot-item">{{ cell.slot.item ? label(cell.slot.item) : 'Item details not supplied' }}</span>
            <span v-if="cell.slot.quantity !== undefined" class="sr-only" data-test="inventory-slot-quantity">×{{ cell.slot.quantity }}</span>
          </template>
        </li>
      </ul>
    </div>
    <ol class="inventory__rail" aria-label="Bag positions" data-test="inventory-rail">
      <li v-for="bag in bags" :key="bag.bag" class="inventory__rail-item" :class="{ 'inventory__rail-item--empty': !bag.installed }"
        :data-bag="bag.bag" :title="railText(bag)" :aria-label="railText(bag)" tabindex="0" data-test="inventory-bag">
        <ItemIcon v-if="bag.installed && bag.container" :icon-url="bag.container.icon ?? null" :item-id="bag.container.itemId" :size="32" />
        <span v-else-if="bag.installed" class="inventory__unknown" data-test="inventory-bag-unknown">?</span>
        <span v-else class="sr-only" data-test="inventory-bag-uninstalled">No bag installed in this position.</span>
        <span class="inventory__rail-label" aria-hidden="true">{{ bag.installed ? (bag.capacity ?? '?') : '–' }}</span>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.inventory { display: grid; grid-template-columns: minmax(150px, 220px) minmax(0, max-content) 56px; gap: 16px; align-items: start; justify-content: start; }
.inventory__center { min-width: 0; max-width: 100%; overflow-x: auto; }
.inventory__slots { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(10, 60px); gap: 4px; }
.inventory__slot { position: relative; box-sizing: border-box; width: 60px; height: 60px; display: flex; align-items: center; justify-content: center; overflow: hidden; }
.inventory__slot--empty { border: 1px solid var(--color-border); border-radius: 2px; background: rgba(0, 0, 0, 0.18); }
.inventory__slot:focus-visible, .inventory__rail-item:focus-visible { outline: 2px solid var(--color-accent, #2f6fdb); outline-offset: 1px; }
.inventory__rail { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.inventory__rail-item { position: relative; box-sizing: border-box; width: 56px; height: 44px; display: flex; align-items: center; justify-content: center; gap: 2px; border: 1px solid var(--color-border); border-radius: 2px; }
.inventory__rail-item--empty { border-style: dashed; opacity: 0.6; }
.inventory__rail-label { font-size: 0.75rem; }
.inventory__unknown { border: 1px dashed var(--color-border); width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 1100px) { .inventory { grid-template-columns: minmax(0, max-content) 56px; } .inventory__facts { grid-column: 1 / -1; } }
</style>
