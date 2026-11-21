<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, ref } from 'vue'
import { mysticForgeApi, type MysticForgeApi } from '@/api/mysticForgeApi'
import type { ForgeCurrency, ForgeNotes, ForgeRecipeRow, NoteEstimate } from '@/api/types'
import { formatCopper } from '@/crafting/formatCopper'
import ItemIcon from '@/items/ItemIcon.vue'
import CoinAmount from '@/items/CoinAmount.vue'
import { itemRarityColor } from '@/items/itemRarity'
import TradingPostPriceDisclaimer from '@/items/TradingPostPriceDisclaimer.vue'
import PageHeader from '@/shell/PageHeader.vue'
import ResearchNoteEstimatePopup from '@/crafting/ResearchNoteEstimatePopup.vue'
import MysticForgeNode from './MysticForgeNode.vue'
import ForgeEquipmentDetail from './ForgeEquipmentDetail.vue'
import ForgeItemSummary from './ForgeItemSummary.vue'
import ForgeBrowseFilters from './ForgeBrowseFilters.vue'
import ForgeRecipePaths from './ForgeRecipePaths.vue'
import { forgeItemTypeLabel, TARGET_DEBOUNCE_MS, parseTarget, useMysticForge } from './useMysticForge'

/**
 * Mystic Forge: a Forge-only recipe overview built from cached catalogue metadata, and one selected
 * project planned by the backend for an exact target. Filtering, searching and paging only touch the
 * loaded catalogue; the planner runs once for the opened recipe and again only when its target changes.
 * No amount here is computed in the browser: costs, margins and quantities are the backend's.
 */
const props = withDefaults(defineProps<{ api?: MysticForgeApi }>(), { api: () => mysticForgeApi })
const f = useMysticForge(props.api)
const listScroll = ref(0)
const focusedRecipe = ref<number | null>(null)
const list = ref<HTMLElement | null>(null)
void TARGET_DEBOUNCE_MS

onMounted(() => f.activate())
onActivated(() => f.activate())
onDeactivated(() => f.deactivate())

const detail = computed(() => f.selectedRecipeId.value !== null)
const row = computed(() => f.selectedRow.value)
const project = computed(() => f.currentProject.value)
const projectLayout = computed(() => row.value !== null)
/** Weapon/Armor details whose direct-input comparison is only unknown bound Gifts carry no usable economics. */
const showEconomics = computed(() => {
  const e = project.value?.economics
  if (!e) return false
  const gearOnlyUnknown = (row.value?.category === 'WEAPONS' || row.value?.category === 'ARMOR')
    && e.unpricedInputs.length > 0 && e.goldInputCopper === 0 && e.currencyInputs.length === 0
  return !gearOnlyUnknown
})
const referenceOnly = computed(() => project.value?.outcome === 'REFERENCE_RANDOM')
const batchTarget = computed(() => project.value?.targetKind === 'BATCHES' || row.value?.outcome !== 'FIXED')
const targetValid = computed(() => parseTarget(f.targetText.value) !== null)

function noteEstimate(n: ForgeNotes): NoteEstimate {
  return { perCraftNotes: 0, totalNotes: n.walletUsed ?? 0, perCraftCopper: 0, totalCopper: n.estimateCopper ?? 0,
    basisRecipeId: n.basisRecipeId, variants: n.variants }
}
function quote(value: number | null): string { return value === null ? 'Not traded / price unavailable' : formatCopper(value) }
function recipeNote(r: ForgeRecipeRow): string {
  return [r.materialTier ? `T${r.materialTier}` : null, r.weightClass, forgeItemTypeLabel(r)].filter(Boolean).join(' · ')
}
function moneyClass(v: number | null | undefined): string { return v == null ? '' : v < 0 ? 'money--loss' : 'money--gain' }
function signed(v: number | null | undefined): string { return v == null ? '—' : `${v > 0 ? '+' : ''}${formatCopper(v)}` }
function currencyDetail(c: ForgeCurrency): string {
  if (c.owned === null) return `need ${c.needed}, wallet unknown`
  return c.missing === null ? `need ${c.needed}, have ${c.owned}` : `need ${c.needed}, have ${c.owned}, missing ${c.missing}`
}

