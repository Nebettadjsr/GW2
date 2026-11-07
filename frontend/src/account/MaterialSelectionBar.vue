<script setup lang="ts">
import { computed } from 'vue'
import { useMaterialProtection } from '@/materials/materialProtectionState'

const protection = useMaterialProtection()
const ready = computed(() => protection.state.status === 'ready')
const dirty = computed(() => protection.dirty.value)
const committedCount = computed(() => protection.state.committed.length)
const pendingCount = computed(() => protection.state.pending.length)
function items(n: number): string { return `${n} ${n === 1 ? 'item' : 'items'}` }
</script>

<template>
  <section v-if="ready" class="selection-bar" aria-label="Material protection selection" data-test="selection-bar">
    <p class="selection-bar__committed" data-test="selection-committed">
      Committed protection: {{ items(committedCount) }}.
    </p>
    <p role="status" class="selection-bar__state" :data-state="dirty ? 'pending' : 'clean'" data-test="selection-state">
      <template v-if="dirty">Unconfirmed changes: {{ items(pendingCount) }} selected, not yet applied.</template>
      <template v-else>No pending changes.</template>
    </p>
    <span class="selection-bar__actions">
      <button type="button" :disabled="!dirty" data-test="selection-confirm" @click="protection.commit()">Confirm protection selection</button>
      <button type="button" class="button--secondary" :disabled="!dirty" data-test="selection-discard" @click="protection.discardPending()">Discard changes</button>
    </span>
  </section>
</template>

<style scoped>
.selection-bar { margin-block: var(--space-3); display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2) var(--space-3); }
.selection-bar p { margin: 0; }
.selection-bar__state[data-state='pending'] { font-weight: 700; }
.selection-bar__actions { display: flex; gap: var(--space-2); }
</style>
