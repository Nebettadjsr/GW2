<script setup lang="ts">
import { computed } from 'vue'
import type {
  CraftingDiscoveryResolutionResponse,
  CraftingRow,
  EffectiveDiscoverySettings,
  MissingItem
} from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import CraftingResolution from './CraftingResolution.vue'
import { formatCopper, formatCount, formatSignedCopper, moneyTone, NO_VALUE } from './formatCopper'
import { materialLabel, recipeLabel, wikiUrl } from './recipeLabel'
import { describeRowState } from './rowState'
import type { ResolutionPhase } from './useResolutionDetail'

/**
 * The selected discovery candidate's details, next to (or below) the comparison list rather than
 * inside it (`FRONTEND_UX_GUIDELINES.md` 4, `DOMAIN_SPEC.md` 2.2.2).
 *
 * It renders **only** what the backend supplied, from two separate answers that are kept separate on
 * screen. `row` is the recipe as the *Discovery list's* calculation reported it; the resolution region
 * holds the recipe's own freshly calculated detail, which is a different calculation and may
 * legitimately disagree. Neither is written over the other, and neither is described as the other's
 * explanation (`TARGET_ARCHITECTURE.md` 13.2/13.4).
 *
 * Nothing is added up, no procurement quantity is derived, and each money value is shown under the
 * basis the contract gives it — `buyCostCopper`, `totalSellValueCopper` and `totalProfitCopper` are
 * totals for every craft counted, while revenue, material sell value and profit are per single craft
 * (`web.dto.CraftingRowDto`). `DOMAIN_SPEC.md` 36's two kinds of material requirement stay apart: the
 * owned materials given up are an opportunity cost, and the cost of materials to buy is the additional
 * cash the settings require.
 *
 * A candidate whose immediate profit is zero or negative is a normal candidate here
 * (`DOMAIN_SPEC.md` 37): its loss is shown with its sign and nothing about it is worded as an error or
 * as a reason the discovery is invalid.
 */
