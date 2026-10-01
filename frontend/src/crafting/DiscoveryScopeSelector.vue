<script setup lang="ts">
import type { DiscoveryScopeOption } from './discoveryScopeOptions'

defineProps<{
  options: readonly DiscoveryScopeOption[]
  selectedId: string | null
}>()

const emit = defineEmits<{ select: [scopeOptionId: string] }>()

function onScopeChange(event: Event): void {
  const target = event.target
  if (target instanceof HTMLSelectElement) emit('select', target.value)
}
</script>

<template>
  <label class="scope">
    <span>Character / discipline</span>
    <select
      data-test="discovery-scope-selector"
      :value="selectedId ?? ''"
      :disabled="options.length === 0"
      @change="onScopeChange"
    >
      <option v-for="option in options" :key="option.id" :value="option.id">
        {{ option.label }}
      </option>
    </select>
  </label>
</template>

<style scoped>
.scope {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

select {
  width: 18rem;
  max-width: 100%;
}
</style>
