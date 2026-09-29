<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ectoApi, type EctoApi } from '@/api/ectoApi'
import type { EctoSalvageScenario } from '@/api/types'
import { formatCopper, formatSignedCopper, moneyTone } from '@/crafting/formatCopper'
import ItemIcon from '@/items/ItemIcon.vue'
import PageHeader from '@/shell/PageHeader.vue'
import { useEctoSalvage } from './useEctoSalvage'

/**
 * The Ectoplasm Salvage screen: the four Ecto-buy/Dust-sell scenarios exactly as
 * `GET /api/ecto/salvage` calculated them (`CURRENT_ARCHITECTURE.md` 5.15, `DOMAIN_SPEC.md` 2.3,
 * 45–47).
 *
 * Every number on this page is a backend field rendered through the shared money formatter. Nothing
 * is recomputed here: no fee is applied, no expected yield is scaled, and profit, net cost and the
 * cost of 1000 Luck are displayed as supplied rather than related to the quotes beside them. That is
 * the whole point of the route — a second economic implementation in the browser is exactly what it
 * exists to prevent.
 *
 * Gross and fee-inclusive values are labelled apart wherever they sit together (`DOMAIN_SPEC.md` 25):
 * the Trading Post quotes and the expected recovered Dust value are gross, and the economic results
 * already have the selling fee in them, deducted once, on the expected gross recovered Dust value
 * (`DOMAIN_SPEC.md` 46). Buying and selling modes are always written out — instant
 * buy versus buy order, instant sell versus listing sell — because "buy price" and "sell price" are
 * ambiguous without whose perspective is meant (`DOMAIN_SPEC.md` 20).
 *
 * The `api` prop exists so a test can supply controlled responses; the browser always gets the real
 * backend client.
 */
const props = withDefaults(defineProps<{ api?: EctoApi }>(), { api: () => ectoApi })

const salvage = useEctoSalvage(() => props.api.loadSalvage())

onMounted(() => {
  void salvage.load()
})

/** Only ever true for a completed calculation the Trading Post had no usable quotes for. */
const hasNoResult = computed(
  () => salvage.data.value !== null && !salvage.data.value.resultAvailable
)

interface ScenarioRow {
  key: string
  /** How the Ectoplasm is bought, in the spec's own terms. */
  acquisition: string
  /** How the recovered Dust is sold. */
  sale: string
  values: EctoSalvageScenario
}

/**
 * The four scenarios in the fixed order the backend names them. A scenario the backend did not
 * supply is not listed at all — an absent result is never filled in with zeros.
 */
const scenarios = computed<ScenarioRow[]>(() => {
  const result = salvage.data.value
  if (result === null) return []

  const named: { key: string; acquisition: string; sale: string; values: EctoSalvageScenario | null }[] = [
    {
      key: 'instant-buy-instant-sell',
      acquisition: 'Instant buy',
      sale: 'Instant sell',
      values: result.instantBuyInstantSell
    },
    {
      key: 'instant-buy-listing-sell',
      acquisition: 'Instant buy',
      sale: 'Listing sell',
      values: result.instantBuyListingSell
    },
    {
      key: 'buy-order-instant-sell',
      acquisition: 'Buy order',
      sale: 'Instant sell',
      values: result.listingBuyInstantSell
    },
    {
      key: 'buy-order-listing-sell',
      acquisition: 'Buy order',
      sale: 'Listing sell',
      values: result.listingBuyListingSell
    }
  ]

  return named.filter((scenario): scenario is ScenarioRow => scenario.values !== null)
})

/**
 * The quotes behind the scenarios, read out of the scenarios that used them. Each value is one
 * backend field: the Ecto instant-buy cost is the instant-buy scenarios' own acquisition cost, and
 * the Dust instant-sell quote is the instant-sell scenarios' own quote. Nothing is averaged,
 * reconciled or computed across scenarios. All four are gross — this panel shows market prices, and
 * no fee-inclusive figure belongs in it.
 */
