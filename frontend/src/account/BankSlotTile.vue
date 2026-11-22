<script setup lang="ts">
import { computed } from 'vue'
import type { BankSlot } from '@/api/types'
import InventoryTile from './InventoryTile.vue'

const props = defineProps<{ bankSlot: BankSlot; selected?: boolean; reserved?: number; mixed?: boolean; finite?: boolean }>()
defineEmits<{ toggle: [itemId: number]; menu: [itemId: number, x: number, y: number] }>()

// Both values are null only for a genuinely empty slot.
const empty = computed(() => props.bankSlot.itemId === null && props.bankSlot.count === null)

</script>

<template>
  <li
    class="bank-cell"
    :class="{ 'bank-cell--empty': empty }"
    :data-slot="bankSlot.slot"
    data-test="bank-slot"
    :title="empty ? `Empty slot #${bankSlot.slot}` : `${bankSlot.itemName ?? `Item #${bankSlot.itemId ?? 'unknown'}`} · ${bankSlot.count ?? 'unknown'} in slot #${bankSlot.slot}`"
    :aria-label="empty ? `Empty slot ${bankSlot.slot}` : `Slot ${bankSlot.slot}, ${bankSlot.itemName ?? `item ${bankSlot.itemId ?? 'unknown'}`}, quantity ${bankSlot.count ?? 'unknown'}`"
  >
    <InventoryTile v-if="!empty && bankSlot.itemId !== null && bankSlot.count !== null"
      :item-id="bankSlot.itemId" :count="bankSlot.count" :icon-url="bankSlot.iconUrl"
      :title="`${bankSlot.itemName ?? `Item #${bankSlot.itemId}`} · ${bankSlot.count} in slot #${bankSlot.slot}`"
      :selected="selected" :reserved="reserved" :mixed="mixed" :finite="finite" :item-name="bankSlot.itemName ?? null"
      @toggle="$emit('toggle', $event)" @menu="(id: number, x: number, y: number) => $emit('menu', id, x, y)" />
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