const props = defineProps<{
  row: CraftingRow | null
  /**
   * True when the recipe is in the loaded result set but the search is holding its row back. The
   * detail stays on screen and says so, rather than looking unrelated to the list.
   */
  hiddenBySearch: boolean
  /**
   * The settings the backend echoed for the result set this row came from, or null while there is
   * none. Read only to name the configured maximum buy beside a budget restriction; no default of
   * this client's own is ever substituted for it.
   */
  settings: EffectiveDiscoverySettings | null
  /** Why nothing is selected, in the words that fit the results region's own state. */
  placeholder: string
  /** The resolution request's situation, and its answer once one has been accepted. */
  resolutionPhase: ResolutionPhase
  resolutionDetail: CraftingDiscoveryResolutionResponse | null
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

function materialQuoteText(item: MissingItem): string {
  if (item.price === null) return 'No price supplied'
  return `Instant buy ${formatCopper(item.price.buyUnitCopper)} · Instant sell ${formatCopper(item.price.sellUnitCopper)}`
}
</script>

<template>
  <section class="panel detail" aria-labelledby="discovery-detail-heading" data-test="discovery-detail">
    <h2 id="discovery-detail-heading" class="panel__title">Selected recipe</h2>

    <p v-if="row === null || state === null" class="meta" data-test="discovery-detail-placeholder">
      {{ placeholder }}
    </p>

    <template v-else>
      <div class="stack">
        <!--
          The output item's own icon, beside the name rather than inside the heading, so the heading's
          text stays the recipe label and nothing announces the item twice.
        -->
        <div class="detail__heading">
          <ItemIcon :icon-url="row.iconUrl" :item-id="row.outputItemId" loading="eager" :size="32" />
          <h3 class="detail__name" data-test="discovery-detail-name">{{ recipeLabel(row) }}</h3>
        </div>
        <p class="meta" data-test="discovery-detail-identity">
          {{ row.disciplines }} · recipe level {{ row.minRating }} · produces
          {{ row.outputCount }} per craft
        </p>
        <p v-if="wikiHref !== null" class="meta">
          <a :href="wikiHref" target="_blank" rel="noopener noreferrer" data-test="discovery-detail-wiki">
            Look up {{ recipeLabel(row) }} on the Guild Wars 2 Wiki
            <span class="visually-hidden">(opens in a new tab)</span>
          </a>
        </p>
        <p v-else class="meta" data-test="discovery-detail-wiki-absent">
          No wiki link: the backend supplied no name for this item, and its ID is not a reliable wiki
          address.
        </p>
      </div>

      <p v-if="hiddenBySearch" class="notice notice--info" data-test="discovery-detail-hidden">
        This recipe is not in the displayed list: the current search hides its row. Clear or change the
        search to list it again.
      </p>

      <div class="stack">
        <span :class="`status status--${state.tone}`" data-test="discovery-detail-status">
          {{ state.label }}
        </span>
        <p data-test="discovery-detail-status-explanation">{{ state.explanation }}</p>
        <p v-if="budgetContext !== null" class="meta" data-test="discovery-detail-budget-context">
          {{ budgetContext }}
        </p>
      </div>

      <section class="detail__section" aria-labelledby="discovery-summary-heading">
        <h3 id="discovery-summary-heading">List calculation</h3>

        <h4 class="detail__basis">For one craft</h4>
        <dl class="detail-values" data-test="discovery-detail-per-craft">
          <dt>Output quantity</dt>
          <dd class="numeric">{{ row.outputCount }}</dd>

          <dt>Output revenue</dt>
          <dd class="numeric">{{ formatCopper(row.revenueCopper) }}</dd>

          <dt>Own materials given up</dt>
          <dd class="numeric">{{ formatCopper(row.matsSellValueCopper) }}</dd>

          <!--
            The same note Crafting Profit's detail carries (DOMAIN_SPEC 2.1.1 / 25): the backend's
            profit already has the 15% Trading Post fee deducted. Output revenue keeps no note
            because it is gross, and this page still calculates no fee of its own.
          -->
          <dt>
            Profit
            <span class="value-note" data-test="discovery-detail-profit-fee-note">
              after 15% TP fees
            </span>
          </dt>
          <dd class="numeric">
            <span
              :class="`money money--${moneyTone(row.profitCopper)}`"
              data-test="discovery-detail-profit-per-craft"
            >
              {{ formatSignedCopper(row.profitCopper) }}
            </span>
          </dd>
        </dl>

        <h4 class="detail__basis">{{ totalsLabel }}</h4>
        <dl class="detail-values" data-test="discovery-detail-totals">
          <dt>Crafts possible</dt>
          <dd class="numeric">{{ formatCount(row.craftableCount) }}</dd>

          <dt class="detail-values__lead">Cost of materials to buy</dt>
          <dd class="numeric detail-values__lead">
            <span class="money money--cost" data-test="discovery-detail-buy-cost">
              {{ formatCopper(row.buyCostCopper) }}
            </span>
          </dd>

          <dt>Output sell value</dt>
          <dd class="numeric">
            <span class="money" data-test="discovery-detail-sell-value">
              {{ formatCopper(row.totalSellValueCopper) }}
            </span>
          </dd>

          <dt>
            Total profit
            <span class="value-note" data-test="discovery-detail-total-profit-fee-note">
              after 15% TP fees
            </span>
          </dt>
          <dd class="numeric">
            <span
              :class="`money money--${moneyTone(row.totalProfitCopper)}`"
              data-test="discovery-detail-total-profit"
            >
              {{ formatSignedCopper(row.totalProfitCopper) }}
            </span>
          </dd>
        </dl>

        <!--
          DOMAIN_SPEC 25: displayed prices and the output sell value stay gross, and the 15% selling
          fee belongs to the domain's profit rule. The backend applies it; this page never deducts
          one, which is what the note says. It is not a second fee model.
        -->
        <p class="meta" data-test="discovery-fee-note">
          Profit is the backend's own figure, with the domain's 15% Trading Post selling fee already
          deducted; this page applies no fee of its own. The prices and output sell value here stay
          gross.
        </p>

        <p class="meta" data-test="discovery-utility-note">
          A discovery also unlocks the recipe and gives crafting progress, so a profit of zero or less
          does not make it a worse candidate — it is only what selling the output would return today.
        </p>

        <!--
          The quote is per single item, which is not per craft: the output quantity above says how many
          one craft produces, so the basis needs the label and not a paragraph.
        -->
        <h4 class="detail__basis" data-test="discovery-detail-quote-heading">
          Trading Post price / item
        </h4>
        <p v-if="row.outputPrice === null" class="meta" data-test="discovery-detail-output-quote">
          No quote supplied
        </p>
        <dl v-else class="detail-values" data-test="discovery-detail-output-quote">
          <dt>Instant buy</dt>
          <dd class="numeric">{{ formatCopper(row.outputPrice.buyUnitCopper) }}</dd>

          <dt>Instant sell</dt>
          <dd class="numeric">{{ formatCopper(row.outputPrice.sellUnitCopper) }}</dd>
        </dl>
      </section>

      <section class="detail__section" aria-labelledby="discovery-tree-heading">
        <h3 id="discovery-tree-heading">Crafting resolution</h3>
        <CraftingResolution
          :phase="resolutionPhase"
          :detail="resolutionDetail"
          :failure="resolutionFailure"
          :requested-recipe-id="resolutionRecipeId"
        />
      </section>

      <section class="detail__section" aria-labelledby="discovery-materials-heading">
        <h3 id="discovery-materials-heading">Materials still to buy</h3>

        <!-- Each line is a supplied quantity under its own basis heading; no total is produced. -->
        <h4 class="detail__basis">{{ totalsLabel }}</h4>
        <p v-if="missingForAllCrafts === null" class="meta" data-test="discovery-missing-all-none">
          Not supplied for this recipe.
        </p>
        <p v-else-if="missingForAllCrafts.length === 0" class="meta" data-test="discovery-missing-all-none">
          Nothing needs to be bought.
        </p>
        <ul v-else class="material-list" data-test="discovery-missing-all">
          <li v-for="item in missingForAllCrafts" :key="item.itemId" data-test="discovery-missing-item">
            <span class="material-name">
              <ItemIcon :icon-url="item.iconUrl" :item-id="item.itemId" loading="lazy" />
              {{ materialLabel(item) }}
            </span>
            <span class="material-quantity numeric">×{{ item.quantity }}</span>
            <span class="meta">{{ materialQuoteText(item) }}</span>
          </li>
        </ul>

        <h4 class="detail__basis">For one further craft</h4>
        <p v-if="missingForOneCraft === null" class="meta" data-test="discovery-missing-one-none">
          Not supplied for this recipe.
        </p>
        <p v-else-if="missingForOneCraft.length === 0" class="meta" data-test="discovery-missing-one-none">
          Nothing needs to be bought.
        </p>
        <ul v-else class="material-list" data-test="discovery-missing-one">
          <li
            v-for="item in missingForOneCraft"
            :key="item.itemId"
            data-test="discovery-missing-one-item"
          >
            <span class="material-name">
              <ItemIcon :icon-url="item.iconUrl" :item-id="item.itemId" loading="lazy" />
              {{ materialLabel(item) }}
            </span>
            <span class="material-quantity numeric">×{{ item.quantity }}</span>
            <span class="meta">{{ materialQuoteText(item) }}</span>
          </li>
        </ul>
      </section>

      <details class="diagnostics" data-test="discovery-detail-diagnostics">
        <summary>Technical details</summary>
        <dl class="diagnostics__body">
          <dt>Recipe id</dt>
          <dd>{{ row.recipeId }}</dd>
          <dt>Output item id</dt>
          <dd>{{ row.outputItemId }}</dd>
          <dt>Result supplied</dt>
          <dd>{{ row.resultAvailable ? 'yes' : 'no' }}</dd>
          <dt>Reported state code</dt>
          <dd data-test="discovery-detail-state-code">{{ state.code ?? NO_VALUE }}</dd>

          <template v-if="resolutionDetail !== null">
            <dt>Resolution consistency</dt>
            <dd data-test="discovery-detail-consistency">{{ resolutionDetail.consistency }}</dd>
            <dt>Resolution basis</dt>
            <dd data-test="discovery-detail-tree-basis">{{ resolutionDetail.treeBasis }}</dd>
            <dt>Resolution tree status</dt>
            <dd data-test="discovery-detail-tree-status">{{ resolutionDetail.treeStatus }}</dd>
            <dt>Resolution calculated at</dt>
            <dd data-test="discovery-detail-calculated-at">{{ resolutionDetail.calculatedAt }}</dd>
            <dt>Resolution inventory character</dt>
            <dd data-test="discovery-detail-resolution-inventory">
              {{ resolutionDetail.calculation.inventoryCharacterName ?? 'none (all owned materials)' }}
            </dd>
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

/* The icon holds its own reserved box beside the name; the name keeps the rest of the line. */
.detail__heading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
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

/* As in `SelectedResultDetail`: the basis under the label, quieter than it and never the only cue. */
.value-note {
  display: block;
  color: var(--color-muted);
  font-size: var(--text-sm);
  font-weight: 400;
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
  display: flex;
  align-items: center;
  gap: var(--space-2);
  overflow-wrap: anywhere;
}

.material-list .meta {
  grid-column: 1 / -1;
}
</style>
