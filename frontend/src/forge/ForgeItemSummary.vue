<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { getJson } from '@/api/http'
import type { ForgeItemSummaryResponse, ForgeRecipeRow } from '@/api/types'
import CoinAmount from '@/items/CoinAmount.vue'
import ItemIcon from '@/items/ItemIcon.vue'
import { itemRarityColor } from '@/items/itemRarity'

const props = defineProps<{ row: ForgeRecipeRow; compact?: boolean }>()
const data = ref<ForgeItemSummaryResponse | null>(null)
const message = ref('')
const retryAvailable = ref(false)
/** Summary quotes are only trusted until a newer catalogue row quote is observed; no timestamps exist, so observation order decides. */
const summaryQuotes = ref<{ buy: number | null; sell: number | null } | null>(null)
let rowQuoteVersion = 0
let generation = 0
let active = true
let timer: ReturnType<typeof setTimeout> | undefined
const MAX_READS = 10
const READ_DELAY_MS = 1500

function stop(): void { clearTimeout(timer); generation++ }
function start(): void {
  stop()
  retryAvailable.value = false
  message.value = 'Loading item details…'
  void load(props.row.outputItemId, generation, 0)
}
async function load(itemId: number, ticket: number, attempt: number): Promise<void> {
  try {
    const requestedVersion = rowQuoteVersion
    const result = await getJson<ForgeItemSummaryResponse>(`/mystic-forge/items/${itemId}/summary`)
    if (!active || ticket !== generation) return
    data.value = result
    // A row refresh observed while this request was in flight is newer than its quotes: keep the row prices.
    summaryQuotes.value = requestedVersion === rowQuoteVersion ? { buy: result.instantBuyCopper, sell: result.instantSellCopper } : null
    if (!result.completing) { message.value = ''; return }
    if (attempt + 1 >= MAX_READS) {
      message.value = 'Some item details are unavailable.'
      retryAvailable.value = true
      return
    }
    timer = setTimeout(() => { void load(itemId, ticket, attempt + 1) }, READ_DELAY_MS)
  } catch {
    if (!active || ticket !== generation) return
    message.value = 'Item details unavailable.'
    retryAvailable.value = true
  }
}
watch(() => props.row.outputItemId, () => { data.value = null; summaryQuotes.value = null; if (active) start() }, { immediate: true })
watch(() => [props.row.sellUnitCopper, props.row.buyUnitCopper], () => { rowQuoteVersion++; summaryQuotes.value = null })
onDeactivated(() => { active = false; stop() })
onActivated(() => { if (!active) { active = true; start() } })
onBeforeUnmount(stop)

function readable(value: string | null | undefined): string {
  return value?.replace(/([a-z])([A-Z])/g, '$1 $2') ?? ''
}
const facts = computed(() => {
  const item = data.value?.item
  const subtype = props.row.category === 'MATERIALS' ? 'Crafting material'
    : readable(item?.subtype ?? props.row.itemSubtype ?? props.row.itemType)
  const result = [{ label: 'Type', value: `${item?.weightClass ? `${item.weightClass} ` : ''}${subtype}` }]
  if (props.row.materialTier) result.push({ label: 'Tier', value: `T${props.row.materialTier}` })
  if (item?.rarity ?? props.row.rarity) result.push({ label: 'Rarity', value: (item?.rarity ?? props.row.rarity)! })
  if (item?.level !== null && item?.level !== undefined) result.push({ label: 'Required level', value: item.level.toLocaleString() })
  if (item?.minPower != null && item.maxPower != null) {
    result.push({ label: 'Weapon strength', value: `${item.minPower.toLocaleString()}–${item.maxPower.toLocaleString()}${item.damageType ? ` · ${item.damageType}` : ''}` })
  } else if (item?.defense != null && item.type === 'Armor') result.push({ label: 'Defense', value: item.defense.toLocaleString() })
  if (item?.prefix) result.push({ label: 'Prefix', value: item.prefix })
  if (item?.binding) result.push({ label: 'Binding', value: item.binding })
  return result
})
const buyPrice = computed(() => summaryQuotes.value?.buy ?? props.row.sellUnitCopper)
const sellPrice = computed(() => summaryQuotes.value?.sell ?? props.row.buyUnitCopper)
</script>

<template>
  <div class="item-summary" :class="{ 'item-summary--compact': compact }" role="group" aria-label="Item details" data-test="forge-item-summary">
    <header class="item-summary__title">
      <ItemIcon :icon-url="row.iconUrl" :item-id="row.outputItemId" :size="40" loading="eager" />
      <h2 :data-rarity="data?.item?.rarity ?? row.rarity" :style="{ color: itemRarityColor(data?.item?.rarity ?? row.rarity) }">{{ row.name ?? `Item ${row.outputItemId}` }}</h2>
      <a v-if="row.wikiUrl" :href="row.wikiUrl" target="_blank" rel="noopener noreferrer">Wiki</a>
    </header>
    <dl class="item-facts">
      <div v-for="fact in facts" :key="fact.label">
        <dt>{{ fact.label }}</dt>
        <dd :data-rarity="fact.label === 'Rarity' ? fact.value : undefined" :style="{ color: fact.label === 'Rarity' ? itemRarityColor(fact.value) : undefined }">{{ fact.value }}</dd>
      </div>
    </dl>
    <dl class="item-prices">
      <div><dt>Instant buy</dt><dd><CoinAmount :copper="buyPrice" /></dd></div>
      <div><dt>Instant sell</dt><dd><CoinAmount :copper="sellPrice" /></dd></div>
    </dl>
    <p v-if="message" class="summary-status" role="status">{{ message }} <button v-if="retryAvailable" type="button" @click="start">Retry</button></p>
  </div>
</template>

<style scoped>
.item-summary { display: grid; grid-template-columns: minmax(0, 1fr) minmax(18rem, 22rem); gap: var(--space-3) var(--space-5); padding: var(--space-4) var(--space-5); border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: var(--color-surface); }
.item-summary__title { grid-column: 1 / -1; display: flex; align-items: center; gap: 0.75rem; }
.item-summary--compact { grid-template-columns: minmax(0, 1fr); padding: var(--space-5); align-content: start; }
.item-summary--compact .item-prices { flex-direction: row; flex-wrap: wrap; gap: var(--space-3) var(--space-5); padding: var(--space-3) 0 0; border-left: 0; border-top: 1px solid var(--color-border); }
.item-summary--compact .item-prices > div { flex: 1 1 12rem; }
.item-summary__title h2 { margin: 0; }
.item-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-3) var(--space-5); margin: 0; }
dt { color: var(--color-muted); font-size: var(--text-sm); }
dd { margin: 2px 0 0; }
.item-prices { display: flex; flex-direction: column; justify-content: center; gap: var(--space-3); margin: 0; padding-left: var(--space-5); border-left: 1px solid var(--color-border); }
.item-prices > div { display: flex; justify-content: space-between; align-items: center; gap: var(--space-4); }
.item-prices dd { margin: 0; }
.summary-status { grid-column: 1 / -1; margin: 0; color: var(--color-muted); font-size: var(--text-sm); }
.summary-status button { padding: 0 var(--space-2); font-size: inherit; min-height: 0; }
</style>
