<script setup lang="ts">
import { computed } from 'vue'
import type { CraftingDiscoveryResolutionResponse, CraftingRow, MissingItem } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import CraftingResolution from './CraftingResolution.vue'
import { formatCopper, formatSignedCopper, moneyTone, NO_VALUE } from './formatCopper'
import { materialLabel, recipeLabel, wikiUrl } from './recipeLabel'
import { describeRowState } from './rowState'
import type { ResolutionPhase } from './useResolutionDetail'

const props = defineProps<{
  row: CraftingRow | null
  hiddenBySearch: boolean
  placeholder: string
  resolutionPhase: ResolutionPhase
  resolutionDetail: CraftingDiscoveryResolutionResponse | null
  resolutionFailure: string | null
  resolutionRecipeId: number | null
}>()

const state = computed(() => props.row === null ? null : describeRowState(props.row))
const wikiHref = computed(() => props.row === null ? null : wikiUrl(props.row.outputName))
const purchases = computed<MissingItem[] | null>(() => props.row?.missingToBuyOne ?? null)

function unavailable(value: number | null): string {
  return value === null ? NO_VALUE : formatCopper(value)
}
</script>

<template>
  <section class="panel detail" aria-labelledby="discovery-detail-heading" data-test="discovery-detail">
    <h2 id="discovery-detail-heading" class="panel__title">Selected result</h2>

    <p v-if="row === null || state === null" class="meta" data-test="discovery-detail-placeholder">
      {{ placeholder }}
    </p>

    <template v-else>
      <div class="stack">
        <div class="detail__heading">
          <ItemIcon :icon-url="row.iconUrl" :item-id="row.outputItemId" loading="eager" :size="32" />
          <h3 class="detail__name" data-test="discovery-detail-name">{{ recipeLabel(row) }}</h3>
        </div>
        <p class="meta" data-test="discovery-detail-identity">
          {{ row.disciplines }} · recipe level {{ row.minRating }}<template v-if="row.outputCount > 1"> · produces {{ row.outputCount }}</template>
        </p>
        <a v-if="wikiHref !== null" class="meta" :href="wikiHref" target="_blank" rel="noopener noreferrer" data-test="discovery-detail-wiki">
          GW2 Wiki: {{ recipeLabel(row) }}
        </a>
      </div>

      <p v-if="hiddenBySearch" class="notice notice--info" data-test="discovery-detail-hidden">
        This recipe is hidden by the current search.
      </p>

      <div v-if="state.labelAddsMeaning || row.blockedReason !== 'NONE'" class="stack" data-test="discovery-detail-state">
        <span :class="`status status--${state.tone}`" data-test="discovery-detail-status">{{ state.label }}</span>
        <p v-if="row.blockedReason !== 'NONE'" data-test="discovery-detail-status-explanation">{{ state.explanation }}</p>
      </div>

      <section class="detail__section" aria-labelledby="discovery-calculation-heading">
        <h3 id="discovery-calculation-heading">Calculation</h3>
        <dl class="detail-values" data-test="discovery-detail-calculation">
          <dt>Output sell value</dt>
          <dd class="numeric" data-test="discovery-detail-output-revenue">{{ unavailable(row.totalSellValueCopper) }}</dd>
          <dt>Own materials</dt>
          <dd class="numeric">{{ unavailable(row.totalMatsSellValueCopper) }}</dd>
          <dt>Bought materials</dt>
          <dd class="numeric">{{ unavailable(row.buyCostCopper) }}</dd>
          <dt>Profit <span class="value-note">after 15% TP fees</span></dt>
          <dd class="numeric">
            <span :class="`money money--${moneyTone(row.totalProfitCopper)}`" data-test="discovery-detail-profit">
              {{ formatSignedCopper(row.totalProfitCopper) }}
            </span>
          </dd>
        </dl>
      </section>

      <section class="detail__section" aria-labelledby="discovery-price-heading">
        <h3 id="discovery-price-heading">Trading Post price / item</h3>
        <p v-if="row.outputPrice === null" class="meta">{{ NO_VALUE }}</p>
        <dl v-else class="detail-values" data-test="discovery-detail-output-quote">
          <dt>Instant sell</dt><dd class="numeric">{{ unavailable(row.outputPrice.buyUnitCopper) }}</dd>
          <dt>Listing sell</dt><dd class="numeric">{{ unavailable(row.outputPrice.sellUnitCopper) }}</dd>
        </dl>
      </section>

      <section class="detail__section" aria-labelledby="discovery-tree-heading">
        <h3 id="discovery-tree-heading">Crafting resolution</h3>
        <CraftingResolution :phase="resolutionPhase" :detail="resolutionDetail" :failure="resolutionFailure"
          :requested-recipe-id="resolutionRecipeId" selected-result-mode />
      </section>

      <section class="detail__section" aria-labelledby="discovery-materials-heading">
        <h3 id="discovery-materials-heading">Materials still to buy</h3>
        <p v-if="purchases === null" class="meta">{{ NO_VALUE }}</p>
        <p v-else-if="purchases.length === 0" class="meta">Nothing needs to be bought.</p>
        <ul v-else class="material-list" data-test="discovery-missing-items">
          <li v-for="item in purchases" :key="item.itemId" data-test="discovery-missing-item">
            <span class="material-name"><ItemIcon :icon-url="item.iconUrl" :item-id="item.itemId" loading="lazy" />{{ materialLabel(item) }}</span>
            <span class="material-quantity numeric">×{{ item.quantity }}</span>
            <span class="material-price">Price / item: {{ unavailable(item.purchaseUnitPriceCopper) }}</span>
            <span class="material-total">Total: {{ unavailable(item.totalPurchaseCostCopper) }}</span>
          </li>
        </ul>
      </section>

      <details class="diagnostics" data-test="discovery-detail-diagnostics">
        <summary>Technical details</summary>
        <dl class="diagnostics__body">
          <dt>Recipe id</dt><dd>{{ row.recipeId }}</dd>
          <dt>Output item id</dt><dd>{{ row.outputItemId }}</dd>
          <dt>Result supplied</dt><dd>{{ row.resultAvailable ? 'yes' : 'no' }}</dd>
          <dt>Reported state code</dt><dd data-test="discovery-detail-state-code">{{ state.code ?? NO_VALUE }}</dd>
          <template v-if="resolutionDetail !== null">
            <dt>Resolution consistency</dt><dd>{{ resolutionDetail.consistency }}</dd>
            <dt>Resolution tree status</dt><dd>{{ resolutionDetail.treeStatus }}</dd>
            <dt>Resolution calculated at</dt><dd>{{ resolutionDetail.calculatedAt }}</dd>
          </template>
        </dl>
      </details>
    </template>
  </section>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; gap: var(--space-4); }
.detail > * + * { margin-top: 0; }
.detail__heading { display: flex; align-items: center; gap: var(--space-2); }
.detail__name { font-size: var(--text-lg); overflow-wrap: anywhere; }
.detail__section { display: flex; flex-direction: column; gap: var(--space-2); padding-top: var(--space-3); border-top: 1px solid var(--color-border); }
.value-note { display: block; color: var(--color-muted); font-size: var(--text-sm); font-weight: 400; }
.material-list { display: flex; flex-direction: column; gap: var(--space-2); margin: 0; padding: 0; list-style: none; font-size: var(--text-sm); }
.material-list li { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: var(--space-1) var(--space-3); }
.material-name { display: flex; align-items: center; gap: var(--space-2); overflow-wrap: anywhere; }
.material-price, .material-total { grid-column: 1 / -1; color: var(--color-muted); }
</style>
