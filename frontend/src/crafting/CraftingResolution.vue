<script setup lang="ts">
import { computed } from 'vue'
import type { CraftingProfitResolutionResponse } from '@/api/types'
import { formatCopper, formatCount, formatSignedCopper, moneyTone } from './formatCopper'
import { describeRootSourcing } from './resolutionPresentation'
import ResolutionTreeNode from './ResolutionTreeNode.vue'
import { describeRowState } from './rowState'
import type { ResolutionPhase } from './useProfitResolution'

/**
 * The selected recipe's backend explanation: the freshly calculated row and the resolution tree,
 * shown together as one answer (`TARGET_ARCHITECTURE.md` 13.3/13.4, `DOMAIN_SPEC.md` 44).
 *
 * The distinction this region exists to keep is the one the contract insists on. The comparison
 * table above holds the *earlier* calculation's numbers; everything here comes from a **separate,
 * fresh** calculation the backend ran when the recipe was selected. Identical inputs may produce
 * different values after a price change or a synchronization, so neither set is quietly written over
 * the other and this one is never described as the table row's own explanation.
 *
 * The tree's basis is one output batch of the requested recipe — not a trace of every craft the row
 * counted, and not a promise that the requested recipe was executed at all. What actually supplied
 * the root requirement is whatever the root node says: owned stock, another recipe or a blocked
 * attempt.
 *
 * Five situations are told apart rather than collapsed into "no detail": still loading, an answer
 * whose tree exists (blocked requirements included), an answer with no calculation result to
 * explain, a recipe the fresh calculation does not contain, and a request that failed. The last
 * three clear the tree instead of leaving an older one under the current selection.
 */
const props = defineProps<{
  phase: ResolutionPhase
  detail: CraftingProfitResolutionResponse | null
  failure: string | null
  /** The recipe the current phase is about, so no wording can attach to a different row. */
  requestedRecipeId: number | null
}>()

/** Only ever read in the `ready` phase, where the contract guarantees a tree. */
const tree = computed(() => props.detail?.tree ?? null)

const freshRow = computed(() => props.detail?.row ?? null)
const freshState = computed(() => (freshRow.value === null ? null : describeRowState(freshRow.value)))

const rootSourcing = computed(() => {
  const root = tree.value
  const recipeId = props.requestedRecipeId
  if (root === null || recipeId === null) return null
  return describeRootSourcing(root, recipeId)
})

/** Names the basis of the fresh row's totals in its own words, never the table row's count. */
const freshTotalsLabel = computed(() => {
  const count = freshRow.value?.craftableCount ?? null
  if (count === null) return 'For every craft this fresh calculation counted'
  return count === 1
    ? 'For the 1 craft this fresh calculation counted'
    : `For all ${count} crafts this fresh calculation counted`
})
</script>

<template>
  <div class="resolution">
    <p v-if="phase === 'idle'" class="meta" data-test="resolution-idle">
      No explanation has been requested for this recipe.
    </p>

    <p
      v-else-if="phase === 'loading'"
      class="notice notice--info"
      role="status"
      data-test="resolution-loading"
    >
      Working out how this recipe resolves…
    </p>

    <p v-else-if="phase === 'absent'" class="notice notice--warning" data-test="resolution-absent">
      A fresh calculation with the same scope and settings no longer offers this recipe, so there is
      nothing to explain. That does not mean the recipe is gone from the database — reload the
      results to see what the calculation offers now.
      <span class="meta detail-line">Backend answer: {{ failure }}</span>
    </p>

    <p v-else-if="phase === 'failed'" class="notice notice--error" data-test="resolution-failed">
      The explanation could not be loaded, so none is shown. This is a request that did not work, not
      something the calculation established about the recipe.
      <span class="meta detail-line">Backend answer: {{ failure }}</span>
    </p>

    <p
      v-else-if="phase === 'unavailable'"
      class="notice notice--warning"
      data-test="resolution-unavailable"
    >
      The backend recalculated this recipe and reported that it has no result to explain, so there is
      no resolution tree for it. This is the calculation's own answer, not a failed request.
    </p>

    <template v-else-if="phase === 'ready' && detail !== null && tree !== null">
      <p class="meta" data-test="resolution-basis">
        A separate calculation, run when this recipe was selected. It resolves
        <strong>one output batch</strong> of the requested recipe from that calculation's starting
        inventory, budget and daily state — not every craft the table counted, and not a promise that
        the requested recipe was the one executed. Because it is its own calculation, its numbers can
        differ from the table's above; neither replaces the other.
      </p>

      <h4 class="detail__basis">What supplied the output</h4>
      <p data-test="resolution-root-sourcing">{{ rootSourcing }}</p>

      <h4 class="detail__basis">Requirements</h4>
      <ul class="resolution__tree" data-test="resolution-tree">
        <ResolutionTreeNode :node="tree" path="0" />
      </ul>
      <p class="meta" data-test="resolution-tree-note">
        Every requirement the backend returned is listed, in its order. A requirement that appears in
        two branches is two entries, because each is its own occurrence. Collapse a group to shorten
        the list; nothing is left out of it.
      </p>

      <h4 class="detail__basis">This recipe in that fresh calculation</h4>
      <p v-if="freshState !== null" class="meta">
        <span :class="`status status--${freshState.tone}`" data-test="resolution-row-status">
          {{ freshState.label }}
        </span>
      </p>
      <dl v-if="freshRow !== null" class="detail-values" data-test="resolution-row">
        <dt>Profit per craft</dt>
        <dd class="numeric">
          <span :class="`money money--${moneyTone(freshRow.profitCopper)}`" data-test="resolution-profit">
            {{ formatSignedCopper(freshRow.profitCopper) }}
          </span>
        </dd>

        <dt>Crafts possible</dt>
        <dd class="numeric" data-test="resolution-craftable">
          {{ formatCount(freshRow.craftableCount) }}
        </dd>

        <dt>Total sell value</dt>
        <dd class="numeric money" data-test="resolution-total-sell-value">
          {{ formatCopper(freshRow.totalSellValueCopper) }}
        </dd>

        <dt>Total profit</dt>
        <dd class="numeric">
          <span
            :class="`money money--${moneyTone(freshRow.totalProfitCopper)}`"
            data-test="resolution-total-profit"
          >
            {{ formatSignedCopper(freshRow.totalProfitCopper) }}
          </span>
        </dd>

        <dt>Cost of materials to buy</dt>
        <dd class="numeric money money--cost" data-test="resolution-buy-cost">
          {{ formatCopper(freshRow.buyCostCopper) }}
        </dd>
      </dl>
      <p class="meta" data-test="resolution-row-basis">
        {{ freshTotalsLabel }}; profit per craft is for one craft. Total sell value is what those
        crafts' output is worth, before the cost of making it. The comparison table above still shows
        what its own calculation reported and has not been changed by this.
      </p>
    </template>
  </div>
</template>

<style scoped>
.resolution {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.resolution__tree {
  margin: 0;
  padding: 0;
  list-style: none;
}
</style>
