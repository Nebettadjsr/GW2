<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ForgeNode, ForgeNotes, ForgeProject, ForgeRecipeRow, ForgeSettings, NoteEstimate } from '@/api/types'
import { formatCopper } from '@/crafting/formatCopper'
import ResearchNoteEstimatePopup from '@/crafting/ResearchNoteEstimatePopup.vue'
import ItemIcon from '@/items/ItemIcon.vue'
import ForgeCraftingSteps from './ForgeCraftingSteps.vue'
import MysticForgeNode from './MysticForgeNode.vue'
import { forgeAcquisitionHint } from './forgeAcquisitionHints'

const props = defineProps<{ row: ForgeRecipeRow; project: ForgeProject | null; settings: ForgeSettings }>()
const emit = defineEmits<{ 'update:settings': [patch: Partial<ForgeSettings>] }>()
function pick(event: Event): string { return (event.target as HTMLSelectElement).value }
function yesNo(event: Event): boolean { return pick(event) === 'YES' }
const comparison = computed(() => props.project?.comparison ?? null)
const reductionHelp = ref(false)
const owned = computed(() => {
  const result: { node: ForgeNode; key: string; usedFor: string | null }[] = []
  function visit(node: ForgeNode, key: string, parent: string | null): void {
    if (node.fromOwned > 0) result.push({ node, key, usedFor: parent })
    node.children.forEach((child, index) => visit(child, `${key}.${index}`, node.item.name))
  }
  if (props.project?.tree) visit(props.project.tree, 'root', null)
  return result.sort((a, b) => (a.node.item.name ?? '').localeCompare(b.node.item.name ?? ''))
})
const missingCurrencies = computed(() => props.project?.currencies.filter(c => c.missing === null || c.missing > 0) ?? [])
const economics = computed(() => props.project?.economics)
function money(value: number | null | undefined): string { return value == null ? '—' : formatCopper(value) }
function signed(value: number | null): string { return value === null ? '—' : `${value > 0 ? '+' : ''}${formatCopper(value)}` }
function noteEstimate(notes: ForgeNotes): NoteEstimate {
  return { perCraftNotes: 0, totalNotes: notes.walletUsed ?? 0, perCraftCopper: 0, totalCopper: notes.estimateCopper ?? 0,
    basisRecipeId: notes.basisRecipeId, variants: notes.variants }
}
</script>

