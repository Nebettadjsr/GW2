<script setup lang="ts">
const props = defineProps<{
  hideZeroCraftable: boolean
  hideNotAllowed: boolean
  hideNonPositiveProfit: boolean
}>()

const emit = defineEmits<{
  'update:hideZeroCraftable': [value: boolean]
  'update:hideNotAllowed': [value: boolean]
  'update:hideNonPositiveProfit': [value: boolean]
}>()

function checkedValue(event: Event): boolean | null {
  const target = event.target
  return target instanceof HTMLInputElement ? target.checked : null
}

function onZeroCraftableChange(event: Event): void {
  const value = checkedValue(event)
  if (value !== null) emit('update:hideZeroCraftable', value)
}

function onNotAllowedChange(event: Event): void {
  const value = checkedValue(event)
  if (value !== null) emit('update:hideNotAllowed', value)
}

function onNonPositiveProfitChange(event: Event): void {
  const value = checkedValue(event)
  if (value !== null) emit('update:hideNonPositiveProfit', value)
}
</script>

<template>
  <div class="display-controls" data-test="display-controls">
    <div class="display-controls__search">
      <slot name="search" />
    </div>

    <div class="display-controls__filters">
      <label>
        <input
          type="checkbox"
          data-test="filter-zero-craftable"
          :checked="hideZeroCraftable"
          @change="onZeroCraftableChange"
        />
        Hide craftable count 0
      </label>

      <label>
        <input
          type="checkbox"
          data-test="filter-not-allowed"
          :checked="hideNotAllowed"
          @change="onNotAllowedChange"
        />
        Hide recipes not allowed
      </label>

      <label>
        <input
          type="checkbox"
          data-test="filter-non-positive-profit"
          :checked="hideNonPositiveProfit"
          @change="onNonPositiveProfitChange"
        />
        Hide profit per craft ≤ 0
      </label>
    </div>
  </div>
</template>

<style scoped>
.display-controls {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.display-controls__search {
  display: flex;
  align-items: center;
}

.display-controls__filters {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.display-controls__filters label {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
</style>