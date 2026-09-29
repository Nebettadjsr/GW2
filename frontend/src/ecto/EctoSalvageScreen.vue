<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getJson } from '@/api/http'
import { formatCopper } from '@/crafting/formatCopper'
import ItemIcon from '@/items/ItemIcon.vue'
import TradingPostPriceDisclaimer from '@/items/TradingPostPriceDisclaimer.vue'
import PageHeader from '@/shell/PageHeader.vue'

type BuyMode = 'instant' | 'order'
type SellMode = 'instant' | 'listing'
type LuckTargetKind = 'PLUS_5' | 'PLUS_10' | 'CAP'

interface ItemMetadata {
  itemId: number
  name: string | null
  iconUrl: string | null
}

interface ItemPrice {
  itemId: number
  buyUnitCopper: number | null
  sellUnitCopper: number | null
}

interface ItemPricesResponse {
  prices: ItemPrice[]
}

interface ItemMetadataResponse {
  items: ItemMetadata[]
}

interface LuckTarget {
  kind: LuckTargetKind
  magicFindPercent: number
  cumulativeLuck: number
  luckRemaining: number
}

interface AccountLuckResponse {
  consumedLuck: number
  currentLuckMagicFindPercent: number
  cumulativeLuckForCurrentPercent: number
  nextMagicFindPercent: number | null
  cumulativeLuckForNextPercent: number | null
  luckRemainingToNextPercent: number
  luckRemainingToCap: number
  cumulativeLuckForCap: number
  fetchedAt: string
  targets: LuckTarget[]
}

interface SalvageTool {
  id: string
  name: string
  costPerUseCopper: number
  gemsPerUse?: number
  costNote?: string
}

interface SalvageMethod {
  id: string
  name: string
  itemId: number
  rareMaterialsChance: number
  dustPerEcto: number
  luckPerEcto: number
  tools: [SalvageTool, ...SalvageTool[]]
  defaultToolId: string
}

interface DisplayLuckTarget {
  key: string
  label: string
  magicFindPercent: number
  luckRemaining: number
  ectosRequired: number
  ectoCostCopper: number | null
  salvageCostCopper: number
  gemCost: number
  effectiveCostCopper: number | null
}

/*
 * Yield data: GW2 Wiki Ectoplasm salvage research.
 * Tool costs: GW2 Wiki Salvage kit table.
 *
 * Standard-kit values use the Wiki's first listed coin cost/use.
 * Mystic's 10.496c/use excludes the acquisition value of Mystic Forge Stones,
 * matching the Wiki's stated caveat.
 * Black Lion's Gem Store basis is 300 Gems / 25 uses = 12 Gems/use.
 * Gems are shown separately and are deliberately not converted to gold yet.
 */
const salvageMethods: SalvageMethod[] = [
  {
    id: 'basic',
    name: 'Basic / Copper-Fed',
    itemId: 44602,
    rareMaterialsChance: 10,
    dustPerEcto: 1.63,
    luckPerEcto: 103.17,
    defaultToolId: 'copper-fed',
    tools: [
      { id: 'basic-kit', name: 'Basic', costPerUseCopper: 3.52 },
      { id: 'copper-fed', name: 'Copper-Fed', costPerUseCopper: 3 }
    ]
  },
  {
    id: 'fine',
    name: 'Fine',
    itemId: 23041,
    rareMaterialsChance: 15,
    dustPerEcto: 1.75,
    luckPerEcto: 107.83,
    defaultToolId: 'fine-kit',
    tools: [
      { id: 'fine-kit', name: 'Fine', costPerUseCopper: 11.52 }
    ]
  },
  {
    id: 'journeyman',
    name: 'Journeyman / Runecrafter',
    itemId: 89409,
    rareMaterialsChance: 20,
    dustPerEcto: 1.74,
    luckPerEcto: 106.33,
    defaultToolId: 'runecrafter',
    tools: [
      { id: 'journeyman-kit', name: 'Journeyman', costPerUseCopper: 32 },
      { id: 'runecrafter', name: 'Runecrafter', costPerUseCopper: 30 }
    ]
  },
  {
    id: 'master',
    name: "Master's / Mystic / Silver-Fed",
    itemId: 67027,
    rareMaterialsChance: 25,
    dustPerEcto: 1.85,
    luckPerEcto: 104.57,
    defaultToolId: 'silver-fed',
    tools: [
      { id: 'masters-kit', name: "Master's", costPerUseCopper: 61.44 },
      {
        id: 'mystic-kit',
        name: 'Mystic',
        costPerUseCopper: 10.496,
        costNote: 'Coin component only; Mystic Forge Stone acquisition value is not included.'
      },
      { id: 'silver-fed', name: 'Silver-Fed', costPerUseCopper: 60 }
    ]
  },
  {
    id: 'black-lion',
    name: 'Black Lion',
    itemId: 19986,
    rareMaterialsChance: 50,
    dustPerEcto: 2.04,
    luckPerEcto: 104.75,
    defaultToolId: 'black-lion-kit',
    tools: [
      {
        id: 'black-lion-kit',
        name: 'Black Lion',
        costPerUseCopper: 0,
        gemsPerUse: 12,
        costNote: 'Gem cost is shown separately and is not converted to gold.'
      }
    ]
  }
]

