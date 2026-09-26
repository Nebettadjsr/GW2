<script setup lang="ts">
import { ref, watch } from 'vue'
import { MIN_MAX_DISPLAYED } from './useProfitTableView'

/**
 * DOMAIN_SPEC 2.1.1's result-display controls: three filters over the rows already returned, plus a
 * changeable maximum displayed recipe count and Show all.
 *
 * They belong to the results region, not to the calculation controls panel, and they say so: nothing
 * here submits a calculation, changes what was asked for, or changes the backend's own per-recipe
 * simulation cap. Every row the calculation returned stays loaded; these controls only decide which
 * of them is listed.
 *
 * The maximum is applied on `change` rather than on every keystroke, and a value that could not
 * display anything is refused — the control returns to the maximum in effect and says why, rather
 * than blanking the list or quietly showing everything.
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
    <legend>Result display</legend>

    <p class="meta display-controls__note">
      These change which of the calculated recipes are listed below. They never recalculate anything,
      never change the calculation controls above, and never change how far the backend simulated each
      recipe.
    </p>

    <div class="display-controls__group">
      <label>
        <input
          type="checkbox"
          data-test="filter-zero-craftable"
          :checked="hideZeroCraftable"
          @change="onZeroCraftableChange"
        />
        Hide recipes with a craftable count of 0
      </label>

      <label>
        <input
          type="checkbox"
          data-test="filter-not-allowed"
          :checked="hideNotAllowed"
          @change="onNotAllowedChange"
        />
        Hide recipes reported as not allowed
      </label>

      <label>
        <input
          type="checkbox"
          data-test="filter-non-positive-profit"
          :checked="hideNonPositiveProfit"
          @change="onNonPositiveProfitChange"
        />
        Hide recipes with a profit per craft of 0 or less
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
          @change="onMaxDisplayedChange"
        />
        recipes
      </label>

      <label>
        <input type="checkbox" data-test="show-all" :checked="showAll" @change="onShowAllChange" />
        Show all matching recipes
      </label>
    </div>

    <p v-if="showAll" class="meta" data-test="max-displayed-note">
      Show all is on, so the maximum above is not applied. Switch it off to return to
      {{ maxDisplayed }}.
    </p>
    <p v-if="maxRejected" class="notice notice--warning" role="status" data-test="max-displayed-rejected">
      A maximum has to be a whole number of at least {{ MIN_MAX_DISPLAYED }}. That entry was not
      applied, so the maximum is still {{ maxDisplayed }}.
    </p>
  </fieldset>
</template>

<style scoped>
.display-controls {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* Why this group is not the calculation settings, said once and kept next to the controls. */
.display-controls__note {
  max-width: var(--prose-max);
  margin: 0;
}

.display-controls__group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-5);
}

input[type='number'] {
  width: 6rem;
}
</style>
