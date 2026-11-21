<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { ForgeCurrency, ForgeShoppingLine } from '@/api/types'
import { formatCopper } from '@/crafting/formatCopper'
import ItemIcon from '@/items/ItemIcon.vue'
import { forgeMerchant } from './forgeAcquisitionHints'

const props = defineProps<{
  itemId: number
  purchase?: ForgeShoppingLine
  currencies: ForgeCurrency[]
  compact?: boolean
}>()
const merchant = computed(() => forgeMerchant(props.itemId))
const copyStatus = ref('')
let feedbackTimer: ReturnType<typeof setTimeout> | undefined
onUnmounted(() => clearTimeout(feedbackTimer))

async function copyWaypoint(): Promise<void> {
  const code = merchant.value?.waypoint.chatCode
  if (!code) return
  clearTimeout(feedbackTimer)
  try {
    await navigator.clipboard.writeText(code)
    copyStatus.value = 'Copied'
  } catch {
    copyStatus.value = 'Could not copy'
  }
  feedbackTimer = setTimeout(() => { copyStatus.value = '' }, 2000)
}

function currency(id: number): ForgeCurrency | undefined {
  return props.currencies.find(c => c.currencyId === id)
}
</script>

<template>
  <div class="merchant-details" :class="{ 'merchant-details--compact': compact }">
    <div v-if="purchase && (purchase.currencyCosts.length || purchase.knownCopper !== null)" class="merchant-cost">
      <span v-if="purchase.knownCopper !== null" class="gold-cost">{{ formatCopper(purchase.knownCopper) }}</span>
      <span v-for="cost in purchase.currencyCosts" :key="cost.currencyId" class="currency-cost">
        <span class="numeric">{{ cost.count.toLocaleString() }}</span>
        <ItemIcon :icon-url="currency(cost.currencyId)?.iconUrl ?? null" :item-id="null" :size="20" />
        <a v-if="currency(cost.currencyId)?.wikiUrl" :href="currency(cost.currencyId)?.wikiUrl ?? undefined" target="_blank" rel="noopener noreferrer">{{ cost.name }}</a>
        <span v-else>{{ cost.name ?? `Currency ${cost.currencyId}` }}</span>
      </span>
    </div>
    <template v-if="merchant">
      <div class="merchant-name"><a :href="merchant.wikiUrl" :title="merchant.condition" target="_blank" rel="noopener noreferrer">{{ merchant.name }}</a><span v-if="merchant.condition && !compact" class="merchant-condition"> · {{ merchant.condition }}</span></div>
      <div class="waypoint">
        <a :href="merchant.waypoint.wikiUrl" target="_blank" rel="noopener noreferrer">{{ merchant.waypoint.name }}</a>
        <button type="button" class="chat-code" :aria-label="`Copy ${merchant.waypoint.name} chat code`" title="Copy waypoint chat code" @click="copyWaypoint">{{ merchant.waypoint.chatCode }}</button>
        <span v-if="copyStatus" class="copy-status" role="status">{{ copyStatus }}</span>
      </div>
    </template>
    <span v-else class="merchant-name">Merchant</span>
  </div>
</template>

<style scoped>
.merchant-details { display: grid; gap: var(--space-2); margin-top: var(--space-2); color: var(--color-muted); font-size: var(--text-sm); }
.merchant-cost, .currency-cost, .waypoint { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2); }
.merchant-cost { gap: var(--space-3); }
.merchant-details--compact { display: contents; }
.merchant-details--compact .merchant-cost { grid-column: 3; grid-row: 1; justify-content: flex-end; }
.merchant-details--compact .merchant-name { grid-column: 1; grid-row: 2; }
.merchant-details--compact .waypoint { grid-column: 2 / 4; grid-row: 2; justify-content: flex-end; text-align: right; }
.merchant-details--compact .merchant-cost, .merchant-details--compact .merchant-name, .merchant-details--compact .waypoint { min-width: 0; color: var(--color-muted); font-size: var(--text-sm); }
.gold-cost { color: var(--color-danger); }
.chat-code { padding: 0 var(--space-1); min-height: 0; background: transparent; border: 1px solid transparent; border-radius: var(--radius-control); color: var(--color-secondary); font-family: monospace; font-size: inherit; line-height: inherit; white-space: nowrap; }
.chat-code:hover, .chat-code:focus-visible { border-color: var(--color-border); background: var(--color-raised); }
.copy-status { color: var(--color-muted); }
</style>
