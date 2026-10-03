<script setup lang="ts">
import { computed } from 'vue'
import type { BankSlot } from '@/api/types'
import InventoryTile from './InventoryTile.vue'

const props = defineProps<{ slot: BankSlot }>()

// Both values are null only for a genuinely empty slot.
const empty = computed(() => props.slot.itemId === null && props.slot.count === null)

</script>

<template>
  <li
    class="bank-cell"
    :class="{ 'bank-cell--empty': empty }"
    :data-slot="slot.slot"
    data-test="bank-slot"
    :title="empty ? `Empty slot #${slot.slot}` : `Item #${slot.itemId ?? 'unknown'} · ${slot.count ?? 'unknown'} in slot #${slot.slot}`"
    :aria-label="empty ? `Empty slot ${slot.slot}` : `Slot ${slot.slot}, item ${slot.itemId ?? 'unknown'}, quantity ${slot.count ?? 'unknown'}`"
  >
    <InventoryTile v-if="!empty && slot.itemId !== null && slot.count !== null"
      :item-id="slot.itemId" :count="slot.count" :icon-url="slot.iconUrl"
      :title="`Item #${slot.itemId} · ${slot.count} in slot #${slot.slot}`" />
    <span v-else class="sr-only" data-test="bank-empty-slot">Empty</span>
  </li>
</template>

<style scoped>
.bank-cell {
  position: relative;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 60px;
  height: 60px;
  padding: 0;
  overflow: hidden;
}

.bank-cell--empty { border: 1px solid var(--color-border); border-radius: 2px; background: rgba(0, 0, 0, 0.18); }
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
