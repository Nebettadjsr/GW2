<script setup lang="ts">
import { computed, inject } from 'vue'
import type { ResolutionCurrency, ResolutionNode } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import { formatCopper } from './formatCopper'
import {
  keepTargetNote,
  nodeLabel,
  nodePlayerStatus,
  RESOLUTION_SETTINGS_KEY
} from './resolutionPresentation'
import { wikiUrl } from './recipeLabel'

/**
 * One requirement of the resolution tree, recursively including the ingredients of a selected or
 * attempted craft. Both crafting pages use this one compact presentation: backend-supplied identity,
 * needed and source quantities, the named crafter and a per-item and a total `Value`. Raw state, blocked-reason and
 * acquisition-method codes remain in backend/API diagnostics. No value is recalculated, and repeated
 * items remain separate ordered occurrences.
 *
 * Child groups are collapsed independently at every level, including the root. Collapsing changes
 * visibility only; all returned requirements remain in the tree.
 */
const props = defineProps<{
  node: ResolutionNode
  /**
   * Local index path from the root (e.g. `0.1.2`), used only as this render's `v-for` key and
   * `data-path`. It is not a persistent node identity and is never stored or sent to the backend.
   */
  path: string
  /** Only Discovery displays recipe-knowledge state. */
  showRecipeKnowledge?: boolean
}>()

const label = computed(() => nodeLabel(props.node))
const resolutionSettings = inject(RESOLUTION_SETTINGS_KEY, null)
const keepNote = computed(() => keepTargetNote(resolutionSettings?.value, props.node.itemId))
const sourcing = computed(() => [
  { label: 'From stock', quantity: props.node.inventoryQuantity },
  { label: 'Crafted', quantity: props.node.craftedQuantity },
  { label: 'Bought', quantity: props.node.boughtQuantity }
].filter((source) => source.quantity > 0))
const playerStatus = computed(() => nodePlayerStatus(props.node))
const wikiHref = computed(() => wikiUrl(props.node.itemName))

const unitText = computed(() => {
  const text = formatCopper(props.node.effectiveUnitCostCopper)
  return props.node.effectiveUnitCostApproximate && props.node.effectiveUnitCostCopper !== null ? `≈ ${text}` : text
})

const currencies = computed(() => props.node.currencies ?? [])

/** Backend-counted currency sourcing, only presented: no arithmetic beyond selecting non-zero quantities. */
function currencySources(currency: ResolutionCurrency) {
  return [
    { label: 'From wallet', quantity: currency.fromWallet ?? 0, key: 'wallet' },
    { label: 'From earlier batch', quantity: currency.fromProduced ?? 0, key: 'produced' },
    { label: 'Crafted', quantity: currency.crafted ?? 0, key: 'crafted' },
    { label: 'Missing', quantity: currency.missing ?? 0, key: 'missing' }
  ].filter((source) => source.quantity > 0)
}

/** An unknown name stays the exact id label and gets no guessed wiki page. */
function currencyWiki(currency: ResolutionCurrency): string | null {
  return currency.name === `Currency ${currency.currencyId}` ? null : wikiUrl(currency.name)
}
const childCount = computed(() => props.node.children.length)
const childSummary = computed(() => {
  const parts: string[] = []
  if (childCount.value > 0) {
    parts.push(childCount.value === 1 ? '1 ingredient requirement' : `${childCount.value} ingredient requirements`)
  }
  if (currencies.value.length > 0) {
    parts.push(currencies.value.length === 1 ? '1 currency requirement' : `${currencies.value.length} currency requirements`)
  }
  return parts.join(' + ')
})
</script>