<template>
  <div class="equipment-grid" data-test="forge-equipment-layout">
    <section class="project-box settings-box" data-test="forge-settings">
      <div class="box-heading"><h3>Calculation settings</h3><span class="muted">Applied by the server to this project only</span></div>
      <div class="settings-grid">
        <label>Material price<select :value="settings.priceMode" data-test="forge-setting-price" @change="emit('update:settings', { priceMode: pick($event) as ForgeSettings['priceMode'] })"><option value="INSTANT_BUY">Instant buy</option><option value="BUY_ORDER">Buy order</option></select></label>
        <label>Use own materials<select :value="settings.useOwn ? 'YES' : 'NO'" data-test="forge-setting-use-own" @change="emit('update:settings', { useOwn: yesNo($event) })"><option value="YES">Yes</option><option value="NO">No</option></select></label>
        <label>Value own materials<select :value="settings.valueOwn ? 'YES' : 'NO'" data-test="forge-setting-value-own" @change="emit('update:settings', { valueOwn: yesNo($event) })"><option value="YES">Yes</option><option value="NO">No</option></select></label>
        <label>Daily cooldowns<select :value="settings.daily" data-test="forge-setting-daily" @change="emit('update:settings', { daily: pick($event) as ForgeSettings['daily'] })"><option value="CRAFT">Craft</option><option value="BUY">Buy</option></select></label>
        <label>Mystic Forge promotions<select :value="settings.promotions ? 'YES' : 'NO'" data-test="forge-setting-promotions" @change="emit('update:settings', { promotions: yesNo($event) })"><option value="YES">Allow</option><option value="NO">Do not allow</option></select></label>
        <div class="reduction-control">
          <label for="forge-reduce">Reduce amount by owned items</label>
          <div class="reduction-input">
            <select id="forge-reduce" :value="settings.reduce" data-test="forge-setting-reduce" @change="emit('update:settings', { reduce: pick($event) as ForgeSettings['reduce'] })"><option value="NO">No</option><option value="YES">Yes</option><option value="TRADABLE">Tradable only</option><option value="UNTRADABLE">Untradable only</option></select>
            <div class="help" @keydown.esc="reductionHelp = false">
              <button type="button" aria-label="Explain reducing the amount by owned items" :aria-expanded="reductionHelp" aria-controls="forge-reduction-help" @click="reductionHelp = !reductionHelp">?</button>
              <div v-if="reductionHelp" id="forge-reduction-help" class="help-panel">
                <dl><dt>No</dt><dd>Craft the full amount entered.</dd><dt>Yes</dt><dd>Reduce it by the items already owned.</dd><dt>Tradable only</dt><dd>Count owned items that can be sold.</dd><dt>Untradable only</dt><dd>Count account-bound and soulbound items.</dd></dl>
              </div>
            </div>
          </div>
        </div>
        <div class="amount-control"><slot name="amount" /></div>
      </div>
    </section>

    <template v-if="project">
      <section class="project-box costs-box" data-test="forge-summary">
        <h3>Costs &amp; comparison</h3>
        <dl class="cost-lines">
          <dt>Remaining gold cost</dt><dd class="money--loss numeric" data-test="forge-known-gold">{{ money(project.knownGoldCopper) }}<small v-if="project.goldCostPartial">partial<template v-if="!project.complete"> · the plan is incomplete</template></small></dd>
          <dt>Own materials value<small v-if="project.ownValue?.unpricedItems.length">{{ project.ownValue.unpricedItems.length }} owned item kinds unpriced</small></dt><dd class="money--loss numeric" data-test="forge-own-value">{{ project.ownValue ? money(project.ownValue.knownCopper) : 'Not valued' }}</dd>
          <dt>Own currencies value</dt><dd class="money--loss numeric">{{ project.notes?.walletUsed ? money(project.notes.estimateCopper) : '—' }}<ResearchNoteEstimatePopup v-if="project.notes?.walletUsed && project.notes.estimateCopper !== null" :estimate="noteEstimate(project.notes)" /></dd>
          <dt class="cost-separator">Buy on Trading Post<small>per item · {{ project.settings.priceMode === 'BUY_ORDER' ? 'buy order' : 'instant buy' }}</small></dt><dd class="cost-separator money--loss numeric" data-test="forge-unit-buy">{{ money(comparison?.unitBuyCopper) }}</dd>
          <dt>Sell on Trading Post<small>per item · instant sell</small></dt><dd class="money--gain numeric">{{ money(comparison?.unitSellCopper) }}</dd>
          <dt>Buying the whole amount<small>project total</small></dt><dd class="money--loss numeric">{{ money(comparison?.outputBuyTotalCopper) }}</dd>
          <dt class="cost-separator">Savings compared to buying<small v-if="comparison?.excludesOwnValue">excludes own-material value</small><small v-else-if="comparison?.conditional">conditional · currencies or estimates excluded</small></dt><dd class="cost-separator numeric" data-test="forge-savings" :class="{ muted: comparison?.savingsCopper == null }">{{ signed(comparison?.savingsCopper ?? null) }}</dd>
          <dt class="cost-total">{{ economics?.conditional || !project.settings.valueOwn ? 'Conditional margin' : 'Profit' }}<small>after 15% TP fees{{ project.settings.valueOwn ? '' : ' · excludes own-material value' }}</small></dt>
          <dd class="cost-total numeric" data-test="forge-margin" :class="{ 'money--gain': economics?.marginCopper != null && economics.marginCopper >= 0, 'money--loss': economics?.marginCopper != null && economics.marginCopper < 0 }">{{ signed(economics?.marginCopper ?? null) }}</dd>
        </dl>
        <p v-if="project.clover" class="muted" data-test="forge-clover-estimate">Estimated: {{ project.clover.attempts }} Mystic Forge attempts for {{ project.clover.missing }} missing Clovers (about {{ project.clover.percent }}% each); not guaranteed.</p>
        <p v-if="project.guaranteedOutput !== null" class="output-note" data-test="forge-output">Guaranteed output: {{ project.guaranteedOutput }}</p>
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
        <div class="box-heading"><h3>Using from your account</h3><span class="count">{{ owned.length }}</span></div>
        <p class="muted">{{ settings.useOwn ? 'Including reserved materials.' : 'Own materials are not used.' }}</p>
        <div class="box-list">
          <ul class="item-list">
            <li v-for="entry in owned" :key="entry.key" data-test="forge-owned-line">
              <ItemIcon :icon-url="entry.node.item.iconUrl" :item-id="entry.node.item.itemId" :size="28" />
              <div class="item-description"><a v-if="entry.node.item.wikiUrl" :href="entry.node.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ entry.node.item.name ?? `Item ${entry.node.item.itemId}` }}</a><span v-else>{{ entry.node.item.name ?? `Item ${entry.node.item.itemId}` }}</span><small v-if="entry.usedFor">For {{ entry.usedFor }}</small><small v-else>Already owned</small></div>
              <strong class="numeric money--gain">{{ entry.node.fromOwned }}</strong>
            </li>
          </ul>
          <p v-if="!owned.length" class="muted">No owned items used in this plan.</p>
        </div>
        <details v-if="project.currencies.length" class="wallet-detail" data-test="forge-currencies"><summary>Currency requirements</summary>
          <p v-for="currency in project.currencies" :key="currency.currencyId" data-test="forge-currency">{{ currency.name }} — need {{ currency.needed }}, {{ currency.owned === null ? 'wallet unknown' : `have ${currency.owned}` }}</p>
        </details>
      </section>

      <section class="project-box shopping-box" data-test="forge-shopping">
        <div class="box-heading"><h3>Shopping &amp; acquisition</h3></div>
        <div class="box-list">
          <ul class="item-list">
            <li v-for="line in project.shopping" :key="`${line.source}-${line.item.itemId}`" data-test="forge-shopping-line">
              <ItemIcon :icon-url="line.item.iconUrl" :item-id="line.item.itemId" :size="28" />
              <div class="item-description"><a v-if="line.item.wikiUrl" :href="line.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ line.item.name ?? `Item ${line.item.itemId}` }}</a><span v-else>{{ line.item.name ?? `Item ${line.item.itemId}` }}</span>
                <small v-if="forgeAcquisitionHint(line.item.itemId)">{{ forgeAcquisitionHint(line.item.itemId)?.source }}</small>
                <small v-else-if="line.source === 'VENDOR'" data-test="forge-shopping-vendor">Merchant · {{ line.purchases }} {{ line.purchases === 1 ? 'purchase' : 'purchases' }}</small>
                <small v-else>{{ line.unitCopper === null ? 'Acquisition needed' : 'Trading Post' }}</small>
                <small v-if="line.knownCopper !== null" class="money--loss">{{ money(line.knownCopper) }}</small>
                <small v-for="cost in line.currencyCosts" :key="cost.currencyId">{{ cost.count }} {{ cost.name }}</small>
                <small v-if="line.hint">{{ line.hint }}</small>
              </div>
              <strong class="numeric">{{ line.toAcquire }}</strong>
            </li>
            <li v-for="currency in missingCurrencies" :key="`currency-${currency.currencyId}`" data-test="forge-shopping-currency">
              <ItemIcon :icon-url="currency.iconUrl" :item-id="null" :size="28" />
              <div class="item-description"><a v-if="currency.wikiUrl" :href="currency.wikiUrl" target="_blank" rel="noopener noreferrer">{{ currency.name }}</a><span v-else>{{ currency.name }}</span><small>{{ currency.missing === null ? `${currency.needed} needed · wallet unknown` : 'Still to acquire' }}</small></div>
              <strong class="numeric">{{ currency.missing ?? '—' }}</strong>
            </li>
          </ul>
          <p v-if="!project.shopping.length && !missingCurrencies.length" class="muted">Nothing to acquire.</p>
        </div>
        <details v-if="project.unpricedItems.length" class="unpriced-detail"><summary>Unpriced requirements</summary><p data-test="forge-unpriced">{{ project.unpricedItems.map(item => item.name ?? `Item ${item.itemId}`).join(', ') }}</p></details>
      </section>

      <section class="project-box tree-box" data-test="forge-tree">
        <h3>Recipe tree</h3>
        <div class="path-content"><ul v-if="project.tree" class="recipe-tree"><MysticForgeNode :node="project.tree" :depth="0" /></ul><p v-else class="muted">Recipe path unavailable.</p></div>
      </section>
      <section class="project-box steps-box">
        <h3>Crafting steps</h3>
        <div class="path-content"><ForgeCraftingSteps :tree="project.tree" /></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* Rearrange the named areas here while pairing; each box owns its contents independently. */