const defaultMethod = salvageMethods.find(method => method.id === 'master')
if (!defaultMethod) throw new Error('Default Ecto salvage method is missing')

const ECTO_ID = 19721
const DUST_ID = 24277
const ITEM_IDS = [ECTO_ID, DUST_ID, ...salvageMethods.map(method => method.itemId)]
const TP_SELL_MULTIPLIER = 0.85

const metadata = ref<Map<number, ItemMetadata>>(new Map())
const prices = ref<Map<number, ItemPrice>>(new Map())
const accountLuck = ref<AccountLuckResponse | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)

const selectedMethodId = ref(defaultMethod.id)
const selectedToolId = ref(defaultMethod.defaultToolId)
const ectoCount = ref(100)
const ectoBuyMode = ref<BuyMode>('instant')
const dustSellMode = ref<SellMode>('instant')
const tpRefreshing = ref(false)
const tpRefreshError = ref<string | null>(null)

const selectedMethod = computed<SalvageMethod>(() =>
  salvageMethods.find(method => method.id === selectedMethodId.value) ?? defaultMethod
)

const selectedTool = computed<SalvageTool>(() => {
  const method = selectedMethod.value
  return method.tools.find(tool => tool.id === selectedToolId.value) ??
    method.tools.find(tool => tool.id === method.defaultToolId) ??
    method.tools[0]
})

watch(selectedMethodId, () => {
  selectedToolId.value = selectedMethod.value.defaultToolId
})

function itemMetadata(itemId: number): ItemMetadata | undefined {
  return metadata.value.get(itemId)
}

function iconUrl(itemId: number): string | null {
  return itemMetadata(itemId)?.iconUrl ?? null
}

function itemPrice(itemId: number): ItemPrice | undefined {
  return prices.value.get(itemId)
}

const ectoInstantBuyCopper = computed(() => itemPrice(ECTO_ID)?.sellUnitCopper ?? null)
const ectoBuyOrderCopper = computed(() => itemPrice(ECTO_ID)?.buyUnitCopper ?? null)
const dustInstantSellCopper = computed(() => itemPrice(DUST_ID)?.buyUnitCopper ?? null)
const dustListingSellCopper = computed(() => itemPrice(DUST_ID)?.sellUnitCopper ?? null)

const selectedEctoPrice = computed(() =>
  ectoBuyMode.value === 'instant' ? ectoInstantBuyCopper.value : ectoBuyOrderCopper.value
)

const selectedDustPrice = computed(() =>
  dustSellMode.value === 'instant' ? dustInstantSellCopper.value : dustListingSellCopper.value
)

const normalizedEctoCount = computed(() => {
  const value = Number(ectoCount.value)
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0
})

const expectedLuck = computed(() => normalizedEctoCount.value * selectedMethod.value.luckPerEcto)
const expectedDust = computed(() => normalizedEctoCount.value * selectedMethod.value.dustPerEcto)

const ectoCostCopper = computed<number | null>(() =>
  selectedEctoPrice.value == null ? null : normalizedEctoCount.value * selectedEctoPrice.value
)

const salvageCostCopper = computed(() =>
  Math.round(normalizedEctoCount.value * selectedTool.value.costPerUseCopper)
)

const salvageGemCost = computed(() =>
  normalizedEctoCount.value * (selectedTool.value.gemsPerUse ?? 0)
)

const dustNetCopper = computed<number | null>(() => {
  if (selectedDustPrice.value == null) return null
  return Math.floor(expectedDust.value * selectedDustPrice.value * TP_SELL_MULTIPLIER)
})

const effectiveLuckCostCopper = computed<number | null>(() => {
  if (ectoCostCopper.value == null || dustNetCopper.value == null) return null
  return ectoCostCopper.value + salvageCostCopper.value - dustNetCopper.value
})