async function open(r: ForgeRecipeRow): Promise<void> {
  listScroll.value = window.scrollY
  focusedRecipe.value = r.recipeId
  f.open(r.recipeId)
  await nextTick()
  window.scrollTo?.(0, 0)
}
async function back(): Promise<void> {
  f.back()
  await nextTick()
  window.scrollTo?.(0, listScroll.value)
  list.value?.querySelector<HTMLElement>(`[data-recipe-id="${focusedRecipe.value}"]`)?.focus()
}
</script>

<template>
  <section class="screen forge" data-test="forge-screen">
    <PageHeader heading="Mystic Forge" intro="Browse Mystic Forge recipes and plan one selected project from your account." />
    <p v-if="f.preferencesUnavailable.value" role="status" class="forge-note" data-test="forge-preferences-unavailable">Settings are kept for this visit; browser storage is unavailable.</p>

    <!-- The list stays mounted while a project is open so its filters, scroll and focus survive. -->
    <div v-show="!detail" ref="list" class="forge-overview" data-test="forge-overview">
      <ForgeBrowseFilters :category="f.tab.value" :subtype="f.subtype.value" :subtypes="f.subtypeOptions.value"
        :rarity="f.rarity.value" :rarities="f.rarityOptions.value" :query="f.query.value"
        @category="f.setTab($event)" @subtype="f.setSubtype($event)" @rarity="f.setRarity($event)" @query="f.setQuery($event)" />
      <p v-if="f.catalogueLoading.value && f.catalogue.value === null" role="status">Loading recipes…</p>
      <p v-if="f.catalogueError.value" role="alert" data-test="forge-catalogue-error">{{ f.catalogueError.value }}
        <button type="button" @click="f.retryCatalogue()">Try again</button></p>
      <p v-if="f.catalogueStalled.value" role="status" data-test="forge-catalogue-stalled">
        Recipe details are still being completed in the background, but automatic checking has stopped. Your rows, search and tab are kept.
        <button type="button" data-test="forge-catalogue-retry" @click="f.retryCatalogue()">Retry</button></p>
      <div v-if="f.catalogue.value" id="forge-results" class="forge-results">
        <p class="forge-count" data-test="forge-count" aria-live="polite">{{ f.filtered.value.length }} items</p>
        <p v-if="!f.filtered.value.length" class="forge-note">No matching recipes.</p>
        <ul class="forge-list">
          <li v-for="r in f.visible.value" :key="r.recipeId">
            <button type="button" class="forge-row" :data-recipe-id="r.recipeId" data-test="forge-row" @click="open(r)">
              <span class="forge-row__header">
                <ItemIcon :icon-url="r.iconUrl" :item-id="r.outputItemId" :size="48" />
                <span class="forge-row__main">
                  <strong :data-rarity="r.rarity" :style="{ color: itemRarityColor(r.rarity) }" :title="r.name ?? `Item ${r.outputItemId}`">{{ r.name ?? `Item ${r.outputItemId}` }}</strong>
                  <span class="forge-row__note" :title="recipeNote(r)" data-test="forge-row-note">{{ recipeNote(r) }}</span>
                </span>
              </span>
              <span v-if="r.outcome !== 'REFERENCE_RANDOM' && (r.buyUnitCopper != null || r.sellUnitCopper != null)" class="forge-row__prices" aria-label="Trading Post prices" data-test="forge-row-prices">
                <span v-if="r.sellUnitCopper != null" class="forge-row__price"><span>Instant buy</span><CoinAmount :copper="r.sellUnitCopper" /></span>
                <span v-if="r.buyUnitCopper != null" class="forge-row__price"><span>Instant sell</span><CoinAmount :copper="r.buyUnitCopper" /></span>
              </span>
            </button>
          </li>
        </ul>
        <button v-if="f.filtered.value.length > f.visible.value.length" type="button" data-test="forge-more" @click="f.showMore()">
          Show more ({{ f.filtered.value.length - f.visible.value.length }} more)
        </button>
        <TradingPostPriceDisclaimer />
      </div>
    </div>

    <div v-if="detail" class="forge-detail" data-test="forge-detail">
      <button type="button" data-test="forge-back" @click="back()">← All Mystic Forge recipes</button>
      <p v-if="f.catalogueStalled.value || (f.catalogueError.value && f.catalogue.value?.completing === true)" role="status"
        data-test="forge-detail-catalogue-retry">
        Recipe details are still being completed in the background{{ f.catalogueStalled.value ? ', but automatic checking has stopped' : '' }}.
        Your selection, target and settings are kept.
        <button type="button" data-test="forge-detail-catalogue-retry-button" @click="f.retryCatalogue()">Retry</button></p>
      <header v-if="row && !projectLayout" class="forge-detail__title">
        <ItemIcon :icon-url="row.iconUrl" :item-id="row.outputItemId" :size="40" loading="eager" />
        <h2>{{ row.name ?? `Item ${row.outputItemId}` }}</h2>
        <a v-if="row.wikiUrl" :href="row.wikiUrl" target="_blank" rel="noopener noreferrer">Wiki</a>
      </header>
      <ForgeItemSummary v-if="projectLayout && row && row.category !== 'MATERIALS'" :key="row.outputItemId" :row="row" />
      <ForgeRecipePaths v-if="!projectLayout && row && f.selectedPaths.value.length > 1" :paths="f.selectedPaths.value" :selected="row.recipeId" @select="f.selectPath($event)" />
      <label v-if="!projectLayout" class="forge-target">
        {{ batchTarget ? 'Batches to run (output varies)' : 'Desired total output' }}
        <input type="text" inputmode="numeric" :value="f.targetText.value" data-test="forge-target"
          :aria-invalid="!targetValid" @input="f.setTarget(($event.target as HTMLInputElement).value)" />
      </label>
      <p v-if="!targetValid" role="alert" class="forge-missing">Enter a whole number from 1 to 1,000,000,000.</p>
      <p v-if="f.projectLoading.value" role="status" data-test="forge-loading">Planning…</p>
      <p v-if="f.sourceRefreshing.value" role="status" data-test="forge-refreshing">Synchronizing your account data for this project…</p>
      <p v-else-if="f.pending.value && !f.stalled.value && !f.sourceError.value" role="status" data-test="forge-pending">Account data is not available yet. It is being refreshed; this page will check again shortly.</p>
      <p v-if="f.sourceError.value || f.stalled.value" role="alert" data-test="forge-source-failed">
        {{ f.sourceError.value ?? 'Account data is still not available; automatic checking has stopped.' }}
        <button type="button" data-test="forge-retry" @click="f.retry()">Retry</button>
      </p>
      <p v-if="f.projectError.value" role="alert" data-test="forge-error">{{ f.projectError.value }}
        <button v-if="!f.sourceError.value && !f.stalled.value" type="button" data-test="forge-error-retry" @click="f.retry()">Retry</button></p>

      <template v-if="project">
        <p v-if="project.freshness?.provisional" class="forge-note" data-test="forge-provisional">Based on the last synchronized data; a refresh is pending or inputs may be stale.</p>
        <p v-if="project.note" class="forge-note" data-test="forge-note">{{ project.note }}</p>
        <p v-if="project.incompleteReason" role="status" class="forge-note" data-test="forge-incomplete">{{ project.incompleteReason }}</p>
      </template>

      <ForgeEquipmentDetail v-if="projectLayout && row" :key="row.recipeId" :row="row" :project="project" :settings="f.settings.value" @update:settings="f.setSettings($event)" @refresh="f.retry()">
        <template #item-summary>
          <ForgeItemSummary v-if="row.category === 'MATERIALS'" :key="row.outputItemId" :row="row" compact />
        </template>
        <template #recipe-path>
          <ForgeRecipePaths v-if="f.selectedPaths.value.length > 1" :paths="f.selectedPaths.value" :selected="row.recipeId" @select="f.selectPath($event)" />
        </template>
        <template #amount>
          <label class="forge-target forge-target--equipment">{{ row.category === 'MATERIALS' ? 'Desired output quantity' : 'Amount' }}
            <input type="text" inputmode="numeric" :value="f.targetText.value" data-test="forge-target"
              :aria-invalid="!targetValid" @input="f.setTarget(($event.target as HTMLInputElement).value)" />
          </label>
        </template>
      </ForgeEquipmentDetail>

      <template v-if="project && !projectLayout">
        <section class="forge-summary" data-test="forge-summary">
          <h3>Cost</h3>
          <p data-test="forge-known-gold">Known remaining gold cost{{ referenceOnly ? ' of the inputs' : '' }}: <strong class="numeric">{{ formatCopper(project.knownGoldCopper) }}</strong>
            <template v-if="project.goldCostPartial"> (partial: {{ project.complete ? 'some requirements are unpriced' : 'the plan is incomplete, so this is only the priced part' }})</template></p>
          <p v-if="project.unpricedItems.length" data-test="forge-unpriced">Unpriced: {{ project.unpricedItems.map((i) => i.name ?? `Item ${i.itemId}`).join(', ') }}</p>
          <p v-if="row?.outcome === 'EXPECTED' && row.expectedMax !== null" data-test="forge-yield">Per batch: {{ row.outputCount }}–{{ row.expectedMax }}, expected {{ row.expectedMean ?? '?' }}. Only the guaranteed minimum counts as stock.</p>
          <p v-if="project.guaranteedOutput !== null && !referenceOnly" data-test="forge-output">Guaranteed output: {{ project.guaranteedOutput }}</p>
          <p v-else-if="project.batches > 0" data-test="forge-batches">{{ referenceOnly ? 'Inputs for ' : '' }}{{ project.batches }} requested batch{{ project.batches === 1 ? '' : 'es' }}</p>
        </section>

        <section v-if="project.currencies.length" data-test="forge-currencies">
          <h3>Currencies</h3>
          <p v-if="!project.walletKnown" class="forge-note">Wallet unknown: balances are not available.</p>
          <ul><li v-for="c in project.currencies" :key="c.currencyId" data-test="forge-currency">
            <ItemIcon :icon-url="c.iconUrl" :item-id="null" :size="20" />
            <a v-if="c.wikiUrl" :href="c.wikiUrl" target="_blank" rel="noopener noreferrer">{{ c.name }}</a><template v-else>{{ c.name }}</template>
            — {{ currencyDetail(c) }}
          </li></ul>
        </section>

        <section v-if="project.notes" data-test="forge-notes">
          <h3>Research Notes</h3>
          <p data-test="forge-notes-needed">Needed {{ project.notes.needed }}<template v-if="project.notes.walletKnown">; wallet used {{ project.notes.walletUsed }}, missing {{ project.notes.shortfall }} before production.</template><template v-else>; wallet unknown, so no production count or deficit is claimed.</template></p>
          <p v-if="project.notes.ownedContainers > 0" data-test="forge-notes-owned">Already owned: {{ project.notes.ownedContainers }} Peer Reviewed Research to open = {{ project.notes.ownedNotes }} Notes.</p>
          <p v-if="project.notes.containersCrafted > 0" data-test="forge-notes-production">Planned work{{ project.notes.producerLocked ? ' (recipe locked)' : '' }}: craft {{ project.notes.containersCrafted }} Peer Reviewed Research (150 Notes each) = {{ project.notes.producedNotes }} Notes.</p>
          <p v-if="project.notes.acquireContainers > 0" class="forge-missing" data-test="forge-notes-acquire">Still to acquire: {{ project.notes.acquireContainers }} Peer Reviewed Research with no available producer; {{ project.notes.uncoveredNotes }} Notes remain uncovered.</p>
          <p v-if="project.notes.surplus > 0" data-test="forge-notes-surplus">{{ project.notes.surplus }} Notes left over after covering the shortfall.</p>
          <p v-if="project.notes.walletUsed" data-test="forge-notes-estimate">
            Wallet Notes value: <template v-if="project.notes.estimateCopper !== null">≈ {{ formatCopper(project.notes.estimateCopper) }} <ResearchNoteEstimatePopup :estimate="noteEstimate(project.notes)" /></template>
            <template v-else>unavailable ({{ project.notes.estimateIssue }})</template>
          </p>
        </section>

        <section v-if="project.economics && showEconomics" data-test="forge-economics">
          <h3>{{ project.economics.conditional ? 'Estimated conditional economics' : 'Economics' }}</h3>
          <p class="forge-note">Direct-input buy-all comparison for the whole project; {{ project.economics.fixedOutput ? 'output is fixed' : 'an estimate, not a guarantee' }}. Excludes unpriced inputs and currencies.</p>
          <dl class="forge-dl">
            <dt>Per-item bid quote</dt><dd data-test="forge-unit-quote">{{ quote(project.economics.outputUnitSellCopper) }}</dd>
            <template v-if="project.economics.expectedGrossProceedsCopper !== null">
              <dt>{{ project.economics.fixedOutput ? 'Total project proceeds' : `Estimated total proceeds (${project.batches} batch${project.batches === 1 ? '' : 'es'})` }}</dt>
              <dd data-test="forge-total-proceeds">{{ formatCopper(project.economics.expectedGrossProceedsCopper) }}</dd>
              <dt>Total Trading Post fees</dt><dd>{{ quote(project.economics.feeCopper) }}</dd>
              <dt>Total net proceeds</dt><dd>{{ quote(project.economics.expectedNetProceedsCopper) }}</dd>
            </template>
            <dt>Gold inputs</dt><dd>{{ formatCopper(project.economics.goldInputCopper) }}</dd>
            <template v-if="project.economics.marginCopper !== null">
              <dt>{{ project.economics.fixedOutput ? 'Margin' : 'Estimated margin' }}<template v-if="project.economics.conditional"> (conditional)</template></dt>
              <dd data-test="forge-margin" :class="['money', moneyClass(project.economics.marginCopper)]">{{ signed(project.economics.marginCopper) }}</dd>
            </template>
          </dl>
          <p v-if="project.economics.expectedGrossProceedsCopper === null" class="forge-note" data-test="forge-no-comparison">
            No Trading Post sell quote for the output, so no proceeds or margin comparison is shown. Use the known remaining costs, unpriced requirements and path below.</p>
          <p v-for="c in project.economics.currencyInputs" :key="c.currencyId" class="forge-note">Plus {{ c.needed }} {{ c.name }} (not priced in gold)</p>
        </section>
        <section v-if="project.seed" data-test="forge-seed">
          <h3>Seed reuse</h3>
          <p>External seed units {{ project.seed.externalSeedUnits }}, reused {{ project.seed.reusedSeedUnits }}.
            Guaranteed net {{ project.seed.netGuaranteedOutput }} (gross {{ project.seed.grossGuaranteedOutput }});
            expected net ≈ {{ project.seed.netExpectedOutput }} (estimate).</p>
        </section>

        <section v-if="project.shopping.length" data-test="forge-shopping">
          <h3>Shopping</h3>
          <ul><li v-for="s in project.shopping" :key="`${s.source}-${s.item.itemId}`" data-test="forge-shopping-line">
            <ItemIcon :icon-url="s.item.iconUrl" :item-id="s.item.itemId" :size="20" />
            {{ s.item.name ?? `Item ${s.item.itemId}` }} × {{ s.toAcquire }} —
            <template v-if="s.source === 'VENDOR'">
              <span data-test="forge-shopping-vendor">vendor purchase ({{ s.purchases }} {{ s.purchases === 1 ? 'buy' : 'buys' }})</span><template v-if="s.knownCopper !== null"> for {{ formatCopper(s.knownCopper) }}</template><template v-for="c in s.currencyCosts" :key="c.currencyId"> for {{ c.count }} {{ c.name }}</template><template v-if="s.hint"> — {{ s.hint }}</template>
            </template>
            <template v-else-if="s.unitCopper === null">price unavailable</template>
            <template v-else>{{ formatCopper(s.unitCopper) }} each = {{ formatCopper(s.knownCopper) }}</template>
          </li></ul>
        </section>

        <section v-if="project.tree" data-test="forge-tree">
          <h3>How to make it</h3>
          <ul class="forge-tree"><MysticForgeNode :node="project.tree" :depth="0" :reference="referenceOnly" /></ul>
        </section>
      </template>
    </div>
  </section>
