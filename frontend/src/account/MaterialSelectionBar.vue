<script setup lang="ts">
import { computed } from 'vue'
import { useMaterialProtection } from '@/materials/materialProtectionState'
import { MATERIAL_PRESETS, type MaterialPreset } from '@/materials/materialPresets'

const protection = useMaterialProtection()
const ready = computed(() => protection.state.status === 'ready')
const dirty = computed(() => protection.dirty.value)
const committedCount = computed(() => protection.state.committed.length)
const pendingCount = computed(() => protection.state.pending.length)
const storageNotice = computed(() => {
  switch (protection.state.diagnostic) {
    case null: return null
    case 'quota-exceeded': return 'Browser storage is full, so this selection could not be saved. It stays usable in this tab only and will not be kept after you restart or reload.'
    case 'corrupt':
    case 'unsupported-version': return 'The previously saved selection could not be read, so it was ignored. Selections you confirm now are kept in this tab and may not be kept after you restart.'
    default: return 'Your browser cannot save or restore this selection. It stays usable in this tab only and will not be kept after you restart or reload.'
  }
})
function applyPreset(preset: MaterialPreset): void {
  protection.setPending([...protection.state.pending, ...preset.itemIds])
}
function items(n: number): string { return `${n} ${n === 1 ? 'item' : 'items'}` }
</script>

<template>
  <section v-if="ready" class="selection-bar" aria-label="Material protection selection" data-test="selection-bar">
    <p class="selection-bar__committed" data-test="selection-committed">
      Saved selection: {{ items(committedCount) }}.
    </p>
    <p class="selection-bar__note meta" data-test="selection-activation-note">
      A saved selection only affects calculations when material protection is turned on in Crafting Profit. Confirming your selection here does not turn it on.
    </p>
    <p v-if="storageNotice" class="notice notice--warning" role="status" data-test="selection-storage-notice">{{ storageNotice }}</p>
    <p role="status" class="selection-bar__state" :data-state="dirty ? 'pending' : 'clean'" data-test="selection-state">
      <template v-if="dirty">Unconfirmed changes: {{ items(pendingCount) }} selected, not yet saved.</template>
      <template v-else>No pending changes.</template>
    </p>
    <span class="selection-bar__presets" role="group" aria-label="Selection presets" data-test="selection-presets">
      <button v-for="preset in MATERIAL_PRESETS" :key="preset.id" type="button" class="button--secondary" :title="preset.tooltip" :data-test="`preset-${preset.id}`" @click="applyPreset(preset)">{{ preset.label }}</button>
    </span>
    <span class="selection-bar__actions">
      <button type="button" :disabled="!dirty" data-test="selection-confirm" @click="protection.commit()">Save selection</button>
      <button type="button" class="button--secondary" :disabled="!dirty" data-test="selection-discard" @click="protection.discardPending()">Discard changes</button>
    </span>
  </section>
</template>

<style scoped>
.selection-bar { margin-block: var(--space-3); display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2) var(--space-3); }
.selection-bar p { margin: 0; }
.selection-bar__state[data-state='pending'] { font-weight: 700; }
.selection-bar__presets, .selection-bar__actions { display: flex; flex-wrap: wrap; gap: var(--space-2); min-width: 0; max-width: 100%; }
</style>