const costPer1000LuckCopper = computed<number | null>(() => {
  if (effectiveLuckCostCopper.value == null || expectedLuck.value <= 0) return null
  return Math.round((effectiveLuckCostCopper.value / expectedLuck.value) * 1000)
})

function buildTarget(
  key: string,
  label: string,
  magicFindPercent: number,
  luckRemaining: number
): DisplayLuckTarget {
  const ectosRequired = luckRemaining <= 0
    ? 0
    : Math.ceil(luckRemaining / selectedMethod.value.luckPerEcto)

    const targetDust = ectosRequired * selectedMethod.value.dustPerEcto
    const targetSalvageCost = Math.round(
      ectosRequired * selectedTool.value.costPerUseCopper
    )
    const targetGemCost =
      ectosRequired * (selectedTool.value.gemsPerUse ?? 0)

    const ectoCostCopper =
      selectedEctoPrice.value != null
        ? ectosRequired * selectedEctoPrice.value
        : null

    let effectiveCostCopper: number | null = null

    if (ectoCostCopper != null && selectedDustPrice.value != null) {
      const dustNet = Math.floor(
        targetDust *
        selectedDustPrice.value *
        TP_SELL_MULTIPLIER
      )

      effectiveCostCopper =
        ectoCostCopper +
        targetSalvageCost -
        dustNet
    }

    return {
      key,
      label,
      magicFindPercent,
      luckRemaining,
      ectosRequired,
      ectoCostCopper,
      salvageCostCopper: targetSalvageCost,
      gemCost: targetGemCost,
      effectiveCostCopper
    }
}

const luckTargets = computed<DisplayLuckTarget[]>(() => {
  const luck = accountLuck.value
  if (!luck) return []

  const result: DisplayLuckTarget[] = []

  if (luck.nextMagicFindPercent != null && luck.luckRemainingToNextPercent > 0) {
    result.push(
      buildTarget('next', 'Next +1%', luck.nextMagicFindPercent, luck.luckRemainingToNextPercent)
    )
  }

  for (const target of luck.targets) {
    const label = target.kind === 'PLUS_5'
      ? '+5%'
      : target.kind === 'PLUS_10'
        ? '+10%'
        : '300% cap'

    result.push(
      buildTarget(target.kind, label, target.magicFindPercent, target.luckRemaining)
    )
  }

  return result
})

const magicFindProgressPercent = computed(() => {
  const luck = accountLuck.value

  if (!luck) return 0

  if (
    luck.nextMagicFindPercent == null ||
    luck.cumulativeLuckForNextPercent == null
  ) {
    return 100
  }

  const currentThreshold = luck.cumulativeLuckForCurrentPercent
  const nextThreshold = luck.cumulativeLuckForNextPercent
  const levelSize = nextThreshold - currentThreshold

  if (levelSize <= 0) return 0

  const progress =
    ((luck.consumedLuck - currentThreshold) / levelSize) * 100

  return Math.max(0, Math.min(100, progress))
})

function formatNumber(value: number): string {
  return Math.round(value).toLocaleString()
}

function formatMoney(value: number | null): string {
  return value == null ? 'Price unavailable' : formatCopper(value)
}

function formatToolCost(copper: number, gems: number): string {
  const parts: string[] = []

  if (copper > 0 || gems === 0) {
    parts.push(formatCopper(copper))
  }

  if (gems > 0) {
    parts.push(`${gems.toLocaleString()} Gems`)
  }

  return parts.join(' + ')
}

async function refreshTradingPostPrices(): Promise<void> {
  if (tpRefreshing.value) return

  tpRefreshing.value = true
  tpRefreshError.value = null

  try {
    const response = await getJson<ItemPricesResponse>(
      `/items/prices?ids=${ECTO_ID},${DUST_ID}`
    )
    prices.value = new Map(response.prices.map(price => [price.itemId, price]))
  } catch {
    tpRefreshError.value = 'Trading Post prices could not be refreshed.'
  } finally {
    tpRefreshing.value = false
  }
}

