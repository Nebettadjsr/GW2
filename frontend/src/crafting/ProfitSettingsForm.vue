<script setup lang="ts">
import type { EffectiveSettings } from '@/api/types'
import { formatCopper } from './formatCopper'

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
  <div
    v-if="settings !== null"
    class="settings"
    data-test="settings-form"
  >
    <div class="settings__checks">
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

      <div class="daily-setting">
        <label>
          <input
            type="checkbox"
            data-test="setting-dailyBuyInsteadOfCraft"
            :checked="settings.dailyBuyInsteadOfCraft"
            @change="onDailyBuyChange"
          />
          Buy daily items instead of crafting
        </label>

        <span class="daily-setting__hint">
          Off: only currently available daily materials are used. <br>
          On: one additional daily craft is allowed; remaining requirements are bought.
        </span>
      </div>
    </div>

    <div class="settings__values">
      <label class="setting-row">
        <span class="setting-row__label">Max buy</span>

        <input
          class="max-buy"
          type="number"
          min="0"
          step="1"
          data-test="setting-maxBuyCopper"
          :value="settings.maxBuyCopper"
          @change="onMaxBuyChange"
        />

        <span class="hint">{{ formatCopper(settings.maxBuyCopper) }}</span>
      </label>

      <label class="setting-row">
        <span class="setting-row__label">Sell price</span>

        <select
          data-test="setting-listingSell"
          :value="settings.listingSell ? 'listing' : 'instant'"
          @change="onListingSellChange"
        >
          <option value="instant">Instant sell</option>
          <option value="listing">Listing sell</option>
        </select>
      </label>

      <label class="setting-row">
        <span class="setting-row__label">Buy price</span>

        <select
          data-test="setting-listingBuy"
          :value="settings.listingBuy ? 'listing' : 'instant'"
          @change="onListingBuyChange"
        >
          <option value="instant">Instant buy</option>
          <option value="listing">Listing buy</option>
        </select>
      </label>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.settings__checks {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-4);
}

.settings__values {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-4);
}

.setting-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.setting-row__label {
  white-space: nowrap;
}

.max-buy {
  width: 7rem;
}

.hint {
  color: var(--color-muted);
  font-size: var(--text-sm);
  white-space: nowrap;
}

.daily-setting {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.daily-setting__hint {
  padding-left: 1.75rem;
  color: var(--color-muted);
  font-size: var(--text-sm);
  line-height: 1.25;
}
</style>