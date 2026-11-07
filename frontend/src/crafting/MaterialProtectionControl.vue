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
  <div v-if="ready" class="protection" data-test="protection-control">
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
    <p v-if="warning && count === 0" class="notice notice--warning" role="alert" data-test="protection-empty-warning">
      Select at least one item in Bank or Materials and save the selection before turning protection on.
      <a href="#/bank">Bank</a> · <a href="#/materials">Materials</a>
    </p>
  </div>
</template>

<style scoped>
.protection { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2) var(--space-3); margin-top: var(--space-3); }
.protection__toggle { display: flex; align-items: center; gap: var(--space-2); }
.protection p { flex-basis: 100%; margin: 0; }
</style>