async function loadPage(): Promise<void> {
  loading.value = true
  loadError.value = null

  const metadataPromise = getJson<ItemMetadataResponse>(
    `/items/metadata?ids=${encodeURIComponent(ITEM_IDS.join(','))}`
  )
  const pricesPromise = getJson<ItemPricesResponse>(
    `/items/prices?ids=${ECTO_ID},${DUST_ID}`
  )
  const luckPromise = getJson<AccountLuckResponse>('/account/luck')

  const [metadataResult, pricesResult, luckResult] = await Promise.allSettled([
    metadataPromise,
    pricesPromise,
    luckPromise
  ])

  const errors: string[] = []

  if (metadataResult.status === 'fulfilled') {
    metadata.value = new Map(metadataResult.value.items.map(item => [item.itemId, item]))
  } else {
    errors.push('Item metadata could not be read.')
  }

  if (pricesResult.status === 'fulfilled') {
    prices.value = new Map(pricesResult.value.prices.map(price => [price.itemId, price]))
  } else {
    errors.push('Trading Post prices could not be read.')
  }

  if (luckResult.status === 'fulfilled') {
    accountLuck.value = luckResult.value
  } else {
    errors.push('Account Luck could not be read.')
  }

  loadError.value = errors.length > 0 ? errors.join(' ') : null
  loading.value = false
}

onMounted(loadPage)
</script>

