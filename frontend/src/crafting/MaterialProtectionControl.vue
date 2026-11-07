<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMaterialProtection } from '@/materials/materialProtectionState'

/**
 * Crafting Profit's Material Protection switch. It reads and writes only the canonical per-account
 * preference owned by `materialProtectionState`; pending Bank/Materials edits are never shown or used.
 * The calculation request is built from that committed state in `useCraftingProfit`.
 */
const protection = useMaterialProtection()
const ready = computed(() => protection.state.status === 'ready')
const count = computed(() => protection.state.committed.length)
const enabled = computed(() => protection.state.activation && count.value > 0)
const warning = ref(false)
const hovered = ref(false)
const focused = ref(false)
const infoOpen = computed(() => (hovered.value || focused.value) && !infoDismissed.value)
const infoDismissed = ref(false)
/** UI-only origin hint; an unknown (legacy) origin honestly shows both destinations. */
const sources = computed(() => {
  const s = protection.state.committedSources
  return s === null || s.length === 0 ? (['bank', 'materials'] as const) : s
})
const showBank = computed(() => sources.value.includes('bank'))
const showMaterials = computed(() => sources.value.includes('materials'))

function onEnter(): void {
  hovered.value = true
  infoDismissed.value = false
}
function onFocusIn(): void {
  focused.value = true
  infoDismissed.value = false
}
function onFocusOut(event: FocusEvent): void {
  const next = event.relatedTarget
  if (next instanceof Node && (event.currentTarget as Node).contains(next)) return
  focused.value = false
}
function closeInfo(): void {
  infoDismissed.value = true
}

function dismiss(): void {
  warning.value = false
  closeInfo()
}

function onToggle(event: Event): void {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  if (target.checked && count.value === 0) {
    warning.value = true
    target.checked = false
    return
  }
  warning.value = false
  protection.setActivation(target.checked)
}
</script>

<template>
  <div v-if="ready" class="protection" data-test="protection-control" @keydown.esc="dismiss">
    <div class="protection__row">
      <label class="protection__toggle">
        <input type="checkbox" data-test="protection-toggle" :checked="enabled" @change="onToggle" />
        <span>Respect excluded items/ mats</span>
      </label>
      <span
        class="protection__info"
        data-test="protection-info"
        @mouseenter="onEnter"
        @mouseleave="hovered = false"
        @focusin="onFocusIn"
        @focusout="onFocusOut"
      >
        <button
          type="button"
          class="protection__info-button"
          data-test="protection-info-button"
          aria-label="About excluded items/ mats"
          :aria-expanded="infoOpen"
          aria-controls="protection-info-panel"
        >
          i
        </button>
        <span
          v-show="infoOpen"
          id="protection-info-panel"
          class="protection__panel"
          role="group"
          aria-label="Saved excluded items"
          data-test="protection-panel"
        >
          <span data-test="protection-count">
            {{ count }} {{ count === 1 ? 'item' : 'items' }} in the saved selection
          </span>
          <span v-if="count > 0" data-test="protection-links">
            Change selection in
            <a v-if="showBank" href="#/bank" data-test="protection-bank-link">Bank</a>
            <template v-if="showBank && showMaterials"> or </template>
            <a v-if="showMaterials" href="#/materials" data-test="protection-materials-link">Materials</a>
          </span>
        </span>
      </span>
    </div>
    <div
      v-if="warning && count === 0"
      class="notice notice--warning protection__popup"
      role="alert"
      data-test="protection-empty-warning"
    >
      Select at least one item in Bank or Materials and save the selection before turning protection on.
      <a href="#/bank">Bank</a> · <a href="#/materials">Materials</a>
      <button type="button" data-test="protection-warning-dismiss" @click="dismiss">Dismiss</button>
    </div>
  </div>
</template>

<style scoped>
.protection {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
  min-width: 0;
}
.protection__row { display: flex; align-items: center; gap: var(--space-2); }
.protection__toggle { display: flex; align-items: center; gap: var(--space-2); }
.protection__info { position: relative; display: inline-flex; }
.protection__info-button {
  box-sizing: border-box;
  width: 1.25rem;
  height: 1.25rem;
  min-width: 1.25rem;
  min-height: 0;
  flex: 0 0 1.25rem;
  padding: 0;
  border-radius: 50%;
  font-size: 0.75rem;
  line-height: 1;
  font-style: italic;
}
/* The panel touches the icon (padding, not margin) so the pointer can travel into it without closing it. */
.protection__panel {
  position: absolute;
  top: 100%;
  left: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  width: max-content;
  max-width: min(22rem, 90vw);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border, #52616e);
  border-radius: 4px;
  background: var(--surface, #1b242c);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
}
.protection__popup {
  position: absolute;
  top: 100%;
  left: 0;
  z-index: 20;
  width: max-content;
  max-width: min(24rem, 90vw);
  margin: var(--space-1) 0 0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
}
</style>
