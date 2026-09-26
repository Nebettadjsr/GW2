<script setup lang="ts">
import { ref, watch } from 'vue'
import { MIN_MAX_DISPLAYED } from './useProfitTableView'

/**
 * DOMAIN_SPEC 2.1.1's result-display controls: three filters over the rows already returned, plus a
 * changeable maximum displayed recipe count and Show all.
 *
 * Since STORY-WEB-011 they are the *Displayed results* subgroup **inside** the calculation-controls
 * panel, beside the Calculation subgroup rather than in a separate top-level section. The two groups
 * are told apart by their own legends and borders, not by a paragraph saying so: nothing in here
 * submits a calculation, changes what was asked for, or changes the backend's own per-recipe
 * simulation cap. Every row the calculation returned stays loaded; these controls only decide which
 * of them is listed.
 *
 * The search over the answer is the fourth thing that decides what is listed, so the screen passes it
 * into this group through the `search` slot rather than leaving it among the calculation settings.
 *
 * The maximum is applied on `change` rather than on every keystroke, and a value that could not
 * display anything is refused — the control returns to the maximum in effect and says why, rather
 * than blanking the list or quietly showing everything. Show all disables the maximum instead of
 * explaining in prose that it is not being applied; switching it off puts the typed maximum back.
 */
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

// An accepted maximum retires the refusal message; it described the entry, not a lasting state.
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
  if (target.value.trim() !== '' && Number.isFinite(parsed) && Math.trunc(parsed) >= MIN_MAX_DISPLAYED) {
    maxRejected.value = false
    emit('update:maxDisplayed', Math.trunc(parsed))
    return
  }

  maxRejected.value = true
  target.value = String(props.maxDisplayed)
}
</script>

<template>
  <fieldset class="display-controls" data-test="display-controls">
    <legend>Displayed results</legend>

    <div class="display-controls__group">
      <slot name="search" />

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

    <div class="display-controls__group">
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
        <input type="checkbox" data-test="show-all" :checked="showAll" @change="onShowAllChange" />
        Show all
      </label>
    </div>

    <p v-if="maxRejected" class="notice notice--warning" role="status" data-test="max-displayed-rejected">
      A maximum has to be a whole number of at least {{ MIN_MAX_DISPLAYED }}; it is still
      {{ maxDisplayed }}.
    </p>
  </fieldset>
</template>

<style scoped>
.display-controls {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.display-controls__group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-4);
}

input[type='number'] {
  width: 6rem;
}
</style>