<template>
  <div class="screen" data-test="ecto-screen">
    <div class="page-heading-wrap">
      <PageHeader
        heading="Ecto Salvage"
        intro="Calculate how much Luck really costs after selling the Crystalline Dust recovered from salvaging your Ectos."
      />
      <button
        type="button"
        class="tp-refresh-button"
        :disabled="tpRefreshing"
        @click="refreshTradingPostPrices"
      >
        {{ tpRefreshing ? 'Refreshing…' : 'Refresh TP prices' }}
      </button>
    </div>

    <p v-if="tpRefreshError" class="notice notice--warning" role="alert">
      {{ tpRefreshError }}
    </p>

    <div v-if="loadError" class="notice notice--warning" role="alert">
      {{ loadError }}
    </div>

    <section class="panel" aria-labelledby="ecto-calculator-heading">
      <div class="calculation-controls-heading">
        <h2 id="ecto-calculator-heading" class="panel__title">Calculation controls</h2>
        <TradingPostPriceDisclaimer class="disclaimer-trigger" />
      </div>

    <div class="calculator-grid">
        <fieldset>
          <legend>Salvage tool</legend>

          <div class="salvage-table">
            <div class="salvage-header" aria-hidden="true">
              <span>Tool</span>
              <span>Rare mats</span>
              <span>Dust / Ecto</span>
              <span>Luck / Ecto</span>
            </div>

            <button
              v-for="method in salvageMethods"
              :key="method.id"
              type="button"
              class="salvage-row"
              :class="{ selected: selectedMethodId === method.id }"
              :aria-pressed="selectedMethodId === method.id"
              @click="selectedMethodId = method.id"
            >
              <span class="salvage-tool">
                <ItemIcon
                  :icon-url="iconUrl(method.itemId)"
                  :item-id="method.itemId"
                  :size="32"
                  loading="lazy"
                />
                <strong>{{ method.name }}</strong>
              </span>
              <strong class="numeric">{{ method.rareMaterialsChance }}%</strong>
              <strong class="numeric">{{ method.dustPerEcto.toFixed(2) }}</strong>
              <strong class="numeric">{{ method.luckPerEcto.toFixed(2) }}</strong>
            </button>
          </div>

          <p class="meta source-line">
            Expected yields are statistical averages from
            <a
              href="https://wiki.guildwars2.com/wiki/Glob_of_Ectoplasm/salvage_research"
              target="_blank"
              rel="noopener noreferrer"
            >GW2 Wiki salvage research</a>.
          </p>
        </fieldset>

        <fieldset>
          <legend>Trading Post prices</legend>

          <div class="stack tp-stack">
            <div class="tp-block">
              <div class="tp-heading">
                <ItemIcon :icon-url="iconUrl(ECTO_ID)" :item-id="ECTO_ID" :size="32" loading="eager" />
                <div>
                  <strong>{{ itemMetadata(ECTO_ID)?.name ?? 'Glob of Ectoplasm' }}</strong>
                  <div class="meta">How should the Ectos be valued?</div>
                </div>
              </div>

              <div class="tp-options">
                <button
                  type="button"
                  class="tp-option"
                  :class="{ selected: ectoBuyMode === 'instant' }"
                  :aria-pressed="ectoBuyMode === 'instant'"
                  @click="ectoBuyMode = 'instant'"
                >
                  <span>Instant buy</span>
                  <strong>{{ formatMoney(ectoInstantBuyCopper) }}</strong>
                </button>

                <button
                  type="button"
                  class="tp-option"
                  :class="{ selected: ectoBuyMode === 'order' }"
                  :aria-pressed="ectoBuyMode === 'order'"
                  @click="ectoBuyMode = 'order'"
                >
                  <span>Buy order</span>
                  <strong>{{ formatMoney(ectoBuyOrderCopper) }}</strong>
                </button>

              </div>

              <p class="meta ecto-value-note">
                <strong>Already own the Ectos?</strong> They aren't free to salvage—the Ectos themselves have market value.
                Choose Instant Buy or Buy Order above to decide how that consumed value should be calculated.
              </p>
            </div>

            <div class="tp-block">
              <div class="tp-heading">
                <ItemIcon :icon-url="iconUrl(DUST_ID)" :item-id="DUST_ID" :size="32" loading="eager" />
                <div>
                  <strong>{{ itemMetadata(DUST_ID)?.name ?? 'Pile of Crystalline Dust' }}</strong>
                  <div class="meta">How do you want to sell the recovered Dust?</div>
                </div>
              </div>

              <div class="tp-options">
                <button
                  type="button"
                  class="tp-option"
                  :class="{ selected: dustSellMode === 'instant' }"
                  :aria-pressed="dustSellMode === 'instant'"
                  @click="dustSellMode = 'instant'"
                >
                  <span>Instant sell</span>
                  <strong>{{ formatMoney(dustInstantSellCopper) }}</strong>
                </button>

                <button
                  type="button"
                  class="tp-option"
                  :class="{ selected: dustSellMode === 'listing' }"
                  :aria-pressed="dustSellMode === 'listing'"
                  @click="dustSellMode = 'listing'"
                >
                  <span>Listing sell</span>
                  <strong>{{ formatMoney(dustListingSellCopper) }}</strong>
                </button>
              </div>
            </div>

            <p class="meta">Dust value includes the Trading Post's 15% selling fees.</p>
          </div>
        </fieldset>
      </div>
    </section>

    <section class="panel" aria-labelledby="ecto-result-heading">
      <h2 id="ecto-result-heading" class="panel__title">Ecto Salvage Result</h2>

        <div class="result-layout">
          <div class="result-left">
          <!-- Left: human-readable explanation -->
          <div class="result-story">
            <p class="result-lead">
              <span>If you salvage </span>
              <input
                id="ecto-count"
                v-model.number="ectoCount"
                class="ecto-count"
                type="number"
                min="0"
                step="1"
                inputmode="numeric"
                aria-label="Number of Ectos"
              />
              <span>Ectos using a </span>

              <span class="tool-choice">
                <button
                  v-for="tool in selectedMethod.tools"
                  :key="tool.id"
                  type="button"
                  class="tool-choice__button"
                  :class="{ 'tool-choice__button--selected': selectedToolId === tool.id }"
                  @click="selectedToolId = tool.id"
                >
                  {{ tool.name }}
                </button>
              </span>

              <span> Salvage Tool,</span>
            </p>

            <div class="result-details">
              <p>
                You approximately receive
                <span class="result-value">{{ formatNumber(expectedLuck) }} Luck</span>
                and
                <span class="result-value">{{ formatNumber(expectedDust) }} Dust</span>
                and pay
                <span class="result-value">{{ formatToolCost(salvageCostCopper, salvageGemCost) }}</span>
                for use of the salvage tool.
              </p>

              <p v-if="selectedTool.costNote" class="meta tool-cost-note">
                {{ selectedTool.costNote }}
              </p>

            </div>
          </div>

          <!-- Right: scan-friendly calculation -->
          <div
            v-if="effectiveLuckCostCopper != null"
            class="salvage-calculation"
          >
            <h3>Salvage calculation</h3>

            <div class="calculation-group">
              <div class="calculation-row">
                <span>Luck received</span>
                <strong class="value-positive">
                  +{{ formatNumber(expectedLuck) }}
                </strong>
              </div>

              <div class="calculation-row">
                <span>Dust received</span>
                <strong class="value-positive">
                  +{{ formatNumber(expectedDust) }}
                </strong>
              </div>
            </div>

            <div class="calculation-group">
              <div class="calculation-row" v-if="ectoCostCopper != null">
                <span>Ecto value consumed</span>
                <strong class="value-negative">
                  -{{ formatMoney(ectoCostCopper) }}
                </strong>
              </div>


              <div class="calculation-row">
                <span>Salvage tool cost</span>

                <strong
                  v-if="salvageGemCost > 0"
                  class="value-negative"
                >
                  -{{ salvageGemCost.toLocaleString() }} Gems
                </strong>

                <strong
                  v-else
                  class="value-negative"
                >
                  -{{ formatMoney(salvageCostCopper) }}
                </strong>
              </div>

              <div class="calculation-row" v-if="dustNetCopper != null">
                <span>Dust value after TP fees</span>
                <strong class="value-positive">
                  +{{ formatMoney(dustNetCopper) }}
                </strong>
              </div>


            </div>

            <div class="calculation-total">
              <span>
                {{ salvageGemCost > 0 ? 'Effective coin result' : 'Effective cost' }}
              </span>

              <strong
                :class="{
                  'value-negative': effectiveLuckCostCopper > 0,
                  'value-positive': effectiveLuckCostCopper < 0
                }"
              >
                {{ formatMoney(effectiveLuckCostCopper) }}
              </strong>
            </div>

            <div
              v-if="salvageGemCost > 0"
              class="calculation-row calculation-gem-total"
            >
              <span>Additional cost</span>
              <strong class="value-negative">
                -{{ salvageGemCost.toLocaleString() }} Gems
              </strong>
            </div>

            <div class="calculation-group calculation-luck">
              <div class="calculation-row">
                <span>Luck received</span>
                <strong class="value-positive">
                  {{ formatNumber(expectedLuck) }}
                </strong>
              </div>

              <div
                v-if="costPer1000LuckCopper != null"
                class="calculation-row"
              >
                <span>Cost per 1,000 Luck</span>
                <strong>{{ formatMoney(costPer1000LuckCopper) }}</strong>
              </div>
            </div>
          </div>

          <p v-else class="meta">
            The final cost will appear when Trading Post prices are available.
          </p>
          </div>

          <div class="magic-find-section">
        <div class="section-heading">
          <div class="stack heading-copy">
            <h2>Your Luck &amp; Magic Find</h2>
            <p class="meta">
              See what your next permanent Luck-based Magic Find increases would cost using the calculator settings above.
            </p>
          </div>

          <a
            href="https://wiki.guildwars2.com/wiki/Magic_Find"
            target="_blank"
            rel="noopener noreferrer"
          >What does Magic Find do?</a>
        </div>

        <div v-if="accountLuck" class="stack account-luck">
          <div class="account-summary">
            <div>
              <span class="meta">Consumed Luck</span>
              <strong>{{ accountLuck.consumedLuck.toLocaleString() }}</strong>
            </div>
            <div>
              <span class="meta">Luck-based Magic Find</span>
              <strong>{{ accountLuck.currentLuckMagicFindPercent }}%</strong>
            </div>
            <div>
              <span class="meta">Next level</span>
              <strong>
                {{ accountLuck.nextMagicFindPercent == null ? 'Maximum' : `${accountLuck.nextMagicFindPercent}%` }}
              </strong>
            </div>
          </div>

          <div class="luck-progress">
            <div class="luck-progress__labels">
              <strong>{{ accountLuck.currentLuckMagicFindPercent }}%</strong>
              <span>{{ accountLuck.nextMagicFindPercent == null ? 'Luck cap reached' : `${accountLuck.luckRemainingToNextPercent.toLocaleString()} Luck remaining` }}</span>
              <strong>{{ accountLuck.nextMagicFindPercent == null ? 'Maximum' : `${accountLuck.nextMagicFindPercent}%` }}</strong>
            </div>
            <div class="luck-progress__track" aria-hidden="true">
              <div
                class="luck-progress__bar"
                :style="{ width: `${magicFindProgressPercent}%` }"
              />
            </div>
          </div>

          <div class="table-region" tabindex="0" aria-label="Magic Find target costs">
            <div class="target-table">
              <div class="target-header">
                <span>Target</span>
                <span>Luck needed</span>
                <span>Ectos to salvage</span>
                <span class="target-header__stack">
                  <span>Ecto value</span>
                  <small>Value consumed</small>
                </span>
                <span class="target-header__stack">
                  <span>Effective cost</span>
                  <small>After Dust sale</small>
                </span>
              </div>

              <div
                v-for="target in luckTargets"
                :key="target.key"
                class="target-row"
              >
                <span class="target-name">
                  <strong>{{ target.label }}</strong>
                  <span class="meta">
                    → {{ target.magicFindPercent }}% MF
                  </span>
                </span>

                <span>
                  {{ target.luckRemaining.toLocaleString() }}
                </span>

                <span>
                  {{ target.ectosRequired.toLocaleString() }}
                </span>

                <span>
                  {{ formatMoney(target.ectoCostCopper) }}
                </span>

                <span class="target-cost">
                  <strong
                    :class="{
                      'value-negative': target.effectiveCostCopper != null && target.effectiveCostCopper > 0,
                      'value-positive': target.effectiveCostCopper != null && target.effectiveCostCopper < 0
                    }"
                  >
                    {{ formatMoney(target.effectiveCostCopper) }}
                  </strong>

                  <small
                    v-if="target.gemCost > 0"
                    class="meta"
                  >
                    + {{ target.gemCost.toLocaleString() }} Gems
                  </small>
                </span>
              </div>
            </div>
          </div>

          <p class="meta">
            Ectos are rounded up. Effective cost includes the selected Ecto market value, salvage-tool cost,
            recovered Dust value and TP fees. Gem costs are shown separately.
          </p>
        </div>

        <p v-else-if="loading" class="meta">Loading account Luck…</p>
        <p v-else class="meta">Account Luck is currently unavailable.</p>
          </div>
        </div>

      <footer class="source-footer">
        <span class="meta">Sources:</span>
        <a
          href="https://wiki.guildwars2.com/wiki/Glob_of_Ectoplasm/salvage_research"
          target="_blank"
          rel="noopener noreferrer"
        >Ectoplasm salvage research</a>
        <span aria-hidden="true">·</span>
        <a
          href="https://wiki.guildwars2.com/wiki/Salvage_kit"
          target="_blank"
          rel="noopener noreferrer"
        >Salvage kit costs &amp; rates</a>
        <span aria-hidden="true">·</span>
        <a
          href="https://wiki.guildwars2.com/wiki/Luck"
          target="_blank"
          rel="noopener noreferrer"
        >Luck progression</a>
        <span aria-hidden="true">·</span>
        <a
          href="https://wiki.guildwars2.com/wiki/Black_Lion_Salvage_Kit"
          target="_blank"
          rel="noopener noreferrer"
        >Black Lion Salvage Kit</a>
      </footer>
    </section>
  </div>
