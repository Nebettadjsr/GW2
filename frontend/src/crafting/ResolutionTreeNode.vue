<script setup lang="ts">
import { computed } from 'vue'
import type { ResolutionNode } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import { formatCopper } from './formatCopper'
import {
  blockedExplanation,
  blockedReasonLabels,
  methodLabels,
  nodeLabel,
  stateExplanations,
  stateLabels
} from './resolutionPresentation'

/**
 * One requirement of the resolution tree, and — recursively — the ingredient requirements of the
 * craft that was selected or attempted for it (`TARGET_ARCHITECTURE.md` 13.3).
 *
 * Profit's compact presentation is a **summary** (`DOMAIN_SPEC.md` 2.1.1): what the item is,
 * how many are needed, how the requirement was supplied, who crafted it where the backend said so,
 * and its effective economic value. The resolver's own bookkeeping — the stock/crafted/bought/
 * missing split, the producing recipe and its id, the craft count and the produced batch total — is
 * not shown here; it stays in the backend contract and is simply not part of the normal view.
 *
 * What is never dropped for brevity is a fact about a requirement that did not resolve: every
 * supplied state and blocked reason keeps its own marker and its own sentence, so no missing price,
 * daily limit or restriction can read as success.
 *
 * Everything on screen is still a value the backend supplied for *this* node. `effectiveCostCopper`
 * is the cash plus opportunity cost for this requirement, including its descendants. No quantity is
 * derived from the others, no state
 * is read out of a price, no identity is replaced by the requested recipe's, and two occurrences of
 * the same item in different branches stay two nodes.
 *
 * Children sit in a **collapsed** disclosure at every level, the root's included. Each group opens
 * on its own and opens nothing below it, so a deep tree is a short list until the user asks for the
 * next level. Collapsing hides nothing: every returned requirement is rendered, in its order.
 */
const props = defineProps<{
  node: ResolutionNode
  /** Index path from the root, the presentation key 13.3 permits — not a persistent node ID. */
  path: string
  /** Profit shows effective value; Discovery retains the separate cost figures. */
  compactValue?: boolean
}>()

const label = computed(() => nodeLabel(props.node))
const methods = computed(() => methodLabels(props.node.methods))
const states = computed(() => stateLabels(props.node.states))
// `label` is this node's own item, so a missing-price sentence says which item has no price
// (`DOMAIN_SPEC.md` 2.1.1) instead of leaving that to the heading above it.
const stateSentences = computed(() => stateExplanations(props.node.states, label.value))
const reasons = computed(() => blockedReasonLabels(props.node.blockedReasons))
const blockedSentence = computed(() => blockedExplanation(props.node.blockedReasons, label.value))

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
        <span class="node__name" data-test="node-name">{{ label }}</span>
      </span>
      <span class="node__needed numeric" data-test="node-requested">
        {{ node.requestedQuantity }} needed
      </span>
    </div>

    <p class="node__methods" data-test="node-methods">
      <template v-if="methods.length === 0">
        <span class="chip chip--empty">Nothing supplied this requirement</span>
      </template>
      <span
        v-for="method in methods"
        :key="method.code"
        :class="['chip', method.known ? 'chip--method' : 'chip--unknown']"
        data-test="node-method"
      >
        {{ method.label }}
      </span>
    </p>

    <!--
      The character the backend named for this requirement's craft, under the label Request-012 asked
      for. A node the backend assigned no character to says nothing here rather than carrying a "Not
      assigned" row of its own — the absence is not a fact about the requirement.
    -->
    <p v-if="node.characterName !== null" class="node__crafter" data-test="node-crafter">
      Crafted by {{ node.characterName }}
    </p>

    <p v-if="compactValue" class="node__value" data-test="node-value">
      Value: {{ formatCopper(node.effectiveCostCopper) }}
    </p>

    <p v-else class="node__costs" data-test="node-costs">
      <span class="node__cost">
        <span class="node__cost-label">Cash cost</span>
        <span class="numeric" data-test="node-cash-cost">{{ formatCopper(node.cashCostCopper) }}</span>
      </span>
      <span class="node__cost">
        <span class="node__cost-label">Opportunity cost</span>
        <span class="numeric" data-test="node-opportunity-cost">
          {{ formatCopper(node.opportunityCostCopper) }}
        </span>
      </span>
      <span class="node__cost">
        <span class="node__cost-label">Effective cost</span>
        <span class="numeric" data-test="node-effective-cost">
          {{ formatCopper(node.effectiveCostCopper) }}
        </span>
      </span>
    </p>

    <p v-if="states.length > 0" class="node__codes" data-test="node-states">
      <span
        v-for="state in states"
        :key="state.code"
        :class="['status', state.known ? 'status--caution' : 'status--unknown']"
        data-test="node-state"
      >
        {{ state.label }}
      </span>
    </p>
    <p
      v-for="(sentence, index) in stateSentences"
      :key="`state-text-${index}`"
      class="node__note"
      data-test="node-state-explanation"
    >
      {{ sentence }}
    </p>

    <template v-if="reasons.length > 0">
      <p class="node__codes" data-test="node-blocked">
        <span
          v-for="reason in reasons"
          :key="reason.code"
          :class="['status', reason.known ? 'status--caution' : 'status--unknown']"
          data-test="node-blocked-reason"
        >
          {{ reason.label }}
        </span>
      </p>
      <p class="node__note" data-test="node-blocked-explanation">{{ blockedSentence }}</p>
    </template>

    <details v-if="childCount > 0" class="node__children" data-test="node-children">
      <summary>{{ childSummary }}</summary>
      <ul class="node__list">
        <ResolutionTreeNode
          v-for="(child, index) in node.children"
          :key="`${path}.${index}`"
          :node="child"
          :path="`${path}.${index}`"
          :compact-value="compactValue"
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

.chip--unknown,
.chip--empty {
  border-style: dashed;
  color: var(--color-muted);
}

.node__crafter {
  margin: 0;
  font-size: var(--text-sm);
}

/*
 * One line instead of the former three-row grid: the labels wrap onto further lines on a narrow
 * viewport rather than pushing the page sideways, and each amount keeps its own label beside it.
 */
.node__costs {
  display: flex;
  flex-wrap: wrap;
  gap: 0 var(--space-3);
  margin: 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
}

.node__value {
  display: flex;
  gap: var(--space-2);
  margin: 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
}

.node__cost {
  display: inline-flex;
  gap: var(--space-1);
  white-space: nowrap;
}

.node__note {
  margin: 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
}

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
