<script setup lang="ts">
import { computed } from 'vue'
import type {
  CraftingProfitResolutionResponse,
  CraftingRow,
  EffectiveSettings,
  MissingItem
} from '@/api/types'
import CraftingResolution from './CraftingResolution.vue'
import { formatCopper, formatCount, formatSignedCopper, moneyTone, NO_VALUE } from './formatCopper'
import { materialLabel, recipeLabel, wikiUrl } from './recipeLabel'
import { describeRowState } from './rowState'
import type { SelectionHiddenReason } from './useProfitTableView'
import type { ResolutionPhase } from './useProfitResolution'

/**
 * The selected recipe's details, next to (or below) the comparison table rather than inside it
 * (`FRONTEND_UX_GUIDELINES.md` 4, `DOMAIN_SPEC.md` 2.1.1).
 *
 * It renders **only** what the backend supplied, from two separate answers that are kept separate on
 * screen. `row` is the selected recipe as the *results table's* calculation reported it; the
 * resolution region below holds the recipe's own freshly calculated detail, which is a different
 * calculation and may legitimately disagree. Neither is written over the other, and neither is
 * described as the other's explanation (`TARGET_ARCHITECTURE.md` 13.2/13.4).
 *
 * Nothing is added up, no procurement quantity is derived, and each money value is shown under the
 * basis the contract gives it — `buyCostCopper`, `totalSellValueCopper` and `totalProfitCopper` are
 * totals for every craft counted, while revenue, material sell value and profit are per single
 * craft (`web.dto.CraftingRowDto`).
 *
 * This region is also where the blocking reasons DOMAIN_SPEC 2.1.1 removed from the comparison table
 * are stated: the row's own state sentence explains the restriction in words, and the supplied buy
 * cost and the backend's echoed maximum-buy setting are shown beside it. No missing acquisition
 * amount is invented, no tree cost is summed in, and nothing about non-Trading-Post eligibility is
 * read out of a reason code.
 *
 * `row` is read from the *current* result set. When a replacement calculation no longer contains
 * that recipe the screen passes null, so an earlier answer's numbers can never appear underneath a
 * newer result.
 */
const props = defineProps<{
  row: CraftingRow | null
  /**
   * Why the selected recipe is not among the displayed rows, or null when it is. The detail stays
   * on screen either way — the recipe is still in the loaded result set — and says which display
   * control is holding its row back rather than letting the detail look unrelated to the list.
   */
  hiddenReason: SelectionHiddenReason
  /**
   * The settings the backend echoed for the result set this row came from, or null while there is
   * none. Read only to name the configured maximum buy beside a budget restriction; no default of
   * this client's own is ever substituted for it.
   */
  settings: EffectiveSettings | null
  /** Why nothing is selected, in the words that fit the results region's own state. */
  placeholder: string
  /** The resolution request's situation, and its answer once one has been accepted. */
  resolutionPhase: ResolutionPhase
  resolutionDetail: CraftingProfitResolutionResponse | null
  resolutionFailure: string | null
  resolutionRecipeId: number | null
}>()

const state = computed(() => (props.row === null ? null : describeRowState(props.row)))

/**
 * The configured maximum buy, worded only for the one state it explains. A budget restriction is the
 * single reason the setting makes the difference between "blocked" and "not blocked", and saying so
 * changes nothing about the restriction itself — it is the backend's own echoed number.
 */
const budgetContext = computed<string | null>(() => {
  if (state.value?.code !== 'INSUFFICIENT_BUDGET') return null
  const settings = props.settings
  if (settings === null) return null
  return `The calculation's maximum buy setting is ${formatCopper(settings.maxBuyCopper)}.`
})

/** Omitted entirely when the backend supplied no name — an ID makes no reliable wiki target. */
const wikiHref = computed(() => (props.row === null ? null : wikiUrl(props.row.outputName)))

/** Names the basis of the totals group honestly when the backend supplied no craftable count. */
const totalsLabel = computed(() => {
  const count = props.row?.craftableCount ?? null
  if (count === null) return 'For every craft the calculation counted'
  return count === 1 ? 'For the 1 craft counted' : `For all ${count} crafts counted`
})

/**
 * A list the backend did not supply and an empty list are different answers, and a response that
 * omits the field altogether is a third; all three stay distinct from "nothing to buy".
 */
const missingForAllCrafts = computed<MissingItem[] | null>(() => props.row?.missingToBuy ?? null)
const missingForOneCraft = computed<MissingItem[] | null>(() => props.row?.missingToBuyOne ?? null)