</template>

<style scoped>
/* Only page-specific layout lives here. Shared controls, panels, fieldsets,
   typography, colors, spacing and table-region styling come from styles.css. */

.page-heading-wrap {
  position: relative;
}

.tp-refresh-button {
  position: absolute;
  top: 0;
  right: 0;
}

.tp-disclaimer-row {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--space-2);
}

.tp-disclaimer-row :deep(button) {
  border-color: var(--color-danger);
  color: var(--color-danger);
}

.ecto-value-note {
  margin-top: var(--space-2);
}

.result-details {
  margin-top: var(--space-3);
}

.disclaimer-trigger {
  color: var(--color-negative);
  border-color: var(--color-negative);
}

.result-details p + p {
  margin-top: var(--space-3);
}

.result-value {
  display: inline-block;
  padding: 0.1rem 0.45rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-raised);
  font-weight: 700;
  white-space: nowrap;
}

.result-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-6);
  align-items: start;
}

.result-left {
  min-width: 0;
  display: grid;
  gap: var(--space-5);
}

.result-story,
.salvage-calculation,
.magic-find-section {
  min-width: 0;
}

.result-story {
  line-height: 1.6;
}

.salvage-calculation {
  padding-top: var(--space-4);
  padding-left: 0;
  border-top: 1px solid var(--color-border);
  border-left: 0;
}

