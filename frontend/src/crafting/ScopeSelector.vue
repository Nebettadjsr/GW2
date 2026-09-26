<script setup lang="ts">
import type { ScopeOption } from './scopeOptions'

/**
 * Crafting Profit's sole calculation-scope control (DOMAIN_SPEC 2.2.1). Its entries come from the
 * backend selector route; this component neither builds nor filters them.
 *
 * It stays usable while a calculation is in flight: a backend calculation can take seconds, and
 * locking the selection for its duration would both strand the user and make the screen's
 * superseded-response handling unreachable. Only a selector that has no entries yet is disabled.
 */
defineProps<{
  options: readonly ScopeOption[]
  selectedId: string | null
}>()

const emit = defineEmits<{ select: [scopeOptionId: string] }>()

function onChange(event: Event): void {
  const target = event.target
  if (target instanceof HTMLSelectElement) emit('select', target.value)
}
</script>

<template>
  <label class="scope">
    <span>Discipline</span>
    <select
      data-test="scope-selector"
      :value="selectedId ?? ''"
      :disabled="options.length === 0"
      @change="onChange"
    >
      <option v-for="option in options" :key="option.id" :value="option.id">{{ option.label }}</option>
    </select>
  </label>
</template>

<style scoped>
select {
  /* Wide enough for a character-and-discipline entry, but it shrinks with the available width. */
  width: 16rem;
}
</style>