/**
 * The quote is per single item, which is not the same thing as per craft: a recipe whose output
 * count is above one produces several of them at once. Saying so is the point of the heading.
 */
const quoteHeading = computed(() => {
  if (props.row === null) return ''
  return `Trading Post price for one ${recipeLabel(props.row)}`
})

function materialQuoteText(item: MissingItem): string {
  if (item.price === null) return 'No price supplied'
  return `Instant buy ${formatCopper(item.price.buyUnitCopper)} · Instant sell ${formatCopper(item.price.sellUnitCopper)}`
}
</script>

<template>
  <section class="panel detail" aria-labelledby="crafting-detail-heading" data-test="selected-detail">
    <h2 id="crafting-detail-heading" class="panel__title">Selected result</h2>

    <p v-if="row === null || state === null" class="meta" data-test="detail-placeholder">
      {{ placeholder }}
    </p>

    <template v-else>
      <div class="stack">
        <h3 class="detail__name" data-test="detail-name">{{ recipeLabel(row) }}</h3>
        <p class="meta" data-test="detail-identity">
          {{ row.disciplines }} · minimum rating {{ row.minRating }}
        </p>
        <p v-if="wikiHref !== null" class="meta">
          <a :href="wikiHref" target="_blank" rel="noopener noreferrer" data-test="detail-wiki">
            Look up {{ recipeLabel(row) }} on the Guild Wars 2 Wiki
            <span class="visually-hidden">(opens in a new tab)</span>
          </a>
        </p>
        <p v-else class="meta" data-test="detail-wiki-absent">
          No wiki link: the backend supplied no name for this item, and its ID is not a reliable
          wiki address.
        </p>
      </div>

      <p v-if="hiddenReason !== null" class="notice notice--info" data-test="detail-hidden">
        This recipe is not in the displayed list:
        <template v-if="hiddenReason === 'limited'">
          it is further down the matching results than the display maximum reaches. Switch on Show all
          to list it.
        </template>
        <template v-else>
          the current search or display filters hide its row. Change them to list it again.
        </template>
        Its details stay available until another recipe is chosen or a new calculation replaces it.
      </p>

      <div class="stack">
        <span :class="`status status--${state.tone}`" data-test="detail-status">{{ state.label }}</span>
        <p data-test="detail-status-explanation">{{ state.explanation }}</p>
        <p v-if="budgetContext !== null" class="meta" data-test="detail-budget-context">
          {{ budgetContext }}
        </p>
      </div>

      <section class="detail__section" aria-labelledby="detail-summary-heading">
        <h3 id="detail-summary-heading">Table calculation</h3>

        <h4 class="detail__basis">For one craft</h4>
        <dl class="detail-values" data-test="detail-per-craft">
          <dt>Output quantity</dt>
          <dd class="numeric">{{ row.outputCount }}</dd>

          <dt>Output revenue</dt>
          <dd class="numeric">{{ formatCopper(row.revenueCopper) }}</dd>

          <dt>Own materials given up</dt>
          <dd class="numeric">{{ formatCopper(row.matsSellValueCopper) }}</dd>

          <dt>Profit</dt>
          <dd class="numeric">
            <span :class="`money money--${moneyTone(row.profitCopper)}`" data-test="detail-profit-per-craft">
              {{ formatSignedCopper(row.profitCopper) }}
            </span>
          </dd>
        </dl>

        <h4 class="detail__basis">{{ totalsLabel }}</h4>
        <dl class="detail-values" data-test="detail-totals">
          <dt>Crafts possible</dt>
          <dd class="numeric">{{ formatCount(row.craftableCount) }}</dd>

          <dt class="detail-values__lead">Cost of materials to buy</dt>
          <dd class="numeric detail-values__lead">
            <span class="money money--cost" data-test="detail-buy-cost">
              {{ formatCopper(row.buyCostCopper) }}
            </span>
          </dd>

          <dt>Total sell value</dt>
          <dd class="numeric">
            <span class="money" data-test="detail-total-sell-value">
              {{ formatCopper(row.totalSellValueCopper) }}
            </span>
          </dd>

          <dt>Total profit</dt>
          <dd class="numeric">
            <span :class="`money money--${moneyTone(row.totalProfitCopper)}`" data-test="detail-total-profit">
              {{ formatSignedCopper(row.totalProfitCopper) }}
            </span>
          </dd>
        </dl>

        <h4 class="detail__basis" data-test="detail-quote-heading">{{ quoteHeading }}</h4>
        <p v-if="row.outputPrice === null" class="meta" data-test="detail-output-quote">
          No quote supplied
        </p>
        <dl v-else class="detail-values" data-test="detail-output-quote">
          <dt>Instant buy, one item</dt>
          <dd class="numeric">{{ formatCopper(row.outputPrice.buyUnitCopper) }}</dd>

          <dt>Instant sell, one item</dt>
          <dd class="numeric">{{ formatCopper(row.outputPrice.sellUnitCopper) }}</dd>
        </dl>
        <p class="meta" data-test="detail-quote-basis">
          Each is the price of a single item. One craft of this recipe produces
          {{ row.outputCount }}.
        </p>
      </section>

      <section class="detail__section" aria-labelledby="detail-tree-heading">
        <h3 id="detail-tree-heading">Crafting resolution</h3>
        <CraftingResolution
          :phase="resolutionPhase"
          :detail="resolutionDetail"
          :failure="resolutionFailure"
          :requested-recipe-id="resolutionRecipeId"
        />
      </section>

      <section class="detail__section" aria-labelledby="detail-materials-heading">
        <h3 id="detail-materials-heading">Materials still to buy</h3>
        <p class="meta">
          Each line is a quantity the table's calculation reported; no shopping total is worked out
          here.
        </p>

        <h4 class="detail__basis">{{ totalsLabel }}</h4>
        <p v-if="missingForAllCrafts === null" class="meta" data-test="missing-all-none">
          Not supplied for this recipe.
        </p>
        <p v-else-if="missingForAllCrafts.length === 0" class="meta" data-test="missing-all-none">
          Nothing needs to be bought.
        </p>
        <ul v-else class="material-list" data-test="missing-all">
          <li v-for="item in missingForAllCrafts" :key="item.itemId" data-test="missing-item">
            <span class="material-name">{{ materialLabel(item) }}</span>
            <span class="material-quantity numeric">×{{ item.quantity }}</span>
            <span class="meta">{{ materialQuoteText(item) }}</span>
          </li>
        </ul>

        <h4 class="detail__basis">For one further craft</h4>
        <p v-if="missingForOneCraft === null" class="meta" data-test="missing-one-none">
          Not supplied for this recipe.
        </p>
        <p v-else-if="missingForOneCraft.length === 0" class="meta" data-test="missing-one-none">
          Nothing needs to be bought.
        </p>
        <ul v-else class="material-list" data-test="missing-one">
          <li v-for="item in missingForOneCraft" :key="item.itemId" data-test="missing-one-item">
            <span class="material-name">{{ materialLabel(item) }}</span>
            <span class="material-quantity numeric">×{{ item.quantity }}</span>
            <span class="meta">{{ materialQuoteText(item) }}</span>
          </li>
        </ul>
      </section>

      <details class="diagnostics" data-test="detail-diagnostics">
        <summary>Technical details</summary>
        <dl class="diagnostics__body">
          <dt>Recipe id</dt>
          <dd>{{ row.recipeId }}</dd>
          <dt>Output item id</dt>
          <dd>{{ row.outputItemId }}</dd>
          <dt>Result supplied</dt>
          <dd>{{ row.resultAvailable ? 'yes' : 'no' }}</dd>
          <dt>Reported state code</dt>
          <dd data-test="detail-state-code">{{ state.code ?? NO_VALUE }}</dd>

          <template v-if="resolutionDetail !== null">
            <dt>Resolution consistency</dt>
            <dd data-test="detail-consistency">{{ resolutionDetail.consistency }}</dd>
            <dt>Resolution basis</dt>
            <dd data-test="detail-tree-basis">{{ resolutionDetail.treeBasis }}</dd>
            <dt>Resolution tree status</dt>
            <dd data-test="detail-tree-status">{{ resolutionDetail.treeStatus }}</dd>
            <dt>Resolution calculated at</dt>
            <dd data-test="detail-calculated-at">{{ resolutionDetail.calculatedAt }}</dd>
          </template>
        </dl>
      </details>
    </template>
  </section>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* `.panel`'s own top margin between children would fight the flex gap above. */
.detail > * + * {
  margin-top: 0;
}

.detail__name {
  font-size: var(--text-lg);
  overflow-wrap: anywhere;
}

.detail__section {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

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
  grid-template-columns: 1fr auto;
  gap: 0 var(--space-3);
}

.material-name {
  overflow-wrap: anywhere;
}

.material-list .meta {
  grid-column: 1 / -1;
}
</style>