.salvage-calculation h3 {
  margin: 0 0 var(--space-4);
}

.table-region {
  width: 100%;
  min-width: 0;
  margin: 0;
  overflow-x: visible;
}

.calculation-group {
  display: grid;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.calculation-row,
.calculation-total {
  display: flex;
  justify-content: space-between;
  gap: var(--space-4);
}

.calculation-row strong,
.calculation-total strong {
  text-align: right;
  white-space: nowrap;
}

.value-positive {
  color: var(--color-success);
}

.value-negative {
  color: var(--color-danger);
}

.calculation-total {
  margin-top: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
  font-size: var(--text-lg);
  font-weight: 700;
}

.calculation-luck {
  margin-top: var(--space-5);
  margin-bottom: 0;
}

.magic-find-section {
  margin: 0;
  padding-top: 0;
  padding-left: var(--space-5);
  border-top: 0;
  border-left: 1px solid var(--color-border);
}

.calculation-controls-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.calculator-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
  align-items: stretch;
}

.calculator-grid > fieldset {
  min-width: 0;
}

.salvage-table {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
}

.salvage-header,
.salvage-row {
  display: grid;
  grid-template-columns: minmax(12rem, 1fr) 5.5rem 6.5rem 6.5rem;
  align-items: center;
  gap: var(--space-2);
}

.salvage-header {
  padding: var(--space-1) var(--space-2);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 0.78rem;
}

.salvage-header span:not(:first-child) {
  text-align: right;
}

.salvage-row {
  width: 100%;
  min-height: 3rem;
  padding: var(--space-1) var(--space-2);
  border: 0;
  border-bottom: 1px solid var(--color-border);
  border-radius: 0;
  background: transparent;
  text-align: left;
}

