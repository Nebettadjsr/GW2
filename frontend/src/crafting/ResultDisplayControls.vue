<script setup lang="ts">
import { ref, watch } from 'vue'
import { MIN_MAX_DISPLAYED } from './useProfitTableView'

const props = defineProps<{
  hideZeroCraftable: boolean
  hideNotAllowed: boolean
  hideNonPositiveProfit: boolean
  maxDisplayed: number
  showAll: boolean
}>()

const emit = defineEmits<{
  'update:hideZeroCraftable': [value: boolean]
  'update:hideNotAllowed': [value: boolean]
  'update:hideNonPositiveProfit': [value: boolean]
  'update:maxDisplayed': [value: number]
  'update:showAll': [value: boolean]
}>()

const maxRejected = ref(false)

watch(
  () => props.maxDisplayed,
  () => {
    maxRejected.value = false
  }
)

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

function onShowAllChange(event: Event): void {
  const value = checkedValue(event)
  if (value !== null) emit('update:showAll', value)
}

function onMaxDisplayedChange(event: Event): void {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return

  const parsed = Number(target.value)

  if (
    target.value.trim() !== '' &&
    Number.isFinite(parsed) &&
    Math.trunc(parsed) >= MIN_MAX_DISPLAYED
  ) {
    maxRejected.value = false
    emit('update:maxDisplayed', Math.trunc(parsed))
    return
  }

  maxRejected.value = true
  target.value = String(props.maxDisplayed)
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

    <div class="display-controls__limit">
      <label>
        Show at most

        <input
          type="number"
          :min="MIN_MAX_DISPLAYED"
          step="1"
          data-test="max-displayed"
          :value="maxDisplayed"
          :disabled="showAll"
          @change="onMaxDisplayedChange"
        />
      </label>

      <label>
        <input
          type="checkbox"
          data-test="show-all"
          :checked="showAll"
          @change="onShowAllChange"
        />
        Show all
      </label>
    </div>

    <p
      v-if="maxRejected"
      class="notice notice--warning"
      role="status"
      data-test="max-displayed-rejected"
    >
      A maximum has to be a whole number of at least {{ MIN_MAX_DISPLAYED }}; it is still
      {{ maxDisplayed }}.
    </p>
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
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-4);
}

.display-controls__limit {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-4);
}

.display-controls__limit label {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

input[type='number'] {
  width: 6rem;
}
</style>