.equipment-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  grid-template-areas: "settings settings settings settings settings settings" "costs costs owned owned shopping shopping" "tree tree tree steps steps steps";
  align-items: start;
  gap: var(--space-5);
}
.settings-box { grid-area: settings; }
.costs-box { grid-area: costs; }
.owned-box { grid-area: owned; }
.shopping-box { grid-area: shopping; }
.tree-box { grid-area: tree; }
.steps-box { grid-area: steps; }
.project-box { min-width: 0; padding: var(--space-5); border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: var(--color-surface); display: flex; flex-direction: column; gap: var(--space-4); }
.box-heading { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); }
.muted, small, .count { color: var(--color-muted); font-size: var(--text-sm); }
.count { font-variant-numeric: tabular-nums; }
.settings-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-4) var(--space-5); }
.settings-grid label, .reduction-control, .amount-control { display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; }
.settings-grid select { width: 100%; min-width: 0; }
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
.cost-lines small, .item-description small { display: block; font-weight: 400; }
.cost-separator { border-top: 1px solid var(--color-border); padding-top: var(--space-4); }
.cost-total { border-top: 1px solid var(--color-border); padding-top: var(--space-4); font-weight: 700; }
.money--loss { color: var(--color-danger); }
.money--gain { color: var(--color-success); }
.box-list { max-height: 30rem; overflow: auto; padding-right: var(--space-1); }
.item-list { list-style: none; margin: 0; padding: 0; }
.item-list li { display: grid; grid-template-columns: 28px minmax(0, 1fr) max-content; gap: var(--space-3); align-items: start; padding: var(--space-3) 0; border-bottom: 1px solid var(--color-border); }
.item-list li:first-child { padding-top: 0; }
.item-list li:last-child { border: 0; padding-bottom: 0; }
.item-description { min-width: 0; overflow-wrap: anywhere; }
.item-list strong { white-space: nowrap; }
details { color: var(--color-muted); font-size: var(--text-sm); }
summary { cursor: pointer; }
details p { margin-top: var(--space-2); }
.output-note { font-size: var(--text-sm); color: var(--color-muted); }
.path-content { max-height: 56rem; overflow: auto; min-width: 0; }
.recipe-tree { margin: 0; padding: 0; }
.recipe-tree :deep(.forge-node__head) { flex-wrap: nowrap; }
.recipe-tree :deep(.forge-node__name) { min-width: 10rem; overflow-wrap: normal; }
.recipe-tree :deep(.forge-node__facts) { min-width: 12rem; }
</style>
