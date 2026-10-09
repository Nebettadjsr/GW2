<script setup lang="ts">
import { computed } from 'vue'
import type {
  CraftingProfitResolutionResponse,
  CraftingRow,
  EffectiveSettings,
  MissingItem
} from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import CraftingResolution from './CraftingResolution.vue'
import {
  formatCopper,
  formatCount,
  formatSignedCopper,
  moneyTone,
  NO_VALUE
} from './formatCopper'
import { materialLabel, recipeLabel, wikiUrl } from './recipeLabel'
import { formatStackQuantity } from './formatStackQuantity'
import type { SelectionHiddenReason } from './useProfitTableView'
import type { ResolutionPhase } from './useResolutionDetail'

const props = defineProps<{
  row: CraftingRow | null
  hiddenReason: SelectionHiddenReason
  settings: EffectiveSettings | null
  placeholder: string
  resolutionPhase: ResolutionPhase
  resolutionDetail: CraftingProfitResolutionResponse | null
  resolutionFailure: string | null
  resolutionRecipeId: number | null
}>()

const wikiHref = computed(() =>
  props.row === null ? null : wikiUrl(props.row.outputName)
)

const totalsLabel = computed(() => {
  const count = props.row?.craftableCount ?? null

  if (count === null) return 'For every craft the calculation counted'
  return count === 1
    ? 'For the 1 craft counted'
    : `For all ${count} crafts counted`
})

const outputItemCount = computed(() => {
  const row = props.row
  if (row === null || row.craftableCount === null) return null
  return row.craftableCount * row.outputCount
})

const missingForAllCrafts = computed<MissingItem[] | null>(
  () => props.row?.missingToBuy ?? null
)

const outputQuoteUnavailable = computed(() => {
  const quote = props.row?.outputPrice
  return quote === null || quote === undefined || (quote.buyUnitCopper === null && quote.sellUnitCopper === null)
})

/*
 * Name the price basis used for the calculation.
 *
 * EffectiveSettings currently supplies the configured sell-price mode.
 * Keep the fallback deliberately generic in case there is no accepted
 * settings response yet.
 */
const calculationSellPriceLabel = computed(() => {
  const settings = props.settings

  if (settings === null) return '1 item sell price'

  /*
   * Keep this tolerant of the API's actual enum/string naming.
   * If your type uses different values, TypeScript will point directly
   * at this switch and it can be adjusted to the exact contract.
   */
  return settings.listingSell ? '1 item (Listing sell)' : '1 item (Instant sell)'
})

function materialQuoteText(item: MissingItem): string {
  const unit = item.purchaseUnitPriceCopper === null ? 'Unavailable' : formatCopper(item.purchaseUnitPriceCopper)
  const total = item.totalPurchaseCostCopper === null ? 'Unavailable' : formatCopper(item.totalPurchaseCostCopper)
  return `Price / item: ${unit} · Total: ${total}`
}
</script>

