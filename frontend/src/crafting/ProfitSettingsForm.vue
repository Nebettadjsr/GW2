<script setup lang="ts">
import type { EffectiveSettings } from '@/api/types'
import { formatCopper } from './formatCopper'

/**
 * Input controls for the settings the Profit contract supports (`CURRENT_ARCHITECTURE.md` 5.5).
 *
 * The control values are the settings the backend reported it calculated with, so no default is
 * repeated here; the form renders nothing until the backend has answered once. Each change emits
 * the complete settings object, which the screen submits as a fresh request. Validation stays with
 * the backend — an out-of-range value is rejected there and reported as a request failure.
 *
 * The controls stay usable while a calculation is in flight, for the reason given on
 * `ScopeSelector`: a later change simply supersedes the request already running.
 */
const props = defineProps<{
  settings: EffectiveSettings | null
}>()

const emit = defineEmits<{ apply: [settings: EffectiveSettings] }>()

function emitWith(changed: Partial<EffectiveSettings>): void {
  if (props.settings === null) return
  emit('apply', { ...props.settings, ...changed })
}

function checkedValue(event: Event): boolean | null {
  const target = event.target
  return target instanceof HTMLInputElement ? target.checked : null
}

function isListingMode(event: Event): boolean | null {
  const target = event.target
  return target instanceof HTMLSelectElement ? target.value === 'listing' : null
}

function onUseOwnMatsChange(event: Event): void {
  const useOwnMats = checkedValue(event)
  if (useOwnMats !== null) emitWith({ useOwnMats })
}

function onAllowBuyingChange(event: Event): void {
  const allowBuying = checkedValue(event)
  if (allowBuying !== null) emitWith({ allowBuying })
}

function onDailyBuyChange(event: Event): void {
  const dailyBuyInsteadOfCraft = checkedValue(event)
  if (dailyBuyInsteadOfCraft !== null) emitWith({ dailyBuyInsteadOfCraft })
}

function onListingSellChange(event: Event): void {
  const listingSell = isListingMode(event)
  if (listingSell !== null) emitWith({ listingSell })
}

function onListingBuyChange(event: Event): void {
  const listingBuy = isListingMode(event)
  if (listingBuy !== null) emitWith({ listingBuy })
}

function onMaxBuyChange(event: Event): void {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  const parsed = Number(target.value)
  if (!Number.isFinite(parsed)) return
  emitWith({ maxBuyCopper: Math.trunc(parsed) })
}
</script>

<template>
  <fieldset v-if="settings !== null" class="settings" data-test="settings-form">
    <legend>Profit settings</legend>

    <label>
      <input
        type="checkbox"
        data-test="setting-useOwnMats"
        :checked="settings.useOwnMats"
        @change="onUseOwnMatsChange"
      />
      Use own materials
    </label>

    <label>
      <input
        type="checkbox"
        data-test="setting-allowBuying"
        :checked="settings.allowBuying"
        @change="onAllowBuyingChange"
      />
      Allow buying
    </label>

    <label>
      <input
        type="checkbox"
        data-test="setting-dailyBuyInsteadOfCraft"
        :checked="settings.dailyBuyInsteadOfCraft"
        @change="onDailyBuyChange"
      />
      Buy daily items instead of crafting
    </label>

    <label>
      Max buy (copper)
      <input
        type="number"
        min="0"
        step="1"
        data-test="setting-maxBuyCopper"
        :value="settings.maxBuyCopper"
        @change="onMaxBuyChange"
      />
      <span class="hint">{{ formatCopper(settings.maxBuyCopper) }}</span>
    </label>

    <label>
      Sell price
      <select
        data-test="setting-listingSell"
        :value="settings.listingSell ? 'listing' : 'instant'"
        @change="onListingSellChange"
      >
        <option value="instant">Instant sell</option>
        <option value="listing">Listing sell</option>
      </select>
    </label>

    <label>
      Buy price
      <select
        data-test="setting-listingBuy"
        :value="settings.listingBuy ? 'listing' : 'instant'"
        @change="onListingBuyChange"
      >
        <option value="instant">Instant buy</option>
        <option value="listing">Listing buy</option>
      </select>
    </label>
  </fieldset>
</template>

<style scoped>
/* The settings group keeps the shared fieldset framing; only its internal flow is defined here. */
.settings {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3) var(--space-5);
}

input[type='number'] {
  width: 8rem;
}

.hint {
  color: var(--color-muted);
  font-size: var(--text-sm);
}
</style>
