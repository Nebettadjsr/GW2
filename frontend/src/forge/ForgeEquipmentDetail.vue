<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { ForgeBaseIngredient, ForgeNode, ForgeNotes, ForgeProject, ForgeRecipePart, ForgeRecipeRow, ForgeSettings, NoteEstimate } from '@/api/types'
import { formatCopper } from '@/crafting/formatCopper'
import ResearchNoteEstimatePopup from '@/crafting/ResearchNoteEstimatePopup.vue'
import ItemIcon from '@/items/ItemIcon.vue'
import ForgeCraftingSteps from './ForgeCraftingSteps.vue'
import ForgeMerchantDetails from './ForgeMerchantDetails.vue'
import ForgeRecipeTable from './ForgeRecipeTable.vue'

const props = defineProps<{ row: ForgeRecipeRow; project: ForgeProject | null; settings: ForgeSettings }>()
const emit = defineEmits<{ 'update:settings': [patch: Partial<ForgeSettings>]; refresh: [] }>()
// Reload an open preview once if it still holds the previous response shape.
onMounted(() => {
  if (props.project && (props.project.recipeTree === undefined || props.project.ownedItems === undefined || props.project.materialProgress === undefined || props.project.baseIngredients === undefined || props.project.craftingSteps === undefined
    || (props.project.recipeTree !== null && (props.project.recipeTree.acquired === undefined
      || currencyProgressMissing(props.project.recipeTree)))
    || props.project.ownedItems.some(entry => entry.locations === undefined || entry.valueCopper === undefined))) emit('refresh')
})
function currencyProgressMissing(part: ForgeRecipePart): boolean {
  return part.currencies.some(currency => currency.progressPercent === undefined)
    || part.children.some(currencyProgressMissing)
}
function pick(event: Event): string { return (event.target as HTMLSelectElement).value }
function yesNo(event: Event): boolean { return pick(event) === 'YES' }
const comparison = computed(() => props.project?.comparison ?? null)
const materialDetail = computed(() => props.row.category === 'MATERIALS')
const batchTarget = computed(() => props.row.outcome !== 'FIXED')
const referenceOnly = computed(() => props.row.outcome === 'REFERENCE_RANDOM')
const baseIngredients = ref<ForgeBaseIngredient[]>([])
// Keep the selected recipe visible while a quantity or account-setting change recalculates its plan.
watch(() => props.project?.baseIngredients, ingredients => {
  if (ingredients) baseIngredients.value = ingredients
}, { immediate: true })
const materialProgress = computed(() => props.project?.materialProgress ?? null)
const materialProgressText = computed(() => {
  const progress = materialProgress.value
  if (!progress) return '—'
  return `${progress.partial ? '≥' : progress.estimated ? '≈' : ''}${progress.percent.toLocaleString(undefined, { maximumFractionDigits: 1 })}%`
})
const materialProgressDescription = computed(() => {
  const progress = materialProgress.value
  return `Material readiness: owned items, their components and wallet purchases, weighted equally at each recipe level.${progress?.estimated ? ' Includes estimated Mystic Clover supplies.' : ''}${progress?.partial ? ' A lower bound; some requirements or wallet balances are unknown.' : ''}`
})
const reductionHelp = ref(false)
const owned = computed(() => [...(props.project?.ownedItems ?? [])]
  .sort((a, b) => (a.item.name ?? '').localeCompare(b.item.name ?? '')))
const shopping = computed(() => {
  const merchantSteps = new Set<number>()
  function visit(node: ForgeNode): void {
    if (node.recipeSource === 'VENDOR' && node.recipeId !== null && node.crafts > 0 && node.hint !== null) {
      merchantSteps.add(node.item.itemId)
    }
    node.children.forEach(visit)
  }
  if (!materialDetail.value && props.project?.tree) visit(props.project.tree)
  return props.project?.shopping.filter(line => line.source !== 'VENDOR' || !merchantSteps.has(line.item.itemId)) ?? []
})
const missingCurrencies = computed(() => props.project?.currencies.filter(c => c.missing === null || c.missing > 0) ?? [])
const economics = computed(() => props.project?.economics)
function money(value: number | null | undefined): string { return value == null ? '—' : formatCopper(value) }
function signed(value: number | null): string { return value === null ? '—' : `${value > 0 ? '+' : ''}${formatCopper(value)}` }
function amount(value: string): string { return Number(value).toLocaleString(undefined, { maximumFractionDigits: 2 }) }
function noteEstimate(notes: ForgeNotes): NoteEstimate {
  return { perCraftNotes: 0, totalNotes: notes.walletUsed ?? 0, perCraftCopper: 0, totalCopper: notes.estimateCopper ?? 0,
    basisRecipeId: notes.basisRecipeId, variants: notes.variants }
}
</script>