.salvage-row:last-child {
  border-bottom: 0;
}

.salvage-row:hover:not(:disabled),
.salvage-row.selected {
  background: var(--color-raised);
}

.salvage-row.selected {
  position: relative;
  z-index: 1;
  box-shadow: inset 0 0 0 1px var(--color-highlight);
}

.salvage-tool,
.tp-heading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.source-line {
  margin-top: var(--space-2);
}

.tp-stack {
  gap: var(--space-3);
}

.tp-block + .tp-block {
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.tp-heading {
  margin-bottom: var(--space-2);
}

.tp-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2);
}

.tp-option {
  width: 100%;
  justify-content: space-between;
  background: transparent;
}

.tp-option.selected {
  border-color: var(--color-highlight);
  background: var(--color-raised);
}

.result-story {
  max-width: 90rem;
  line-height: 1.6;
}

.result-story p + p {
  margin-top: var(--space-2);
}

.result-lead {
  font-size: var(--text-lg);
}

.ecto-count {
  width: 7rem;
  margin: 0 var(--space-1);
  font-size: var(--text-lg);
  font-weight: 600;
}

.tool-choice {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.tool-choice__button {
  min-height: 2.5rem;
  padding: var(--space-2) var(--space-3);
}

.tool-choice__button--selected {
  border-color: var(--color-highlight);
  background: var(--color-raised);
  font-weight: 600;
}

.tool-separator {
  color: var(--color-muted);
}

.tool-cost-note {
  margin-top: var(--space-1) !important;
}

.result-conclusion {
  color: var(--color-success);
  font-size: var(--text-lg);
  font-weight: 600;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.heading-copy {
  gap: var(--space-1);
}

.account-luck {
  margin-top: var(--space-3);
}

.account-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-6);
}

.account-summary > div {
  display: flex;
  flex-direction: column;
}

.account-summary strong {
  font-size: var(--text-lg);
}

.luck-progress {
  width: 100%;
  min-width: 0;
  margin: var(--space-2) 0 var(--space-3);
}

.luck-progress__labels {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: end;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
  font-size: var(--text-sm);
}

.luck-progress__labels span {
  text-align: center;
  color: var(--color-muted);
}

.luck-progress__track {
  width: 100%;
  height: 1rem;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--color-bg);
}

.luck-progress__bar {
  height: 100%;
  border-radius: inherit;
  background: var(--color-highlight);
  transition: width var(--transition-fast);
}

.target-table {
  width: 100%;
  min-width: 0;
}

.target-header,
.target-row {
  display: grid;
  grid-template-columns:
    minmax(5.5rem, 1.15fr)
    minmax(5rem, 0.85fr)
    minmax(4.5rem, 0.8fr)
    minmax(5.75rem, 1fr)
    minmax(5.75rem, 1fr);
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2);
}

.target-header > span,
.target-row > span {
  min-width: 0;
}

.target-header {
  border-bottom: 1px solid var(--color-border);
  background: var(--color-raised);
  font-size: var(--text-sm);
  font-weight: 600;
}

.target-header__stack {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.target-header__stack small {
  color: var(--color-muted);
  font-size: 0.75rem;
  font-weight: 400;
  line-height: 1.15;
}

.target-row {
  border-bottom: 1px solid var(--color-border);
}

.target-row:last-child {
  border-bottom: 0;
}

.target-name,
.target-cost {
  display: flex;
  flex-direction: column;
}

.source-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) var(--space-2);
  margin-top: var(--space-5);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
  font-size: var(--text-sm);
}

@media (max-width: 64rem) {
  .result-layout {
    grid-template-columns: 1fr;
  }

  .magic-find-section {
    padding-top: var(--space-5);
    padding-left: 0;
    border-top: 1px solid var(--color-border);
    border-left: 0;
  }

  .calculator-grid {
    grid-template-columns: 1fr;
  }

  .luck-progress {
    width: 100%;
    min-width: 0;
  }
}

@media (max-width: 48rem) {
  .tp-refresh-button {
    position: static;
    margin-bottom: var(--space-3);
  }

  .salvage-header {
    display: none;
  }

  .salvage-row {
    grid-template-columns: 1fr auto auto auto;
  }

  .section-heading {
    flex-direction: column;
  }
}

@media (max-width: 34rem) {
  .tp-options {
    grid-template-columns: 1fr;
  }

  .salvage-row {
    grid-template-columns: 1fr 1fr;
  }

  .salvage-tool {
    grid-column: 1 / -1;
  }
}
</style>