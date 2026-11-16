<script setup lang="ts">
import { computed, nextTick } from 'vue'
import ItemIcon from '@/items/ItemIcon.vue'

const props = withDefaults(defineProps<{
  itemId: number
  count: number
  iconUrl: string | null
  title: string
  showOne?: boolean
  /** Undefined: the tile is not selectable. Otherwise the pending protection state of this item ID. */
  selected?: boolean
  /** Finite keep-target for this item, when one is saved (0/undefined: none). */
  reserved?: number
  /** Native mixed (partially reserved) checkbox state of one stack. */
  mixed?: boolean
  /** Finite reservation of this stack, independent of the checked state; defaults to reserved-and-unchecked. */
  finite?: boolean
  /** Cached item name used for title and accessible labels; null falls back to the item ID. */
  itemName?: string | null
}>(), { showOne: false, selected: undefined, reserved: 0, mixed: false, finite: undefined, itemName: null })

const label = computed(() => props.itemName ?? props.title)
const isFinite = computed(() => props.finite ?? (props.reserved > 0 && props.selected !== true))
const emit = defineEmits<{ toggle: [itemId: number]; menu: [itemId: number, x: number, y: number] }>()

function openMenu(event: MouseEvent | KeyboardEvent, element: HTMLElement | null): void {
  if (props.selected === undefined) return
  event.preventDefault()
  // Make the initiating tile's checkbox the focused control so the menu layer can restore focus to it.
  const host = element ?? (event.currentTarget instanceof HTMLElement ? event.currentTarget : null)
  host?.querySelector<HTMLInputElement>('input.inventory-tile__select')?.focus({ preventScroll: true })
  const rect = element?.getBoundingClientRect()
  const x = event instanceof MouseEvent ? event.clientX : (rect?.left ?? 0)
  const y = event instanceof MouseEvent ? event.clientY : (rect?.bottom ?? 0)
  emit('menu', props.itemId, x, y)
}
/**
 * One activation toggles exactly once. A real activation delivers `click` and then `change`; a click
 * on a disconnected checkbox delivers only `click`. Handle the first and ignore its paired `change`.
 */
let clickedRecently = false
function onClick(event: MouseEvent): void {
  clickedRecently = true
  setTimeout(() => { clickedRecently = false }, 0)
  emit('toggle', props.itemId)
  // A rejected toggle leaves the model unchanged; do not let the native control drift from it.
  const input = event.currentTarget instanceof HTMLInputElement ? event.currentTarget : null
  void nextTick(() => { if (input !== null && input.checked !== (props.selected === true)) input.checked = props.selected === true })
}
function onChange(): void {
  if (clickedRecently) {
    clickedRecently = false
    return
  }
  emit('toggle', props.itemId)
}
function onKey(event: KeyboardEvent): void {
  if (event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey)) {
    openMenu(event, event.currentTarget instanceof HTMLElement ? event.currentTarget : null)
  }
}
</script>

<template>
  <component :is="selected === undefined ? 'span' : 'label'" class="inventory-tile"
    :class="{ 'inventory-tile--empty': count === 0, 'inventory-tile--selected': selected === true, 'inventory-tile--reserved': isFinite,
      'inventory-tile--selectable': selected !== undefined }"
    :title="itemName ?? title" @contextmenu="openMenu($event, null)">
    <ItemIcon :item-id="itemId" :icon-url="iconUrl" loading="lazy" :size="56" />
    <input
      v-if="selected !== undefined"
      type="checkbox"
      class="inventory-tile__select"
      data-test="protect-checkbox"
      :checked="selected"
      :indeterminate.prop="mixed"
      :aria-label="isFinite && reserved > 0 ? `Protect ${label} (keeping ${reserved})` : `Protect ${label}`"
      @click="onClick"
      @change="onChange"
      @keydown="onKey"
    >
    <span v-if="count > 0 && (showOne || count > 1)" class="inventory-tile__quantity" data-test="item-count">
      {{ count.toLocaleString('en-US') }}
    </span>
  </component>
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
.inventory-tile--selectable { cursor: pointer; }
.inventory-tile--selectable:focus-within { outline: 3px solid var(--color-focus, #fff); outline-offset: -3px; }
.inventory-tile--reserved { border: 3px solid #f2c400; }
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
}
</style>
