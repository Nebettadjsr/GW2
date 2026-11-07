<script setup lang="ts">
import ItemIcon from '@/items/ItemIcon.vue'
import InventoryTile from '@/account/InventoryTile.vue'
import type { InventoryBag, InventoryItemRef, InventorySlot } from '@/api/types'

/**
 * Read-only character bags as compact square icon slots (like Bank/Materials), in the backend's
 * actual bag order and slot order. Only supplied bags and cells are drawn: an uninstalled position
 * has no capacity and no cells, true empties stay as empty squares, and no locked/unlock status is
 * invented. Item identity, quantity and binding live in the accessible label/title of each slot.
 */
defineProps<{ bags: InventoryBag[] }>()

function label(item: InventoryItemRef): string {
  return item.resolved && item.name ? item.name : `Item #${item.itemId} (details unavailable)`
}
function describe(slot: InventorySlot): string {
  if (slot.empty) return `Empty slot ${slot.slot}`
  const parts = [slot.item ? label(slot.item) : 'Item details not supplied']
  if (slot.quantity !== undefined) parts.push(`quantity ${slot.quantity}`)
  if (slot.binding) parts.push(`binding ${slot.binding}${slot.boundTo ? ` (${slot.boundTo})` : ''}`)
  return `Slot ${slot.slot}: ${parts.join(', ')}`
}
</script>

<template>
  <div class="inventory" data-test="inventory-panel">
    <p v-if="bags.length === 0" class="notice" data-test="inventory-no-bags">The saved data lists no bags for this character.</p>
    <section v-for="bag in bags" :key="bag.bag" class="inventory__bag" :data-bag="bag.bag" data-test="inventory-bag">
      <h4 class="inventory__title" data-test="inventory-bag-title">
        <ItemIcon v-if="bag.container" :icon-url="bag.container.icon ?? null" :item-id="bag.container.itemId" :size="24" />
        Bag {{ bag.bag + 1 }}<template v-if="bag.container">: {{ label(bag.container) }}</template>
        <span v-if="bag.installed && bag.capacity !== undefined" class="meta"> ({{ bag.capacity }} slots)</span>
      </h4>
      <p v-if="!bag.installed" class="meta" data-test="inventory-bag-uninstalled">No bag installed in this position.</p>
      <ul v-else class="inventory__slots" :aria-label="`Bag ${bag.bag + 1} slots`">
        <li v-for="slot in bag.slots" :key="slot.slot" class="inventory__slot" :class="{ 'inventory__slot--empty': slot.empty }"
          :data-slot="slot.slot" :title="describe(slot)" :aria-label="describe(slot)" tabindex="0" data-test="inventory-slot">
          <span v-if="slot.empty" class="sr-only" data-test="inventory-slot-empty">Empty</span>
          <template v-else>
            <InventoryTile v-if="slot.item && slot.quantity !== undefined" :item-id="slot.item.itemId" :count="slot.quantity"
              :icon-url="slot.item.icon ?? null" :title="describe(slot)" show-one />
            <span v-else class="inventory__unknown" data-test="inventory-slot-unknown">?</span>
            <span class="sr-only" data-test="inventory-slot-item">{{ slot.item ? label(slot.item) : 'Item details not supplied' }}</span>
            <span v-if="slot.quantity !== undefined" class="sr-only" data-test="inventory-slot-quantity">×{{ slot.quantity }}</span>
          </template>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.inventory__bag { margin-bottom: 12px; }
.inventory__slots { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, 60px); gap: 4px; }
.inventory__slot { position: relative; box-sizing: border-box; width: 60px; height: 60px; display: flex; align-items: center; justify-content: center; overflow: hidden; }
.inventory__slot--empty { border: 1px solid var(--color-border); border-radius: 2px; background: rgba(0, 0, 0, 0.18); }
.inventory__slot:focus-visible { outline: 2px solid var(--color-accent, #2f6fdb); outline-offset: 1px; }
.inventory__unknown { border: 1px dashed var(--color-border); width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
</style>