<template>
  <div class="equipment-grid" :class="{ 'equipment-grid--materials': materialDetail, 'equipment-grid--batches': batchTarget }" data-test="forge-equipment-layout">
    <div v-if="materialDetail" class="description-box"><slot name="item-summary" /></div>
    <section v-if="materialDetail" class="project-box base-recipe-box" data-test="forge-base-recipe">
      <div class="box-heading"><h3>Base recipe</h3><span class="muted">1 batch</span></div>
      <ul class="base-recipe">
        <li v-for="(ingredient, index) in baseIngredients" :key="`${ingredient.item.itemId}-${index}`" data-test="forge-base-ingredient">
          <span class="numeric">{{ ingredient.quantity.toLocaleString() }}</span>
          <ItemIcon :icon-url="ingredient.item.iconUrl" :item-id="ingredient.item.itemId" :size="24" />
          <a v-if="ingredient.item.wikiUrl" :href="ingredient.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ ingredient.item.name ?? `Item ${ingredient.item.itemId}` }}</a>
          <span v-else>{{ ingredient.item.name ?? `Item ${ingredient.item.itemId}` }}</span>
        </li>
      </ul>
    </section>
    <section class="project-box settings-box" data-test="forge-settings">
      <div class="box-heading"><h3>Calculation settings</h3><span v-if="!materialDetail" class="muted">Shared across Mystic Forge recipes</span></div>
      <div class="settings-grid">
        <label>Material price<select :value="settings.priceMode" data-test="forge-setting-price" @change="emit('update:settings', { priceMode: pick($event) as ForgeSettings['priceMode'] })"><option value="INSTANT_BUY">Instant buy</option><option value="BUY_ORDER">Buy order</option></select></label>
        <label>Use own materials<select :value="settings.useOwn ? 'YES' : 'NO'" data-test="forge-setting-use-own" @change="emit('update:settings', { useOwn: yesNo($event) })"><option value="YES">Yes</option><option value="NO">No</option></select></label>
        <label>Value own materials<select :value="settings.valueOwn ? 'YES' : 'NO'" data-test="forge-setting-value-own" @change="emit('update:settings', { valueOwn: yesNo($event) })"><option value="YES">Yes</option><option value="NO">No</option></select></label>
        <label v-if="!materialDetail">Daily cooldowns<select :value="settings.daily" data-test="forge-setting-daily" @change="emit('update:settings', { daily: pick($event) as ForgeSettings['daily'] })"><option value="CRAFT">Craft</option><option value="BUY">Buy</option></select></label>
        <label v-if="!materialDetail">Mystic Forge promotions<select :value="settings.promotions ? 'YES' : 'NO'" data-test="forge-setting-promotions" @change="emit('update:settings', { promotions: yesNo($event) })"><option value="YES">Allow</option><option value="NO">Do not allow</option></select></label>
        <div class="amount-control"><slot name="amount" /></div>
        <div v-if="materialDetail" class="batch-estimate" :title="batchTarget ? 'Based on average yield and seed reuse; actual output varies.' : undefined">
          <span>{{ batchTarget ? 'Estimated batches' : 'Batches' }}</span>
          <output class="numeric" data-test="forge-estimated-batches">{{ project ? `${batchTarget ? '≈' : ''}${project.batches.toLocaleString()}` : '—' }}</output>
        </div>
        <div v-if="!materialDetail" class="reduction-control" :class="{ 'control-disabled': batchTarget }" :title="batchTarget ? 'Owned output does not reduce the number of Forge batches.' : undefined">
          <label for="forge-reduce">Reduce amount by owned items</label>
          <div class="reduction-input">
            <select id="forge-reduce" :value="settings.reduce" :disabled="batchTarget" data-test="forge-setting-reduce" @change="emit('update:settings', { reduce: pick($event) as ForgeSettings['reduce'] })"><option value="NO">No</option><option value="YES">Yes</option><option value="TRADABLE">Tradable only</option><option value="UNTRADABLE">Untradable only</option></select>
            <div class="help" @keydown.esc="reductionHelp = false">
              <button type="button" aria-label="Explain reducing the amount by owned items" :aria-expanded="reductionHelp" aria-controls="forge-reduction-help" @click="reductionHelp = !reductionHelp">?</button>
              <div v-if="reductionHelp" id="forge-reduction-help" class="help-panel">
                <dl><dt>No</dt><dd>Craft the full amount entered.</dd><dt>Yes</dt><dd>Reduce it by the items already owned.</dd><dt>Tradable only</dt><dd>Count owned items that can be sold.</dd><dt>Untradable only</dt><dd>Count account-bound and soulbound items.</dd></dl>
              </div>
            </div>
          </div>
        </div>
        <div class="recipe-path-control"><slot name="recipe-path" /></div>
      </div>
    </section>

    <template v-if="project">
      <section class="project-box costs-box" data-test="forge-summary">
        <h3>Costs &amp; comparison</h3>
        <dl v-if="batchTarget" class="cost-lines yield-lines" data-test="forge-yield">
          <template v-if="!referenceOnly">
            <dt>Output per batch<small>variable yield</small></dt><dd class="numeric">{{ row.outputCount }}–{{ row.expectedMax ?? '?' }}<small>≈{{ row.expectedMean ? amount(row.expectedMean) : '?' }} expected</small></dd>
            <template v-if="project.seed">
              <dt>Expected net output<small>after seed reuse</small></dt><dd class="numeric" data-test="forge-expected-output">≈{{ amount(project.seed.netExpectedOutput) }}</dd>
            </template>
          </template>
          <template v-else><dt>Output</dt><dd>Random</dd></template>
        </dl>
        <dl class="cost-lines">
          <dt>Remaining gold cost</dt><dd class="money--loss numeric" data-test="forge-known-gold">{{ money(project.knownGoldCopper) }}<small v-if="project.goldCostPartial">partial<template v-if="!project.complete"> · the plan is incomplete</template></small></dd>
          <dt>Own materials value<small v-if="project.ownValue?.unpricedItems.length">{{ project.ownValue.unpricedItems.length }} owned item kinds unpriced</small></dt><dd class="money--loss numeric" data-test="forge-own-value">{{ project.ownValue ? money(project.ownValue.knownCopper) : 'Not valued' }}</dd>
          <dt>Own currencies value</dt><dd class="money--loss numeric">{{ project.notes?.walletUsed ? money(project.notes.estimateCopper) : '—' }}<ResearchNoteEstimatePopup v-if="project.notes?.walletUsed && project.notes.estimateCopper !== null" :estimate="noteEstimate(project.notes)" /></dd>
          <template v-if="!batchTarget">
          <dt class="cost-separator">Buy on Trading Post<small>per item · {{ project.settings.priceMode === 'BUY_ORDER' ? 'buy order' : 'instant buy' }}</small></dt><dd class="cost-separator money--loss numeric" data-test="forge-unit-buy">{{ money(comparison?.unitBuyCopper) }}</dd>
          <dt>Sell on Trading Post<small>per item · instant sell</small></dt><dd class="money--gain numeric">{{ money(comparison?.unitSellCopper) }}</dd>
          <dt>Buying the whole amount<small>project total</small></dt><dd class="money--loss numeric">{{ money(comparison?.outputBuyTotalCopper) }}</dd>
          <dt class="cost-separator">Savings compared to buying<small v-if="comparison?.excludesOwnValue">excludes own-material value</small><small v-else-if="comparison?.conditional">conditional · currencies or estimates excluded</small></dt><dd class="cost-separator numeric" data-test="forge-savings" :class="{ muted: comparison?.savingsCopper == null }">{{ signed(comparison?.savingsCopper ?? null) }}</dd>
          <dt class="cost-total">{{ economics?.conditional || !project.settings.valueOwn ? 'Conditional margin' : 'Profit' }}<small>after 15% TP fees{{ project.settings.valueOwn ? '' : ' · excludes own-material value' }}</small></dt>
          <dd class="cost-total numeric" data-test="forge-margin" :class="{ 'money--gain': economics?.marginCopper != null && economics.marginCopper >= 0, 'money--loss': economics?.marginCopper != null && economics.marginCopper < 0 }">{{ signed(economics?.marginCopper ?? null) }}</dd>
          </template>
          <template v-else-if="!referenceOnly && economics">
            <dt class="cost-separator">Estimated sale proceeds<small>whole batch plan</small></dt><dd class="cost-separator money--gain numeric">{{ money(economics.expectedGrossProceedsCopper) }}</dd>
            <dt>Trading Post fees</dt><dd class="money--loss numeric">{{ money(economics.feeCopper) }}</dd>
            <dt>Estimated net proceeds</dt><dd class="money--gain numeric">{{ money(economics.expectedNetProceedsCopper) }}</dd>
            <dt class="cost-total">Estimated {{ economics.conditional || !project.settings.valueOwn ? 'conditional margin' : 'profit' }}<small>after 15% TP fees{{ project.settings.valueOwn ? '' : ' · excludes own-material value' }}</small></dt>
            <dd class="cost-total numeric" data-test="forge-margin" :class="{ 'money--gain': economics.marginCopper != null && economics.marginCopper >= 0, 'money--loss': economics.marginCopper != null && economics.marginCopper < 0 }">{{ signed(economics.marginCopper) }}</dd>
          </template>
        </dl>
        <p v-if="project.clover" class="muted" data-test="forge-clover-estimate">Estimated: {{ project.clover.attempts }} Mystic Forge attempts for {{ project.clover.missing }} missing Clovers (about {{ project.clover.percent }}% each); not guaranteed.</p>
        <p v-if="project.guaranteedOutput !== null && !referenceOnly" class="output-note" data-test="forge-output">{{ batchTarget ? 'Guaranteed net output' : 'Guaranteed output' }}: {{ project.guaranteedOutput.toLocaleString() }}</p>
        <p v-if="project.seed" class="output-note" data-test="forge-seed">Seed: {{ project.seed.externalSeedUnits.toLocaleString() }} supplied · {{ project.seed.reusedSeedUnits.toLocaleString() }} reused</p>
        <details v-if="project.notes" class="notes-detail" data-test="forge-notes">
          <summary>Research Notes · {{ project.notes.needed }} needed</summary>
          <p data-test="forge-notes-needed">Needed {{ project.notes.needed }}<template v-if="project.notes.walletKnown">; wallet used {{ project.notes.walletUsed }}, missing {{ project.notes.shortfall }} before production.</template><template v-else>; wallet unknown.</template></p>
          <p v-if="project.notes.ownedContainers > 0" data-test="forge-notes-owned">Already owned: {{ project.notes.ownedContainers }} Peer Reviewed Research to open = {{ project.notes.ownedNotes }} Notes.</p>
          <p v-if="project.notes.containersCrafted > 0" data-test="forge-notes-production">Planned work{{ project.notes.producerLocked ? ' (recipe locked)' : '' }}: craft {{ project.notes.containersCrafted }} Peer Reviewed Research (150 Notes each) = {{ project.notes.producedNotes }} Notes.</p>
          <p v-if="project.notes.acquireContainers > 0" data-test="forge-notes-acquire">Acquire {{ project.notes.acquireContainers }} Peer Reviewed Research; {{ project.notes.uncoveredNotes }} Notes remain uncovered.</p>
          <p v-if="project.notes.surplus > 0" data-test="forge-notes-surplus">{{ project.notes.surplus }} Notes left over.</p>
          <p v-if="project.notes.walletUsed" data-test="forge-notes-estimate">Wallet Notes value: {{ money(project.notes.estimateCopper) }}<span v-if="project.notes.estimateIssue"> ({{ project.notes.estimateIssue }})</span></p>
        </details>
      </section>

      <section class="project-box owned-box" data-test="forge-owned">
        <div class="box-heading"><h3>Using from your account</h3></div>
        <p class="muted">{{ settings.useOwn ? 'Including reserved materials.' : 'Own materials are not used.' }}</p>
        <div class="box-list">
          <ul class="item-list">
            <li v-for="entry in owned" :key="entry.item.itemId" data-test="forge-owned-line">
              <strong class="numeric money--gain">{{ entry.count.toLocaleString() }}</strong>
              <ItemIcon :icon-url="entry.item.iconUrl" :item-id="entry.item.itemId" :size="28" />
              <div class="item-description"><a v-if="entry.item.wikiUrl" :href="entry.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ entry.item.name ?? `Item ${entry.item.itemId}` }}</a><span v-else>{{ entry.item.name ?? `Item ${entry.item.itemId}` }}</span>
                <div class="item-meta"><small>{{ entry.locations?.join(' · ') }}</small><small class="item-value" data-test="forge-owned-value" :title="settings.valueOwn && entry.valueCopper == null ? 'Value unavailable' : undefined">{{ money(settings.valueOwn ? entry.valueCopper : 0) }}</small></div>
              </div>
            </li>
            <li v-for="currency in project.currencies" :key="`currency-${currency.currencyId}`" data-test="forge-currency" :title="currency.owned === null ? 'Wallet unknown' : `In wallet: ${currency.owned.toLocaleString()}`">
              <strong class="numeric" :class="{ 'money--gain': currency.missing === 0 }">{{ currency.needed.toLocaleString() }}</strong>
              <ItemIcon :icon-url="currency.iconUrl" :item-id="null" :size="28" />
              <div class="item-description"><a v-if="currency.wikiUrl" :href="currency.wikiUrl" target="_blank" rel="noopener noreferrer">{{ currency.name }}</a><span v-else>{{ currency.name }}</span><small>{{ currency.owned === null ? 'Wallet balance unknown' : `${currency.owned.toLocaleString()} in wallet` }}</small></div>
            </li>
          </ul>
          <p v-if="!owned.length && !project.currencies.length" class="muted">No owned items used in this plan.</p>
        </div>
      </section>

      <section class="project-box shopping-box" data-test="forge-shopping">
        <div class="box-heading"><h3>Shopping &amp; acquisition</h3></div>
        <div class="box-list">
          <ul class="item-list">
            <li v-for="line in shopping" :key="`${line.source}-${line.item.itemId}`" data-test="forge-shopping-line">
              <strong class="numeric">{{ line.toAcquire.toLocaleString() }}</strong>
              <ItemIcon :icon-url="line.item.iconUrl" :item-id="line.item.itemId" :size="28" />
              <div class="item-description"><a v-if="line.item.wikiUrl" :href="line.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ line.item.name ?? `Item ${line.item.itemId}` }}</a><span v-else>{{ line.item.name ?? `Item ${line.item.itemId}` }}</span>
                <ForgeMerchantDetails v-if="line.source === 'VENDOR'" :item-id="line.item.itemId" :purchase="line" :currencies="project.currencies" data-test="forge-shopping-vendor" />
                <div v-else class="item-meta">
                  <small>{{ line.unitCopper === null ? 'Acquisition needed' : 'Trading Post' }}</small>
                  <small v-if="line.knownCopper !== null" class="item-value money--loss" data-test="forge-shopping-value">{{ money(line.knownCopper) }}</small>
                </div>
              </div>
            </li>
            <li v-for="currency in missingCurrencies" :key="`currency-${currency.currencyId}`" data-test="forge-shopping-currency">
              <strong class="numeric">{{ currency.missing?.toLocaleString() ?? '—' }}</strong>
              <ItemIcon :icon-url="currency.iconUrl" :item-id="null" :size="28" />
              <div class="item-description"><a v-if="currency.wikiUrl" :href="currency.wikiUrl" target="_blank" rel="noopener noreferrer">{{ currency.name }}</a><span v-else>{{ currency.name }}</span><small>{{ currency.missing === null ? `${currency.needed} needed · wallet unknown` : 'Still to acquire' }}</small></div>
            </li>
          </ul>
          <p v-if="!shopping.length && !missingCurrencies.length" class="muted">No additional shopping.</p>
        </div>
        <details v-if="project.unpricedItems.length" class="unpriced-detail"><summary>Unpriced requirements</summary><p data-test="forge-unpriced">{{ project.unpricedItems.map(item => item.name ?? `Item ${item.itemId}`).join(', ') }}</p></details>
      </section>

      <section v-if="!batchTarget" class="project-box tree-box" data-test="forge-tree">
        <div class="material-heading">
          <h3>Full material list</h3>
          <div v-if="settings.useOwn" class="overall-progress" role="progressbar" aria-label="Overall material readiness"
            :aria-valuenow="materialProgress?.percent" :aria-valuetext="materialProgressText" :aria-valuemin="0" :aria-valuemax="100"
            :style="{ '--progress': `${materialProgress?.percent ?? 0}%` }" :title="materialProgressDescription" data-test="forge-material-progress">
            <span>{{ materialProgressText }}</span>
          </div>
        </div>
        <div class="path-content"><ForgeRecipeTable v-if="project.recipeTree" :tree="project.recipeTree" :show-progress="settings.useOwn" /><p v-else class="muted">Recipe path unavailable.</p></div>
      </section>
      <section v-if="!materialDetail" class="project-box steps-box">
        <h3>Crafting steps</h3>
        <div class="path-content"><ForgeCraftingSteps :steps="project.craftingSteps ?? []" :shopping="project.shopping" :currencies="project.currencies" :batch-target="batchTarget" /></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* Rearrange the named areas here while pairing; each box owns its contents independently. */
