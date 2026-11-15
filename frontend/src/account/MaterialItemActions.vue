<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMaterialProtection, validReservationQuantity, type SelectionView } from '@/materials/materialProtectionState'
import { requestProfitSearch } from '@/materials/profitSearchIntent'
import { useHashRoute } from '@/shell/useHashRoute'

export interface ItemActionTarget { itemId: number; name: string | null; count: number; x: number; y: number }

const props = defineProps<{ view: SelectionView; target: ItemActionTarget | null }>()
const emit = defineEmits<{ close: [] }>()
const protection = useMaterialProtection()
const route = useHashRoute()

const mode = ref<'menu' | 'editor'>('menu')
const menu = ref<HTMLElement | null>(null)
const input = ref<HTMLInputElement | null>(null)
const draft = ref('')
const error = ref<string | null>(null)
const label = computed(() => props.target === null ? '' : props.target.name ?? `Item #${props.target.itemId}`)
const storeLabel = computed(() => props.view === 'bank' ? 'bank' : 'material storage')
const placed = ref<{ left: string; top: string } | null>(null)
const position = computed(() => placed.value ?? { left: '4px', top: '4px', visibility: 'hidden' as const })
/** The control that opened this layer; focus returns to it when it is still connected. */
let opener: HTMLElement | null = null

function clampToViewport(): void {
  const t = props.target
  const el = menu.value
  if (t === null || el === null) return
  const margin = 4
  const x = Math.max(margin, Math.min(t.x, window.innerWidth - el.offsetWidth - margin))
  const y = Math.max(margin, Math.min(t.y, window.innerHeight - el.offsetHeight - margin))
  placed.value = { left: `${x}px`, top: `${y}px` }
}

watch(() => props.target, async (target, previous) => {
  mode.value = 'menu'
  error.value = null
  placed.value = null
  if (target === null) return
  if (previous === null || previous === undefined) {
    opener = document.activeElement instanceof HTMLElement && document.activeElement !== document.body ? document.activeElement : null
  }
  draft.value = String(protection.quantityFor(props.view, target.itemId) || '')
  await nextTick()
  clampToViewport()
  // Wait for the visible placement to be rendered; a visibility:hidden button cannot take focus.
  await nextTick()
  menu.value?.querySelector<HTMLElement>('button')?.focus()
})

function restoreFocus(): void {
  const el = opener
  opener = null
  if (el !== null && el.isConnected) void nextTick(() => el.focus())
}
function close(restore = true): void {
  emit('close')
  if (restore) restoreFocus()
  else opener = null
}
function openEditor(): void {
  mode.value = 'editor'
  void nextTick(() => input.value?.focus())
}
function openProfit(): void {
  const accountId = protection.state.accountId
  if (accountId !== null && props.target !== null) requestProfitSearch(accountId, label.value)
  // Navigation moves the user to another screen: do not pull focus back to the hidden tile.
  close(false)
  route.navigate('crafting')
}
function apply(): void {
  const text = draft.value.trim()
  const value = text === '' ? 0 : /^\d+$/.test(text) ? Number(text) : Number.NaN
  if (props.target === null || !validReservationQuantity(value)) {
    error.value = 'Enter a whole number from 0 to 2,147,483,647.'
    return
  }
  protection.reserveQuantity(props.target.itemId, props.view, value)
  close()
}

const root = ref<HTMLElement | null>(null)
function focusables(): HTMLElement[] {
  return Array.from(root.value?.querySelectorAll<HTMLElement>('button, input, [href], [tabindex]:not([tabindex="-1"])') ?? [])
    .filter(el => !(el as HTMLButtonElement).disabled)
}
/** Tab stays inside the open menu/dialog; arrows move within the menu. */
function onKeydown(event: KeyboardEvent): void {
  const items = focusables()
  if (items.length === 0) return
  const index = items.indexOf(document.activeElement as HTMLElement)
  if (event.key === 'Tab') {
    event.preventDefault()
    const next = event.shiftKey ? (index <= 0 ? items.length - 1 : index - 1) : (index === items.length - 1 ? 0 : index + 1)
    items[next]?.focus()
  } else if (mode.value === 'menu' && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
    event.preventDefault()
    const step = event.key === 'ArrowDown' ? 1 : -1
    items[(index + step + items.length) % items.length]?.focus()
  }
}
</script>

<template>
  <div v-if="target !== null" ref="root" class="item-actions" data-test="item-actions" @keydown.esc.stop="close()" @keydown="onKeydown">
    <div class="item-actions__backdrop" data-test="item-actions-backdrop" @click="close()" @contextmenu.prevent="close()" />
    <div v-if="mode === 'menu'" ref="menu" class="item-actions__menu" role="menu" :aria-label="`Actions for ${label}`"
      :style="position" data-test="item-menu">
      <button type="button" role="menuitem" data-test="menu-reserve" @click="openEditor">Reserve quantity…</button>
      <button type="button" role="menuitem" data-test="menu-open-profit" @click="openProfit">Open Crafting Profit</button>
    </div>
    <form v-else class="item-actions__dialog" role="dialog" aria-modal="true" :aria-label="`Reserve ${label}`"
      data-test="reserve-dialog" @submit.prevent="apply">
      <h2>{{ label }}</h2>
      <p class="meta">In {{ storeLabel }}: {{ target.count.toLocaleString('en-US') }}</p>
      <label>
        Keep in {{ storeLabel }}
        <input ref="input" v-model="draft" type="text" inputmode="numeric" autocomplete="off" data-test="reserve-input"
          :aria-invalid="error !== null">
      </label>
      <p v-if="error" class="notice notice--error" role="alert" data-test="reserve-error">{{ error }}</p>
      <span class="item-actions__buttons">
        <button type="submit" data-test="reserve-apply">Apply</button>
        <button type="button" class="button--secondary" data-test="reserve-cancel" @click="close()">Cancel</button>
      </span>
    </form>
  </div>
</template>

<style scoped>
.item-actions { position: fixed; inset: 0; z-index: 50; }
.item-actions__backdrop { position: absolute; inset: 0; backdrop-filter: blur(2px); background: rgb(0 0 0 / 0.2); }
.item-actions__menu { position: fixed; display: grid; min-inline-size: 12rem; padding: var(--space-1); border: 1px solid var(--color-border);
  background: var(--color-surface); box-shadow: 0 4px 16px rgb(0 0 0 / 0.35); }
.item-actions__menu button { text-align: start; }
.item-actions__dialog { position: absolute; inset-block-start: 50%; inset-inline-start: 50%; transform: translate(-50%, -50%);
  inline-size: min(22rem, calc(100vw - 2rem)); display: grid; gap: var(--space-2); padding: var(--space-4);
  border: 1px solid var(--color-border); background: var(--color-surface); box-shadow: 0 8px 32px rgb(0 0 0 / 0.45); }
.item-actions__dialog h2, .item-actions__dialog p { margin: 0; }
.item-actions__dialog label { display: grid; gap: var(--space-1); }
.item-actions__buttons { display: flex; gap: var(--space-2); }
</style>