<template>
  <section
    class="panel detail"
    aria-labelledby="crafting-detail-heading"
    data-test="selected-detail"
  >
    <h2 id="crafting-detail-heading" class="panel__title">
      Selected result
    </h2>

    <p
      v-if="row === null"
      class="meta"
      data-test="detail-placeholder"
    >
      {{ placeholder }}
    </p>

    <template v-else>
      <!-- Item header -->
      <div class="stack">
        <div class="detail__heading">
          <ItemIcon
            :icon-url="row.iconUrl"
            :item-id="row.outputItemId"
            loading="eager"
            :size="32"
          />

          <h3 class="detail__name" data-test="detail-name">
            {{ recipeLabel(row) }}
          </h3>
        </div>

        <p class="meta" data-test="detail-identity">
          {{ row.disciplines }} · minimum rating {{ row.minRating }}
        </p>

        <p v-if="wikiHref !== null" class="meta">
          <a
            :href="wikiHref"
            target="_blank"
            rel="noopener noreferrer"
            data-test="detail-wiki"
          >
            GW2 Wiki: {{ recipeLabel(row) }}
            <span class="visually-hidden">(opens in a new tab)</span>
          </a>
        </p>

        <p
          v-else
          class="meta"
          data-test="detail-wiki-absent"
        >
          No wiki link: the backend supplied no name for this item, and its ID is
          not a reliable wiki address.
        </p>
      </div>

      <!-- A selected row can remain available while hidden by the current display controls. -->
      <p
        v-if="hiddenReason !== null"
        class="notice notice--info"
        data-test="detail-hidden"
      >
        This recipe is not in the displayed list:

        <template v-if="hiddenReason === 'limited'">
          it is further down the matching results than the display maximum reaches.
          Switch on Show all to list it.
        </template>

        <template v-else>
          the current search or display filters hide its row. Change them to list it
          again.
        </template>
      </p>

      <!-- Calculation -->
      <section
        class="detail__section"
        aria-labelledby="detail-summary-heading"
      >
        <h3 id="detail-summary-heading">
          Calculation
        </h3>

        <dl class="calculation" data-test="detail-calculation">
          <!-- Informational -->
          <dt class="calculation__info">
            Price {{ calculationSellPriceLabel }}
          </dt>
          <dd class="numeric calculation__info">
            {{ formatCopper(row.revenueCopper) }}
          </dd>

          <dt class="calculation__info">
            No. craftable Items
          </dt>
          <dd class="numeric calculation__info">
            {{ formatCount(outputItemCount) }}
          </dd>

          <!-- Revenue -->
          <dt class="calculation__subtotal">
            Total sell value
          </dt>
          <dd class="numeric calculation__subtotal">
            <span
              class="calc-positive"
              data-test="detail-total-sell-value"
            >
              {{ formatCopper(row.totalSellValueCopper) }}
            </span>
          </dd>

          <!-- Costs -->
          <dt>
            Own materials
          </dt>
          <dd class="numeric">
            <span
              v-if="row.totalMatsSellValueCopper !== null"
              class="calc-negative"
              data-test="detail-own-material-cost"
            >
              {{ formatCopper(row.totalMatsSellValueCopper) }}
            </span>

            <span v-else class="meta">
              {{ NO_VALUE }}
            </span>
          </dd>

          <dt>
            Bought materials
          </dt>
          <dd class="numeric">
            <span
              class="calc-negative"
              data-test="detail-buy-cost"
            >
              {{ formatCopper(row.buyCostCopper) }}
            </span>
          </dd>

          <!-- Result -->
          <dt class="calculation__result">
            Profit
            <span
              class="value-note"
              data-test="detail-total-profit-fee-note"
            >
              after 15% TP fees
            </span>
          </dt>

          <dd class="numeric calculation__result">
            <span
              :class="`money money--${moneyTone(row.totalProfitCopper)}`"
              data-test="detail-total-profit"
            >
              {{ formatSignedCopper(row.totalProfitCopper) }}
            </span>
          </dd>
        </dl>

        <!-- TP prices -->
        <div
          class="tp-prices"
          data-test="detail-output-quote"
        >
          <h4 class="tp-prices__heading" data-test="detail-quote-heading">
            Trading Post price / item
          </h4>

          <p
            v-if="outputQuoteUnavailable"
            class="meta"
            data-test="detail-output-unavailable"
          >
            <strong>Not available on TP</strong> · No quote supplied
            <a v-if="wikiHref !== null" :href="wikiHref" target="_blank" rel="noopener noreferrer">GW2 Wiki: {{ recipeLabel(row) }}</a>
          </p>

          <dl v-if="row.outputPrice !== null && (row.outputPrice.buyUnitCopper !== null || row.outputPrice.sellUnitCopper !== null)" class="tp-prices__values">
            <dt>Instant sell</dt>
            <dd class="numeric">
              {{ formatCopper(row.outputPrice.buyUnitCopper) }}
            </dd>

            <dt>Listing sell</dt>
            <dd class="numeric">
              {{ formatCopper(row.outputPrice.sellUnitCopper) }}
            </dd>
          </dl>
        </div>
      </section>

      <!-- Crafting resolution -->
      <section
        class="detail__section"
        aria-labelledby="detail-tree-heading"
      >
        <h3 id="detail-tree-heading">
          Crafting resolution
        </h3>

        <CraftingResolution
          :phase="resolutionPhase"
          :detail="resolutionDetail"
          :failure="resolutionFailure"
          :requested-recipe-id="resolutionRecipeId"
        />
      </section>

      <!-- Shopping list -->
      <section
        class="detail__section"
        aria-labelledby="detail-materials-heading"
      >
        <h3 id="detail-materials-heading">
          Materials still to buy
        </h3>

        <h4 class="detail__basis">
          {{ totalsLabel }}
        </h4>

        <p
          v-if="missingForAllCrafts === null"
          class="meta"
          data-test="missing-all-none"
        >
          Not supplied for this recipe.
        </p>

        <p
          v-else-if="missingForAllCrafts.length === 0"
          class="meta"
          data-test="missing-all-none"
        >
          Nothing needs to be bought.
        </p>

        <ul
          v-else
          class="material-list"
          data-test="missing-all"
        >
          <li
            v-for="item in missingForAllCrafts"
            :key="item.itemId"
            data-test="missing-item"
          >
            <span class="material-name">
              <ItemIcon
                :icon-url="item.iconUrl"
                :item-id="item.itemId"
                loading="lazy"
              />

              {{ materialLabel(item) }}
            </span>

            <span class="material-quantity numeric">
              {{ formatStackQuantity(item.quantity) }}
            </span>

            <span class="meta">
              {{ materialQuoteText(item) }}
            </span>
          </li>
        </ul>
      </section>

    </template>
  </section>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.detail > * + * {
  margin-top: 0;
}