const quotes = computed(() => {
  const result = salvage.data.value
  const instantSell = result?.instantBuyInstantSell ?? null
  const listingSell = result?.instantBuyListingSell ?? null
  const buyOrder = result?.listingBuyInstantSell ?? null
  if (result === null || instantSell === null || listingSell === null || buyOrder === null) {
    return null
  }

  return {
    ectoInstantBuy: instantSell.ectoAcquisitionCostCopper,
    ectoBuyOrder: buyOrder.ectoAcquisitionCostCopper,
    dustInstantSellGross: instantSell.dustGrossUnitPriceCopper,
    dustListingSellGross: listingSell.dustGrossUnitPriceCopper
  }
})

/**
 * The written outcome beside the profit figure, so gain and loss are distinguishable without color
 * (`FRONTEND_UX_GUIDELINES.md` 5). A supplied zero is break-even, not an absent value.
 */
function outcomeOf(profitCopper: number): string {
  if (profitCopper > 0) return 'gain'
  return profitCopper < 0 ? 'loss' : 'break-even'
}

function onReload(): void {
  void salvage.load()
}
</script>

<template>
  <div class="screen" data-test="ecto-screen">
    <PageHeader
      heading="Ectoplasm Salvage"
      intro="What salvaging a Glob of Ectoplasm for Luck really costs, once the recovered Crystalline Dust has been sold. The backend calculates every figure from live Trading Post prices; this page only displays them."
    >
      <template #actions>
        <button
          type="button"
          class="button--primary"
          data-test="ecto-reload"
          :disabled="salvage.phase.value === 'loading'"
          @click="onReload"
        >
          Reload calculation
        </button>
      </template>
    </PageHeader>

    <div class="stack">
      <p class="meta prose">
        Prices come from the Trading Post at the moment the calculation ran, not from the
        synchronized database, so a reload is the only thing that refreshes them. This page changes
        nothing on the account and starts no synchronization.
      </p>

      <p
        v-if="salvage.phase.value === 'loading'"
        class="notice notice--info"
        role="status"
        data-test="ecto-loading"
      >
        Calculating from live Trading Post prices…
      </p>

      <p
        v-else-if="salvage.failure.value !== null"
        class="notice notice--error"
        data-test="ecto-error"
      >
        The Ectoplasm calculation could not be completed, so no figures are shown — this is a
        failure, not a result of zero.
        <span class="meta detail">
          Backend answer: {{ salvage.failure.value.code }} — {{ salvage.failure.value.message }}
        </span>
        <span class="notice__actions">
          <button type="button" data-test="ecto-retry" @click="onReload">Try again</button>
        </span>
      </p>

      <p v-else-if="hasNoResult" class="notice notice--warning" data-test="ecto-unavailable">
        The Trading Post returned no usable prices for both Glob of Ectoplasm and Crystalline Dust,
        so this calculation has no result. That is an answer, not a failure, and no figure is shown
        as zero in its place.
      </p>

      <template v-else-if="salvage.data.value !== null">
        <section class="panel" aria-labelledby="ecto-basis-heading" data-test="ecto-basis">
          <h2 id="ecto-basis-heading" class="panel__title">Basis of these figures</h2>

          <ul class="basis">
            <li data-test="ecto-assumption-yield">
              One Ectoplasm is expected to yield about
              {{ salvage.data.value.assumptions.expectedLuckPerEcto }} Luck and about
              {{ salvage.data.value.assumptions.expectedDustPerEcto }} Crystalline Dust. These are
              expected values over many salvages, not a guaranteed drop from one.
            </li>
            <li data-test="ecto-assumption-luck">
              1000 Luck is costed as
              {{ salvage.data.value.assumptions.ectosPer1000Luck }} Ectoplasm.
            </li>
            <li data-test="ecto-assumption-fee">
              Profit, net cost and Luck cost already have the Trading Post's
              {{ salvage.data.value.assumptions.tradingPostSellFeePercent }}% selling fee deducted
              once, from the expected gross value of the Dust one Ectoplasm recovers. Values labelled
              gross carry no fee — the Trading Post quotes and that recovered Dust value alike — and
              buying an Ectoplasm carries no selling fee.
            </li>
          </ul>
        </section>

        <section
          v-if="quotes !== null"
          class="panel"
          aria-labelledby="ecto-prices-heading"
          data-test="ecto-prices"
        >
          <h2 id="ecto-prices-heading" class="panel__title">Live Trading Post prices</h2>

          <div class="quotes">
            <div class="quote-item">
              <p class="quote-item__name">
                <!--
                  The shared icon component with no supplied source: this route carries no item
                  metadata, so the established neutral placeholder stands in. No URL is built here
                  and nothing is requested from ArenaNet.
                -->
                <ItemIcon :item-id="salvage.data.value.ectoItemId" :icon-url="null" loading="eager" />
                <span>Glob of Ectoplasm</span>
                <span class="meta" data-test="ecto-item-id">#{{ salvage.data.value.ectoItemId }}</span>
              </p>
              <dl class="quote-values">
                <div>
                  <dt>Instant buy (gross)</dt>
                  <dd class="money" data-test="ecto-quote-instant-buy">
                    {{ formatCopper(quotes.ectoInstantBuy) }}
                  </dd>
                </div>
                <div>
                  <dt>Buy order (gross)</dt>
                  <dd class="money" data-test="ecto-quote-buy-order">
                    {{ formatCopper(quotes.ectoBuyOrder) }}
                  </dd>
                </div>
              </dl>
            </div>

            <div class="quote-item">
              <p class="quote-item__name">
                <ItemIcon :item-id="salvage.data.value.dustItemId" :icon-url="null" loading="eager" />
                <span>Crystalline Dust</span>
                <span class="meta" data-test="dust-item-id">#{{ salvage.data.value.dustItemId }}</span>
              </p>
              <dl class="quote-values">
                <div>
                  <dt>Instant sell (gross)</dt>
                  <dd class="money" data-test="dust-quote-instant-sell">
                    {{ formatCopper(quotes.dustInstantSellGross) }}
                  </dd>
                </div>
                <div>
                  <dt>Listing sell (gross)</dt>
                  <dd class="money" data-test="dust-quote-listing-sell">
                    {{ formatCopper(quotes.dustListingSellGross) }}
                  </dd>
                </div>
              </dl>
            </div>
          </div>
        </section>

        <section aria-labelledby="ecto-scenarios-heading" class="stack">
          <h2 id="ecto-scenarios-heading">All four buying and selling combinations</h2>

          <div
            class="table-region"
            role="region"
            aria-label="Ectoplasm salvage scenarios, scrollable"
            tabindex="0"
          >
            <table class="scenario-table" data-test="ecto-scenario-table">
              <caption class="visually-hidden">
                One row per combination of how the Ectoplasm is bought and how the recovered Dust is
                sold. Values marked gross carry no fee — the two Trading Post quotes and the
                expected value of the recovered Dust; the columns marked after fees are the same
                recovered Dust value less the selling fee, and the net cost, profit and Luck cost
                that follow from it. Every figure is per one Ectoplasm except the last column.
              </caption>

              <thead>
                <tr>
                  <th scope="col">Buy Ectoplasm</th>
                  <th scope="col">Sell Dust</th>
                  <th scope="col" class="numeric">
                    <span class="column-label">
                      Ectoplasm cost
                      <span class="column-note">gross, per ecto</span>
                    </span>
                  </th>
                  <th scope="col" class="numeric">
                    <span class="column-label">
                      Dust quote
                      <span class="column-note">gross, per dust</span>
                    </span>
                  </th>
                  <th scope="col" class="numeric">
                    <span class="column-label">
                      Recovered Dust
                      <span class="column-note">gross, per ecto</span>
                    </span>
                  </th>
                  <th scope="col" class="numeric">
                    <span class="column-label">
                      Recovered Dust
                      <span class="column-note">
                        after {{ salvage.data.value.assumptions.tradingPostSellFeePercent }}% TP
                        fees, per ecto
                      </span>
                    </span>
                  </th>
                  <th scope="col" class="numeric">
                    <span class="column-label">
                      Net cost
                      <span class="column-note">
                        after {{ salvage.data.value.assumptions.tradingPostSellFeePercent }}% TP
                        fees, per ecto
                      </span>
                    </span>
                  </th>
                  <th scope="col" class="numeric">
                    <span class="column-label">
                      Profit
                      <span class="column-note">
                        after {{ salvage.data.value.assumptions.tradingPostSellFeePercent }}% TP
                        fees, per ecto
                      </span>
                    </span>
                  </th>
                  <th scope="col" class="numeric">
                    <span class="column-label">
                      Cost per 1000 Luck
                      <span class="column-note">
                        after {{ salvage.data.value.assumptions.tradingPostSellFeePercent }}% TP fees
                      </span>
                    </span>
                  </th>
                </tr>
              </thead>

              <tbody>
                <tr
                  v-for="scenario in scenarios"
                  :key="scenario.key"
                  :data-test="`ecto-scenario-${scenario.key}`"
                  class="scenario-row"
                >
                  <th scope="row" data-test="ecto-scenario-acquisition">
                    {{ scenario.acquisition }}
                  </th>
                  <td data-test="ecto-scenario-sale">{{ scenario.sale }}</td>
                  <td class="numeric" data-test="ecto-scenario-ecto-cost">
                    <span class="money money--cost">
                      {{ formatCopper(scenario.values.ectoAcquisitionCostCopper) }}
                    </span>
                  </td>
                  <td class="numeric" data-test="ecto-scenario-dust-gross">
                    <span class="money">
                      {{ formatCopper(scenario.values.dustGrossUnitPriceCopper) }}
                    </span>
                  </td>
                  <td class="numeric" data-test="ecto-scenario-dust-recovered-gross">
                    <span class="money">
                      {{ formatCopper(scenario.values.expectedGrossRecoveredDustValueCopper) }}
                    </span>
                  </td>
                  <td class="numeric" data-test="ecto-scenario-dust-recovered-net">
                    <span class="money">
                      {{ formatCopper(scenario.values.netValueOfRecoveredDustCopper) }}
                    </span>
                  </td>
                  <td class="numeric" data-test="ecto-scenario-net-cost">
                    <span class="money">
                      {{ formatCopper(scenario.values.netCostPerEctoCopper) }}
                    </span>
                  </td>
                  <td class="numeric" data-test="ecto-scenario-profit">
                    <span :class="`money money--${moneyTone(scenario.values.profitPerEctoCopper)}`">
                      {{ formatSignedCopper(scenario.values.profitPerEctoCopper) }}
                    </span>
                    <span class="meta outcome" data-test="ecto-scenario-outcome">
                      {{ outcomeOf(scenario.values.profitPerEctoCopper) }}
                    </span>
                  </td>
                  <td class="numeric" data-test="ecto-scenario-luck-cost">
                    <span class="money">
                      {{ formatCopper(scenario.values.costPer1000LuckCopper) }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <p class="meta prose">
            A negative net cost or Luck cost means the recovered Dust is worth more than the
            Ectoplasm it came from; the sign is written out so the figure reads the same without
            color.
          </p>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: block;
  margin-top: var(--space-2);
}

