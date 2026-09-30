<script setup lang="ts">
import { computed } from 'vue'
import type { EffectiveSettings } from '@/api/types'

const props = withDefaults(
  defineProps<{
    settings: EffectiveSettings | null
    layoutPart?: 'values' | 'checks' | 'all'
  }>(),
  {
    layoutPart: 'all'
  }
)

const emit = defineEmits<{ apply: [settings: EffectiveSettings] }>()

const showValues = computed(
  () => props.layoutPart === 'values' || props.layoutPart === 'all'
)

const showChecks = computed(
  () => props.layoutPart === 'checks' || props.layoutPart === 'all'
)

const maxBuyGold = computed(() => {
  if (props.settings === null) return 0
  return Math.floor(props.settings.maxBuyCopper / 10000)
})

const maxBuySilver = computed(() => {
  if (props.settings === null) return 0
  return Math.floor((props.settings.maxBuyCopper % 10000) / 100)
})

const maxBuyCopperPart = computed(() => {
  if (props.settings === null) return 0
  return props.settings.maxBuyCopper % 100
})

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
  return target instanceof HTMLSelectElement
    ? target.value === 'listing'
    : null
}

function numberValue(event: Event): number | null {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return null

  const value = Number(target.value)
  if (!Number.isFinite(value)) return null

  return value
}

function onUseOwnMatsChange(event: Event): void {
  const useOwnMats = checkedValue(event)
  if (useOwnMats !== null) {
    emitWith({ useOwnMats })
  }
}

function onAllowBuyingChange(event: Event): void {
  const allowBuying = checkedValue(event)
  if (allowBuying !== null) {
    emitWith({ allowBuying })
  }
}

function onAllowDailyCraftsChange(event: Event): void {
  const allowDailyCrafts = checkedValue(event)
  if (allowDailyCrafts !== null) {
    emitWith({ allowDailyCrafts })
  }
}

function onListingSellChange(event: Event): void {
  const listingSell = isListingMode(event)
  if (listingSell !== null) {
    emitWith({ listingSell })
  }
}

function onListingBuyChange(event: Event): void {
  const listingBuy = isListingMode(event)
  if (listingBuy !== null) {
    emitWith({ listingBuy })
  }
}

function emitMaxBuy(gold: number, silver: number, copper: number): void {
  const normalizedGold = Math.max(0, Math.trunc(gold))
  const normalizedSilver = Math.max(0, Math.min(99, Math.trunc(silver)))
  const normalizedCopper = Math.max(0, Math.min(99, Math.trunc(copper)))

  emitWith({
    maxBuyCopper:
      normalizedGold * 10000 +
      normalizedSilver * 100 +
      normalizedCopper
  })
}

function onMaxBuyGoldChange(event: Event): void {
  const value = numberValue(event)
  if (value === null) return

  emitMaxBuy(
    value,
    maxBuySilver.value,
    maxBuyCopperPart.value
  )
}

function onMaxBuySilverChange(event: Event): void {
  const value = numberValue(event)
  if (value === null) return

  emitMaxBuy(
    maxBuyGold.value,
    value,
    maxBuyCopperPart.value
  )
}

function onMaxBuyCopperChange(event: Event): void {
  const value = numberValue(event)
  if (value === null) return

  emitMaxBuy(
    maxBuyGold.value,
    maxBuySilver.value,
    value
  )
}
</script>

<template>
  <div
    v-if="settings !== null"
    class="settings"
    data-test="settings-form"
  >
    <!-- LEFT SIDE -->
    <div v-if="showValues" class="settings__values">
      <div class="setting-row setting-row--max-buy">
        <span class="setting-row__label">Max buy</span>

        <label class="coin-input">
          <input
            type="number"
            min="0"
            step="1"
            data-test="setting-maxBuyGold"
            :value="maxBuyGold"
            aria-label="Maximum buy price in gold"
            @change="onMaxBuyGoldChange"
          />
          <span>g</span>
        </label>

        <label class="coin-input">
          <input
            type="number"
            min="0"
            max="99"
            step="1"
            data-test="setting-maxBuySilver"
            :value="maxBuySilver"
            aria-label="Maximum buy price in silver"
            @change="onMaxBuySilverChange"
          />
          <span>s</span>
        </label>

        <label class="coin-input">
          <input
            type="number"
            min="0"
            max="99"
            step="1"
            data-test="setting-maxBuyCopper"
            :value="maxBuyCopperPart"
            aria-label="Maximum buy price in copper"
            @change="onMaxBuyCopperChange"
          />
          <span>c</span>
        </label>
      </div>

      <div class="price-settings">
        <div class="price-setting">
          <label for="sell-price">Sell price</label>

          <select
            id="sell-price"
            data-test="setting-listingSell"
            :value="settings.listingSell ? 'listing' : 'instant'"
            @change="onListingSellChange"
          >
            <option value="instant">Instant sell</option>
            <option value="listing">Listing sell</option>
          </select>
        </div>

        <div class="price-setting">
          <label for="buy-price">Buy price</label>

          <select
            id="buy-price"
            data-test="setting-listingBuy"
            :value="settings.listingBuy ? 'listing' : 'instant'"
            @change="onListingBuyChange"
          >
            <option value="instant">Instant buy</option>
            <option value="listing">Listing buy</option>
          </select>
        </div>
      </div>
    </div>

    <!-- RIGHT SIDE -->
    <div v-if="showChecks" class="settings__checks">
      <label class="check-row">
        <input
          type="checkbox"
          data-test="setting-useOwnMats"
          :checked="settings.useOwnMats"
          @change="onUseOwnMatsChange"
        />
        <span>Use own materials</span>
      </label>

      <label class="check-row">
        <input
          type="checkbox"
          data-test="setting-allowBuying"
          :checked="settings.allowBuying"
          @change="onAllowBuyingChange"
        />
        <span>Allow buying</span>
      </label>

      <label class="check-row">
        <input
          type="checkbox"
          data-test="setting-allowDailyCrafts"
          :checked="settings.allowDailyCrafts"
          @change="onAllowDailyCraftsChange"
        />
        <span>Allow daily craft</span>
      </label>
    </div>
  </div>
</template>

<style scoped>
.settings {
  min-width: 0;
}

.settings__checks {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.settings__values{
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.setting-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 2.5rem;
}

.setting-row__label {
  white-space: nowrap;
}

.setting-row--max-buy .setting-row__label {
  margin-right: 0.25rem;
}

.coin-input {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}

.coin-input input {
  width: 3.25rem;
}

.coin-input:first-of-type input {
  width: 4rem;
}

.price-settings {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--space-5);
}

.price-setting {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}

.price-setting label {
  display: inline;
  margin: 0;
}

.price-setting select {
  width: 9rem;
}

.check-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 0;
}

@media (max-width: 700px) {
  .price-settings {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>