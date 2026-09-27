<script setup lang="ts">
import type { EffectiveDiscoverySettings } from '@/api/types'
import { formatCopper } from './formatCopper'

/**
 * Input controls for the settings the Discovery contract supports: owned materials, buying, the buying
 * budget and the two Trading Post price modes (`CURRENT_ARCHITECTURE.md` 5.6).
 *
 * Exactly those five, and no more. `dailyBuyInsteadOfCraft` is not offered because the route does not
 * accept it — Discovery fixes it, and the fixed value is reported below as the backend's own fact
 * rather than as a control. Crafting Profit's "Allow non-Trading-Post materials" is not offered either:
 * it is not part of this contract, and adding it here would send the route a setting it has no field
 * for.
 *
 * The control values are the settings the backend reported it calculated with, so no default is
 * repeated here; the form renders nothing until the backend has answered once. Each change emits the
 * complete settings object, which the screen submits as a fresh request. Validation stays with the
 * backend — an out-of-range value is rejected there and reported as a request failure.
 */
const props = defineProps<{
  settings: EffectiveDiscoverySettings | null
}>()

const emit = defineEmits<{ apply: [settings: EffectiveDiscoverySettings] }>()

function emitWith(changed: Partial<EffectiveDiscoverySettings>): void {
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
  <fieldset v-if="settings !== null" class="settings" data-test="discovery-settings-form">
    <legend>Discovery settings</legend>

    <label>
      <input
        type="checkbox"
        data-test="discovery-setting-useOwnMats"
        :checked="settings.useOwnMats"
        @change="onUseOwnMatsChange"
      />
      Use own materials
    </label>

    <label>
      <input
        type="checkbox"
        data-test="discovery-setting-allowBuying"
        :checked="settings.allowBuying"
        @change="onAllowBuyingChange"
      />
      Allow buying
    </label>

    <label>
      Max buy (copper)
      <input
        type="number"
        min="0"
        step="1"
        data-test="discovery-setting-maxBuyCopper"
        :value="settings.maxBuyCopper"
        @change="onMaxBuyChange"
      />
      <span class="hint">{{ formatCopper(settings.maxBuyCopper) }}</span>
    </label>

    <label>
      Sell price
      <select
        data-test="discovery-setting-listingSell"
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
        data-test="discovery-setting-listingBuy"
        :value="settings.listingBuy ? 'listing' : 'instant'"
        @change="onListingBuyChange"
      >
        <option value="instant">Instant buy</option>
        <option value="listing">Listing buy</option>
      </select>
    </label>

    <!--
      Reported, not offered: the route has no field for it, so this states what the backend fixed
      rather than pretending the value is the user's to change.
    -->
    <p class="meta fixed-note" data-test="discovery-fixed-daily">
      Daily-limited materials are
      {{ settings.dailyBuyInsteadOfCraft ? 'bought instead of crafted' : 'crafted rather than bought' }}
      — the Discovery calculation fixes this and does not take it as a setting.
    </p>
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

/* A statement about the calculation rather than a control, so it takes its own row. */
.fixed-note {
  flex-basis: 100%;
  margin: 0;
}
</style>