.equipment-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  grid-template-areas:
    "settings settings settings settings settings settings settings settings settings settings settings settings"
    "costs costs costs costs owned owned owned owned shopping shopping shopping shopping"
    "tree tree tree tree tree tree tree steps steps steps steps steps";
  align-items: stretch;
  gap: var(--space-5);
}
.settings-box { grid-area: settings; }
.equipment-grid--materials {
  grid-template-areas:
    "description description description description base base base settings settings settings settings settings"
    "costs costs costs costs owned owned owned owned shopping shopping shopping shopping"
    "tree tree tree tree tree tree tree tree tree tree tree tree";
}
.equipment-grid--materials.equipment-grid--batches {
  grid-template-areas:
    "description description description description base base base settings settings settings settings settings"
    "costs costs costs costs owned owned owned owned shopping shopping shopping shopping";
}
.description-box { grid-area: description; display: flex; min-width: 0; }
.description-box :deep(.item-summary) { flex: 1; min-width: 0; }
.base-recipe-box { grid-area: base; }
.base-recipe { display: grid; gap: var(--space-2); list-style: none; padding: 0; margin: 0; font-size: var(--text-sm); }
.base-recipe li { display: grid; grid-template-columns: 3ch 24px minmax(0, 1fr); align-items: center; gap: var(--space-2); }
.base-recipe .numeric { text-align: right; }
.base-recipe a, .base-recipe li > span:last-child { min-width: 0; overflow-wrap: anywhere; }
.costs-box { grid-area: costs; }
.owned-box { grid-area: owned; }
.shopping-box { grid-area: shopping; }
.tree-box { grid-area: tree; }
.steps-box { grid-area: steps; }
.project-box { min-width: 0; padding: var(--space-5); border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: var(--color-surface); display: flex; flex-direction: column; gap: var(--space-4); }
.box-heading { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); }
.material-heading { display: flex; align-items: center; gap: var(--space-5); }
.material-heading h3 { flex-shrink: 0; }
.overall-progress { flex: 1; min-width: 6rem; height: 1.75rem; display: grid; place-items: center; border: 1px solid var(--color-border); border-radius: var(--radius-control); background-color: var(--color-raised); background-image: linear-gradient(to left, color-mix(in srgb, var(--color-success) 32%, transparent) var(--progress), transparent var(--progress)); font-size: var(--text-sm); font-variant-numeric: tabular-nums; }
.muted, small, .count { color: var(--color-muted); font-size: var(--text-sm); }
.count { font-variant-numeric: tabular-nums; }
.settings-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: var(--space-4) var(--space-5); }
.settings-grid label, .reduction-control, .amount-control { display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; }
.settings-grid select { width: 100%; min-width: 0; }
.equipment-grid--materials .settings-box { padding: var(--space-4); }
.equipment-grid--materials .settings-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-3); }
.equipment-grid--materials .settings-grid label, .equipment-grid--materials .batch-estimate > span { font-size: var(--text-sm); }
.equipment-grid--materials .recipe-path-control { grid-column: 3 / -1; }
.batch-estimate { grid-column: 2; grid-row: 2; display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; }
.batch-estimate > span { text-align: center; }
.batch-estimate output { display: flex; align-items: center; justify-content: center; min-height: 2.5rem; font-size: var(--text-lg); }
.control-disabled { opacity: .55; }
.control-disabled select { cursor: default; }
.recipe-path-control { grid-column: 4 / -1; grid-row: 2; min-width: 0; align-self: end; }
.recipe-path-control :deep(.recipe-paths) { align-items: flex-end; text-align: right; }
.recipe-path-control :deep(.recipe-paths__toggle) { justify-content: flex-end; }
.reduction-input { display: flex; align-items: center; gap: var(--space-2); }
.help { position: relative; }
.help > button { border-radius: 50%; width: 1.75rem; height: 1.75rem; padding: 0; }
.help-panel { position: absolute; right: 0; top: calc(100% + var(--space-2)); z-index: 3; width: 25rem; padding: var(--space-4); background: var(--color-raised); border: 1px solid var(--color-border); border-radius: var(--radius-panel); box-shadow: 0 8px 24px #0006; }
.help-panel dl { display: grid; grid-template-columns: max-content 1fr; gap: var(--space-2) var(--space-3); margin: 0 0 var(--space-3); }
.help-panel dt { font-weight: 600; }
.help-panel dd { margin: 0; }
.cost-lines { display: grid; grid-template-columns: minmax(0, 1fr) max-content; gap: var(--space-3) var(--space-4); margin: 0; align-items: baseline; }
.cost-lines dt, .cost-lines dd { min-width: 0; margin: 0; }
.cost-lines dd { text-align: right; }
.yield-lines { padding-bottom: var(--space-3); border-bottom: 1px solid var(--color-border); }
.cost-lines small, .item-description small { display: block; font-weight: 400; }
.cost-separator { border-top: 1px solid var(--color-border); padding-top: var(--space-4); }
.cost-total { border-top: 1px solid var(--color-border); padding-top: var(--space-4); font-weight: 700; }
.money--loss { color: var(--color-danger); }
.money--gain { color: var(--color-success); }
.box-list { max-height: 30rem; overflow: auto; padding-right: var(--space-1); }
.item-list { list-style: none; margin: 0; padding: 0; }
.item-list li { display: grid; grid-template-columns: 9ch 28px minmax(0, 1fr); gap: var(--space-3); align-items: start; padding: var(--space-3) 0; border-bottom: 1px solid var(--color-border); }
.item-list li:first-child { padding-top: 0; }
.item-list li:last-child { border: 0; padding-bottom: 0; }
.item-description { min-width: 0; overflow-wrap: anywhere; }
.item-meta { display: flex; justify-content: space-between; align-items: baseline; gap: var(--space-2); }
.item-meta > small:first-child { min-width: 0; }
.item-value { flex-shrink: 0; margin-left: auto; text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
.item-list strong { white-space: nowrap; text-align: right; line-height: 28px; }
.item-description > a, .item-description > span { line-height: 28px; }
details { color: var(--color-muted); font-size: var(--text-sm); }
summary { cursor: pointer; }
details p { margin-top: var(--space-2); }
.output-note { font-size: var(--text-sm); color: var(--color-muted); }
.path-content { max-height: 56rem; overflow: auto; min-width: 0; }
</style>