<template>
  <li class="node" data-test="tree-node" :data-path="path">
    <div class="node__head">
      <!--
        This node's *own* item, which is the identity the backend gave it — not the requested
        recipe's output, and not a picture of how the requirement was sourced. Two occurrences of the
        same item in different branches are two nodes and each shows its own icon.
      -->
      <span class="node__identity">
        <ItemIcon :icon-url="node.iconUrl" :item-id="node.itemId" loading="lazy" />
        <a v-if="wikiHref !== null" class="node__name" :href="wikiHref" target="_blank"
          rel="noopener noreferrer" data-test="node-wiki"><span data-test="node-name">{{ label }}</span></a>
        <span v-else class="node__name" data-test="node-name">{{ label }}</span>
        <span v-if="showRecipeKnowledge && node.recipeKnowledge === 'KNOWN'"
          class="status status--success recipe-knowledge" data-test="recipe-knowledge">Known</span>
        <span v-else-if="showRecipeKnowledge && node.recipeKnowledge === 'TO_DISCOVER'"
          class="status status--caution recipe-knowledge" data-test="recipe-knowledge">To discover</span>
      </span>
      <span class="node__needed numeric" data-test="node-requested">
        {{ node.requestedQuantity }} needed
      </span>
    </div>

    <p v-if="keepNote !== null" class="node__keep meta" data-test="node-keep-target">{{ keepNote }}</p>

    <p v-if="sourcing.length > 0" class="node__methods" data-test="node-methods">
      <span data-test="node-sourcing">
        <span v-for="source in sourcing" :key="source.label" class="chip chip--method" data-test="node-method">
          {{ source.label }} ×{{ source.quantity }}
        </span>
      </span>
    </p>

    <p v-if="playerStatus !== null" class="node__player-status" data-test="node-player-status">
      <strong>{{ playerStatus }}</strong>
      <span> · </span>
      <a v-if="wikiHref !== null" :href="wikiHref" target="_blank" rel="noopener noreferrer">GW2 Wiki: {{ label }}</a>
    </p>

    <!--
      The character the backend named for this requirement's craft, under the label Request-012 asked
      for. A node the backend assigned no character to says nothing here rather than carrying a "Not
      assigned" row of its own — the absence is not a fact about the requirement.
    -->
    <p v-if="node.characterName !== null" class="node__crafter" data-test="node-crafter">
      Crafted by {{ node.characterName }}
    </p>

    <!--
      The backend's display-only unit average and unchanged inclusive total, each on its own line
      (`DOMAIN_SPEC.md` 2.1.1). Cash and opportunity breakdowns remain in API diagnostics.
    -->
    <p class="node__value" data-test="node-value">
      <span class="node__value-part" data-test="node-unit-value">Value per item: <span class="node__value-amount">{{ unitText }}</span></span>
      <span class="node__value-part" data-test="node-total-value">Total value for {{ node.requestedQuantity }}
        {{ node.requestedQuantity === 1 ? 'item' : 'items' }}: <span class="node__value-amount">{{ formatCopper(node.effectiveCostCopper) }}</span></span>
    </p>

    <details v-if="childCount > 0 || currencies.length > 0" class="node__children" data-test="node-children">
      <summary>{{ childSummary }}</summary>
      <ul class="node__list">
        <!--
          Currency units and their sourcing are supplied by the backend for this node's craft; a currency is
          not an item: its own icon route and wiki name, never an item id, link or calculation here.
        -->
        <li v-for="currency in currencies" :key="`currency-${currency.currencyId}`" class="node node--currency"
          data-test="tree-currency">
          <div class="node__head">
            <span class="node__identity">
              <ItemIcon :icon-url="currency.iconUrl ?? null" :item-id="null" loading="lazy" />
              <a v-if="currencyWiki(currency) !== null" class="node__name" :href="currencyWiki(currency) ?? undefined"
                target="_blank" rel="noopener noreferrer" data-test="currency-wiki"><span data-test="currency-name">{{ currency.name }}</span></a>
              <span v-else class="node__name" data-test="currency-name">{{ currency.name }}</span>
            </span>
            <span class="node__needed numeric" data-test="currency-quantity">{{ currency.quantity }} needed</span>
          </div>
          <p v-if="currencySources(currency).length > 0" class="node__methods" data-test="currency-sources">
            <span v-for="source in currencySources(currency)" :key="source.key" class="chip chip--method"
              :class="{ 'chip--missing': source.key === 'missing' }" data-test="currency-source">
              {{ source.label }} ×{{ source.quantity }}
            </span>
          </p>
          <p v-if="currency.walletBalance !== undefined" class="meta node__wallet" data-test="currency-wallet">
            <template v-if="currency.walletBalance === null">Wallet balance unknown</template>
            <template v-else>Wallet balance when calculated: {{ currency.walletBalance }}</template>
          </p>
        </li>
        <ResolutionTreeNode
          v-for="(child, index) in node.children"
          :key="`${path}.${index}`"
          :node="child"
          :path="`${path}.${index}`"
          :show-recipe-knowledge="showRecipeKnowledge"
        />
      </ul>
    </details>
  </li>
</template>

<style scoped>
.node {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-2) 0 var(--space-2) var(--space-3);
  border-left: 2px solid var(--color-border);
}

.node + .node {
  margin-top: var(--space-2);
}

.node__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-1) var(--space-3);
}

/* Icon and name are one unit, so the "needed" quantity still sits at the far end of the line. */
.node__identity {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.node__name {
  font-weight: 600;
  overflow-wrap: anywhere;
}

.node__value-part { display: block; }
.node__value-amount { white-space: nowrap; }

.node__identity a { color: inherit; text-decoration: underline; text-decoration-color: var(--color-muted); }

.recipe-knowledge { padding: 0 var(--space-2); font-size: var(--text-sm); }

.node__needed {
  color: var(--color-muted);
  font-size: var(--text-sm);
  white-space: nowrap;
}

.node__methods {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-2);
  margin: 0;
}

/* A quiet marker for a backend-supplied source quantity. */
.chip {
  padding: 0 var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  font-size: var(--text-sm);
  /* Source chips wrap inside the tree rather than widening a deeply nested node. */
  white-space: normal;
  overflow-wrap: anywhere;
}

.chip--method {
  border-color: var(--color-secondary);
  color: var(--color-secondary);
}

.chip--missing {
  border-color: var(--color-danger, currentColor);
  color: var(--color-danger, inherit);
}

.node__wallet { margin: 0; font-size: var(--text-sm); }

.node__crafter {
  margin: 0;
  font-size: var(--text-sm);
}

.node__value {
  display: flex;
  flex-direction: column;
  gap: 0;
  overflow-wrap: anywhere;
  margin: 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
}

.node__player-status { margin: 0; font-size: var(--text-sm); }
.node__children > summary {
  padding: var(--space-1) 0;
  cursor: pointer;
  color: var(--color-muted);
  font-size: var(--text-sm);
}

.node__list {
  margin: 0;
  padding: 0;
  list-style: none;
}
</style>