/* Item */

.detail__heading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.detail__name {
  font-size: var(--text-lg);
  overflow-wrap: anywhere;
}

/* Sections */

.detail__section {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.detail__basis {
  color: var(--color-muted);
  font-size: var(--text-sm);
  font-weight: 500;
}

/* Calculation */

.calculation {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-2) var(--space-3);
  margin: 0;
  align-items: baseline;
}

.calculation dt,
.calculation dd {
  margin: 0;
}

.calculation dd {
  white-space: nowrap;
}

/*
 * Price per item and item count are inputs/context rather than
 * profit/cost results, so keep them visually quiet.
 */
.calculation__info {
  color: var(--color-muted);
}

/* First accounting result. */
.calculation__subtotal {
  padding-top: var(--space-2);
  border-top: 1px solid var(--color-border);
}

/* Final accounting result. */
.calculation__result {
  padding-top: var(--space-2);
  border-top: 1px solid var(--color-border);
  font-weight: 600;
}

.value-note {
  display: block;
  color: var(--color-muted);
  font-size: var(--text-sm);
  font-weight: 400;
}

/*
 * Explicit colors for this accounting view.
 * These use the same semantic CSS variables as the application.
 */
.calc-positive {
  color: var(--color-success, #6fdc9a);
}

.calc-negative {
  color: var(--color-danger, #ff7b72);
}

/* TP prices */

.tp-prices {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.tp-prices__heading {
  margin: 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.tp-prices__values {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-2) var(--space-3);
  margin: 0;
}

.tp-prices__values dt,
.tp-prices__values dd {
  margin: 0;
}

.tp-prices__values dd {
  white-space: nowrap;
}

/* Shopping list */

.material-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: var(--text-sm);
}

.material-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0 var(--space-3);
}

.material-name {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
  overflow-wrap: anywhere;
}

.material-quantity {
  white-space: nowrap;
}

.material-list .meta {
  grid-column: 1 / -1;
}
</style>
