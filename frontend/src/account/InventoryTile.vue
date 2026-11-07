<script setup lang="ts">
import ItemIcon from '@/items/ItemIcon.vue'

withDefaults(defineProps<{
  itemId: number
  count: number
  iconUrl: string | null
  title: string
  showOne?: boolean
  /** Undefined: the tile is not selectable. Otherwise the pending protection state of this item ID. */
  selected?: boolean
}>(), { showOne: false, selected: undefined })

defineEmits<{ toggle: [itemId: number] }>()
</script>

<template>
  <span class="inventory-tile" :class="{ 'inventory-tile--empty': count === 0, 'inventory-tile--selected': selected === true }" :title="title">
    <ItemIcon :item-id="itemId" :icon-url="iconUrl" loading="lazy" :size="56" />
    <input
      v-if="selected !== undefined"
      type="checkbox"
      class="inventory-tile__select"
      data-test="protect-checkbox"
      :checked="selected"
      :aria-label="`Protect item #${itemId}`"
      @change="$emit('toggle', itemId)"
    >
    <span v-if="count > 0 && (showOne || count > 1)" class="inventory-tile__quantity" data-test="item-count">
      {{ count.toLocaleString('en-US') }}
    </span>
  </span>
</template>

<style scoped>
.inventory-tile {
  position: relative;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 60px;
  height: 60px;
  padding: 1px;
  border: 1px solid var(--color-border);
  border-radius: 2px;
  background: var(--color-surface);
  overflow: hidden;
}
.inventory-tile__select {
  position: absolute;
  top: 1px;
  left: 1px;
  z-index: 1;
  width: 18px;
  height: 18px;
  margin: 0;
  cursor: pointer;
  accent-color: var(--color-accent, #2f6fdb);
}
.inventory-tile--selected { outline: 2px solid var(--color-accent, #2f6fdb); outline-offset: -2px; }
.inventory-tile--empty :deep(.item-icon__image),
.inventory-tile--empty :deep(.item-icon__fallback) { filter: grayscale(1); opacity: 0.38; }
.inventory-tile__quantity {
  position: absolute;
  right: 2px;
  bottom: 1px;
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
  text-shadow: -1px -1px 2px #000, 1px -1px 2px #000, -1px 1px 2px #000, 1px 1px 2px #000;
  pointer-events: none;
}
</style>