</template>

<style scoped>
.forge-detail, .forge-detail > section { display: flex; flex-direction: column; gap: var(--space-3); }
.forge-detail > section { padding: var(--space-3) 0; }
.forge-detail h3, .forge-detail p, .forge-detail dl { margin: 0; }
.forge-overview, .forge-results { display: flex; flex-direction: column; gap: var(--space-3); }
.forge-list { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); grid-auto-rows: 12rem; gap: var(--space-4); list-style: none; padding: 0; margin: 0.5rem 0; }
.forge-list > li { display: flex; min-width: 0; }
.forge-row { display: flex; flex-direction: column; align-items: stretch; justify-content: flex-start; gap: var(--space-3); width: 100%; height: 100%; min-width: 0; padding: var(--space-4); text-align: left; background: var(--color-raised); border: 1px solid var(--color-border); border-radius: var(--radius-panel); transition: border-color 150ms, background-color 150ms; }
.forge-row:hover { border-color: var(--color-secondary); background: color-mix(in srgb, var(--color-raised) 94%, var(--color-secondary)); }
.forge-row__header { display: grid; grid-template-columns: 48px minmax(0, 1fr); align-items: start; gap: var(--space-3); min-width: 0; }
.forge-row__main { display: flex; flex-direction: column; gap: var(--space-1); min-width: 0; }
.forge-row__main strong { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; overflow-wrap: anywhere; line-height: 1.35; }
.forge-row__note { color: var(--color-muted); font-size: var(--text-sm); line-height: 1.4; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.forge-row__prices { display: flex; flex-direction: column; gap: var(--space-1); margin-top: auto; padding-top: var(--space-2); border-top: 1px solid var(--color-border); font-size: var(--text-sm); }
.forge-row__price { display: flex; justify-content: space-between; align-items: baseline; gap: var(--space-2); }
.forge-row__price > span:first-child { color: var(--color-muted); white-space: nowrap; }
.forge-row__price :deep(.coin-amount) { gap: 6px; }
.forge-row__price :deep(.coin) { width: 14px; height: 14px; }
.forge-detail { max-width: 100%; }
.forge-detail__title { display: flex; align-items: center; gap: 0.75rem; }
.forge-target input { width: 10ch; }
.forge-target--equipment { display: flex; flex-direction: column; gap: var(--space-2); }
.forge-target--equipment input { width: 100%; }
.forge-dl { display: grid; grid-template-columns: minmax(12rem, max-content) 1fr; gap: 0.25rem 1rem; }
.forge-note { color: var(--color-muted); white-space: normal; min-width: 0; }
.forge-missing { color: var(--color-warning); }
.forge-tree { padding: 0; }
</style>