.basis {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-width: var(--prose-max);
  margin: 0;
  padding-left: var(--space-4);
  color: var(--color-muted);
  font-size: var(--text-sm);
}

/* Two item blocks side by side once there is room, stacked before that. */
.quotes {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 18rem), 1fr));
  gap: var(--space-4);
}

.quote-item__name {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0 0 var(--space-2);
  font-weight: 600;
}

.quote-values {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin: 0;
}

.quote-values > div {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
}

.quote-values dt {
  color: var(--color-muted);
  font-size: var(--text-sm);
}

.quote-values dd {
  margin: 0;
}

.scenario-table {
  border-collapse: collapse;
  /* Sized by its content, not stretched: the region around it owns the available width. */
  min-width: 100%;
  font-size: var(--text-sm);
}

.scenario-table th,
.scenario-table td {
  border-bottom: 1px solid var(--color-border);
  padding: var(--space-2) var(--space-3);
  text-align: left;
  vertical-align: top;
}

.scenario-table thead th {
  background: var(--color-raised);
  white-space: nowrap;
}

.column-label {
  display: inline-flex;
  flex-direction: column;
}

.numeric .column-label {
  align-items: flex-end;
}

/* The basis of the column, so a gross quote is never read as a fee-inclusive result. */
.column-note {
  color: var(--color-muted);
  font-size: var(--text-sm);
  font-weight: 400;
  text-transform: lowercase;
}

/* The word beside the figure: the meaning survives a monochrome rendering. */
.outcome {
  display: block;
  text-transform: lowercase;
}
</style>
