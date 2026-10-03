<script setup lang="ts">
import type { EffectiveDiscoverySettings } from '@/api/types'

const props = defineProps<{ settings: EffectiveDiscoverySettings | null }>()
const emit = defineEmits<{ apply: [settings: EffectiveDiscoverySettings] }>()

function emitWith(changed: Partial<EffectiveDiscoverySettings>): void {
  if (props.settings !== null) emit('apply', { ...props.settings, ...changed })
}

function checked(event: Event): boolean | null {
  return event.target instanceof HTMLInputElement ? event.target.checked : null
}

function listing(event: Event): boolean | null {
  return event.target instanceof HTMLSelectElement ? event.target.value === 'listing' : null
}

function onUseOwnMaterialsChange(event: Event): void {
  const value = checked(event)
  if (value !== null) emitWith({ useOwnMats: value })
}

function onAllowBuyingChange(event: Event): void {
  const value = checked(event)
  if (value !== null) emitWith({ allowBuying: value })
}

function onSellPriceChange(event: Event): void {
  const value = listing(event)
  if (value !== null) emitWith({ listingSell: value })
}

function onBuyPriceChange(event: Event): void {
  const value = listing(event)
  if (value !== null) emitWith({ listingBuy: value })
}
</script>

<template>
  <div v-if="settings !== null" class="settings" data-test="discovery-settings-form">
    <div class="settings__checks">
      <label class="check-row">
        <input type="checkbox" data-test="discovery-setting-useOwnMats" :checked="settings.useOwnMats"
          @change="onUseOwnMaterialsChange" />
        <span>Use own materials</span>
      </label>
      <label class="check-row">
        <input type="checkbox" data-test="discovery-setting-allowBuying" :checked="settings.allowBuying"
          @change="onAllowBuyingChange" />
        <span>Allow buying</span>
      </label>
    </div>
    <div class="price-settings">
      <label class="price-setting">
        <span>Sell price</span>
        <select data-test="discovery-setting-listingSell" :value="settings.listingSell ? 'listing' : 'instant'"
          @change="onSellPriceChange">
          <option value="instant">Instant sell</option>
          <option value="listing">Listing sell</option>
        </select>
      </label>
      <label class="price-setting">
        <span>Buy price</span>
        <select data-test="discovery-setting-listingBuy" :value="settings.listingBuy ? 'listing' : 'instant'"
          @change="onBuyPriceChange">
          <option value="instant">Instant buy</option>
          <option value="listing">Listing buy</option>
        </select>
      </label>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: var(--space-4);
}
.settings__checks { display: flex; flex-direction: column; align-items: flex-start; gap: var(--space-2); }
.check-row { display: flex; align-items: center; gap: var(--space-2); }
.price-settings { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3) var(--space-4); }
@media (max-width: 640px) { .settings { grid-template-columns: minmax(0, 1fr); } }
.price-setting { display: flex; align-items: center; gap: var(--space-2); white-space: nowrap; }
.price-setting select { min-width: 9rem; }
</style>
