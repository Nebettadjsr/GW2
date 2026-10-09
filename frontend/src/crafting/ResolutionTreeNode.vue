<script setup lang="ts">
import { computed } from 'vue'
import type { ResolutionNode } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import { formatCopper } from './formatCopper'
import {
  nodeLabel,
  nodePlayerStatus
} from './resolutionPresentation'
import { wikiUrl } from './recipeLabel'

/**
 * One requirement of the resolution tree, recursively including the ingredients of a selected or
 * attempted craft. Both crafting pages use this one compact presentation: backend-supplied identity,
 * needed and source quantities, the named crafter and a single `Value`. Raw state, blocked-reason and
 * acquisition-method codes remain in backend/API diagnostics. No value is recalculated, and repeated
 * items remain separate ordered occurrences.
 *
 * Child groups are collapsed independently at every level, including the root. Collapsing changes
 * visibility only; all returned requirements remain in the tree.
 */
const props = defineProps<{
  node: ResolutionNode
  /** Index path from the root, the presentation key 13.3 permits — not a persistent node ID. */
  path: string
  /** Only Discovery displays recipe-knowledge state. */
  showRecipeKnowledge?: boolean
}>()

const label = computed(() => nodeLabel(props.node))
const sourcing = computed(() => [
  { label: 'From stock', quantity: props.node.inventoryQuantity },
  { label: 'Crafted', quantity: props.node.craftedQuantity },
  { label: 'Bought', quantity: props.node.boughtQuantity }
].filter((source) => source.quantity > 0))
const playerStatus = computed(() => nodePlayerStatus(props.node))
const wikiHref = computed(() => wikiUrl(props.node.itemName))

const childCount = computed(() => props.node.children.length)
const childSummary = computed(() =>
  childCount.value === 1 ? '1 ingredient requirement' : `${childCount.value} ingredient requirements`
)
</script>

<template>
  <li class="node" :data-test="'tree-node'" :data-path="path">
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
      One `Value` per requirement, the backend's own `effectiveCostCopper` (`DOMAIN_SPEC.md` 2.1.1).
      The separate cash/opportunity/effective figures remain in the API contract for diagnostics and
      are deliberately not a per-node disclosure here.
    -->
    <p class="node__value" data-test="node-value">
      Value: {{ formatCopper(node.effectiveCostCopper) }}
    </p>

    <details v-if="childCount > 0" class="node__children" data-test="node-children">
      <summary>{{ childSummary }}</summary>
      <ul class="node__list">
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

.node__identity a { color: inherit; text-decoration: underline; text-decoration-color: var(--color-muted); }

.recipe-knowledge { padding: 0 var(--space-2); font-size: var(--text-sm); }

.node__needed {
  color: var(--color-muted);
  font-size: var(--text-sm);
  white-space: nowrap;
}

.node__methods,
.node__codes {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-2);
  margin: 0;
}

/* A quiet marker for a supplied code; the word inside it is what carries the meaning. */
.chip {
  padding: 0 var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  font-size: var(--text-sm);
}

/*
 * A code this page has no wording for is shown in full, and an unknown code can be any length. The
 * shared `.status` treatment keeps its text on one line, which is right in a table cell but would
 * make a deeply nested node wider than a narrow viewport, so inside the tree these wrap instead of
 * pushing the page sideways. Nothing is shortened or dropped to achieve it.
 */
.chip,
.node__codes .status {
  white-space: normal;
  overflow-wrap: anywhere;
}

.chip--method {
  border-color: var(--color-secondary);
  color: var(--color-secondary);
}

.chip--unknown {
  border-style: dashed;
  color: var(--color-muted);
}

.node__crafter {
  margin: 0;
  font-size: var(--text-sm);
}

.node__value {
  display: flex;
  gap: var(--space-2);
  margin: 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
}

.node__note {
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
