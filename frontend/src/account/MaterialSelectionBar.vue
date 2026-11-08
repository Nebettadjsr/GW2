<script setup lang="ts">
import { computed } from 'vue'
import { useMaterialProtection, type SelectionView } from '@/materials/materialProtectionState'

const props = defineProps<{ view: SelectionView; itemIds: readonly number[] }>()
const protection = useMaterialProtection()
const ready = computed(() => protection.state.status === 'ready')
const dirty = computed(() => protection.dirtyFor(props.view))
const committedCount = computed(() => protection.committedFor(props.view).length)
const pendingCount = computed(() => protection.pendingFor(props.view).length)
const pendingIds = computed(() => new Set(protection.pendingFor(props.view)))
const pageFullySelected = computed(() => props.itemIds.length > 0 && props.itemIds.every(id => pendingIds.value.has(id)))
const storageNotice = computed(() => {
  switch (protection.state.diagnostic) {
    case null: return null
    case 'quota-exceeded': return 'Browser storage is full, so this selection could not be saved. It stays usable in this tab only and will not be kept after you restart or reload.'
    case 'corrupt':
    case 'unsupported-version': return 'The previously saved selection could not be read, so it was ignored. Selections you confirm now are kept in this tab and may not be kept after you restart.'
    default: return 'Your browser cannot save or restore this selection. It stays usable in this tab only and will not be kept after you restart or reload.'
  }
})
function togglePage(): void {
  protection.togglePendingGroup(props.itemIds, props.view)
}
function items(n: number): string { return `${n} ${n === 1 ? 'item' : 'items'}` }
</script>

<template>
  <section v-if="ready" class="selection-bar" aria-label="Material protection selection" data-test="selection-bar">
    <p class="selection-bar__committed" data-test="selection-committed">
      Saved selection: {{ items(committedCount) }}.
    </p>
    <p class="selection-bar__note meta" data-test="selection-activation-note">
      Select materials that can be excluded from Crafting Profit calculations.
    </p>
    <p v-if="storageNotice" class="notice notice--warning" role="status" data-test="selection-storage-notice">{{ storageNotice }}</p>
    <p role="status" class="selection-bar__state" :data-state="dirty ? 'pending' : 'clean'" data-test="selection-state">
      <template v-if="dirty && pendingCount === 0">Selection cleared — not saved.</template>
      <template v-else-if="dirty">Unsaved changes: {{ items(pendingCount) }} selected.</template>
      <template v-else>No pending changes.</template>
    </p>
    <span class="selection-bar__actions">
      <button type="button" class="button--secondary" :disabled="itemIds.length === 0" data-test="selection-toggle" @click="togglePage()">{{ pageFullySelected ? 'Deselect all' : 'Select all' }}</button>
      <button type="button" :disabled="!dirty" data-test="selection-confirm" @click="protection.commit(props.view)">Save selection</button>
      <button type="button" class="button--secondary" :disabled="!dirty" data-test="selection-discard" @click="protection.discardPending(props.view)">Discard changes</button>
    </span>
  </section>
</template>

<style scoped>
.selection-bar { margin-block: var(--space-3); display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--space-2); }
.selection-bar p { margin: 0; }
.selection-bar__state { min-block-size: 2lh; }
.selection-bar__state[data-state='pending'] { font-weight: 700; }
.selection-bar__actions { display: flex; flex-wrap: wrap; gap: var(--space-2); min-width: 0; max-width: 100%; }
.selection-bar__actions [data-test='selection-toggle'] { min-inline-size: 8.5em; }
</style>
