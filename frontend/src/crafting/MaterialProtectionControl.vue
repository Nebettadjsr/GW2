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

function dismiss(): void {
  warning.value = false
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
    <label class="protection__toggle">
      <input type="checkbox" data-test="protection-toggle" :checked="enabled" @change="onToggle" />
      <span>Protect selected materials</span>
    </label>
    <span class="meta" data-test="protection-count">
      {{ count }} {{ count === 1 ? 'item' : 'items' }} in the saved selection
    </span>
    <span v-if="count > 0" class="meta" data-test="protection-links">
      · Change selection in <a href="#/bank" data-test="protection-bank-link">Bank</a> or
      <a href="#/materials" data-test="protection-materials-link">Materials</a>
    </span>
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
  grid-column: 1 / -1;
  width: 100%;
  margin-top: var(--space-3);
}
.protection__toggle { display: flex; align-items: center; gap: var(--space-2); }
.protection__popup {
  position: absolute;
  top: 100%;
  left: 0;
  z-index: 20;
  max-width: min(32rem, 100%);
  margin: var(--space-1) 0 0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
}
</style>
