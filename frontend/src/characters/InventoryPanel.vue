<script setup lang="ts">
import ItemIcon from '@/items/ItemIcon.vue'
import type { InventoryBag, InventoryItemRef } from '@/api/types'

/**
 * Read-only character bags in the backend's actual bag order. Only the supplied bags and slots are
 * drawn: an uninstalled position has no capacity and no slots, and no locked/unlock status is
 * invented. Unresolved item ids and quantities stay visible.
 */
defineProps<{ bags: InventoryBag[] }>()

function label(item: InventoryItemRef): string {
  return item.resolved && item.name ? item.name : `Item #${item.itemId} (details unavailable)`
}
</script>

<template>
  <div class="inventory" data-test="inventory-panel">
    <p v-if="bags.length === 0" class="notice" data-test="inventory-no-bags">The saved data lists no bags for this character.</p>
    <section v-for="bag in bags" :key="bag.bag" class="inventory__bag" data-test="inventory-bag">
      <h4 class="inventory__title" data-test="inventory-bag-title">
        <ItemIcon v-if="bag.container" :icon-url="bag.container.icon ?? null" :item-id="bag.container.itemId" :size="24" />
        Bag {{ bag.bag }}<template v-if="bag.container">: {{ label(bag.container) }}</template>
        <span v-if="bag.installed && bag.capacity !== undefined" class="meta"> ({{ bag.capacity }} slots)</span>
      </h4>
      <p v-if="!bag.installed" class="meta" data-test="inventory-bag-uninstalled">No bag installed in this position.</p>
      <ul v-else class="inventory__slots">
        <li v-for="slot in bag.slots" :key="slot.slot" class="inventory__slot" :class="{ 'inventory__slot--empty': slot.empty }"
          data-test="inventory-slot">
          <span class="meta" data-test="inventory-slot-number">Slot {{ slot.slot }}</span>
          <span v-if="slot.empty" data-test="inventory-slot-empty">Empty</span>
          <template v-else>
            <ItemIcon v-if="slot.item" :icon-url="slot.item.icon ?? null" :item-id="slot.item.itemId" :size="32" />
            <span class="inventory__name" data-test="inventory-slot-item">{{ slot.item ? label(slot.item) : 'Item details not supplied' }}</span>
            <span v-if="slot.quantity !== undefined" data-test="inventory-slot-quantity">×{{ slot.quantity }}</span>
            <span v-if="slot.binding" class="meta">Binding: {{ slot.binding }}<template v-if="slot.boundTo"> ({{ slot.boundTo }})</template></span>
          </template>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.inventory__bag { margin-bottom: 12px; }
.inventory__slots { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 6px; }
.inventory__slot { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 6px; border: 1px solid var(--border, #888); min-width: 0; overflow-wrap: anywhere; }
.inventory__slot--empty { opacity: 0.75; border-style: dashed; }
.inventory__name { font-weight: 600; }
</style>
