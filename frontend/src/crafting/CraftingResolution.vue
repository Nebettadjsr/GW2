<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ResolutionDetailView } from '@/api/types'
import { NO_VALUE } from './formatCopper'
import { describeRootSourcing } from './resolutionPresentation'
import ResolutionTreeNode from './ResolutionTreeNode.vue'
import type { ResolutionPhase } from './useResolutionDetail'

/**
 * The selected recipe's backend explanation: the resolution tree the fresh calculation produced
 * (`TARGET_ARCHITECTURE.md` 13.3/13.4, `DOMAIN_SPEC.md` 2.1.1, 44).
 *
 * The distinction this region exists to keep is the one the contract insists on. The comparison
 * table above holds the *earlier* calculation's numbers; the tree here comes from a **separate,
 * fresh** calculation the backend ran when the recipe was selected. Identical inputs may produce
 * different values after a price change or a synchronization, so neither set is quietly written over
 * the other and this one is never described as the table row's own explanation. That is now said in
 * one short line rather than a paragraph, and the fresh row's own totals — a second set of numbers
 * beside the table's, saying nothing the tree does not — are no longer repeated here at all
 * (2.1.1). The response still carries them; the request, its association rules and the five
 * situations below are untouched.
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
 *
 * It takes the envelope both fresh-detail routes share (`ResolutionDetailView`), not one route's
 * response, so Crafting Profit and Crafting Discovery present a resolution the same way. The echoed
 * calculation is deliberately not part of that type: matching it against what was asked for is the
 * calling feature's own association check, not this region's.
 */
const props = defineProps<{
  phase: ResolutionPhase
  detail: ResolutionDetailView | null
  failure: string | null
  /** The recipe the current phase is about, so no wording can attach to a different row. */
  requestedRecipeId: number | null
}>()

/** Only ever read in the `ready` phase, where the contract guarantees a tree. */
const tree = computed(() => props.detail?.tree ?? null)

const rootSourcing = computed(() => {
  const root = tree.value
  const recipeId = props.requestedRecipeId
  if (root === null || recipeId === null) return null
  return describeRootSourcing(root, recipeId)
})

/**
 * Bumped for every answer this region is given, and used as the tree's key.
 *
 * Whether a group is open is the `<details>` element's own state, not this application's, so a
 * replacement answer rendered into the same elements would inherit whatever the previous one had
 * been expanded to. Remounting the tree is what makes "a newly selected or replaced resolution
 * starts collapsed" true of the second answer as well as the first (`DOMAIN_SPEC.md` 2.1.1).
 */
const treeGeneration = ref(0)
watch(
  () => props.detail,
  () => {
    treeGeneration.value += 1
  }
)
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
      <!--
        The basis, kept but no longer a paragraph (`DOMAIN_SPEC.md` 2.1.1). One line is enough to
        stop the tree reading as a trace of every craft the table counted; the envelope's own
        literals stay in the detail's technical disclosure for anyone who needs more.
      -->
      <p class="meta" data-test="resolution-basis">
        A separate calculation of one output batch — not every craft the table counted.
      </p>

      <h4 class="detail__basis">What supplied the output</h4>
      <p data-test="resolution-root-sourcing">{{ rootSourcing }}</p>

      <h4 class="detail__basis">Requirements</h4>
      <ul class="resolution__tree" data-test="resolution-tree">
        <ResolutionTreeNode :key="treeGeneration" :node="tree" path="0" />
      </ul>
      <p class="meta" data-test="resolution-tree-note">
        Every requirement the backend returned is listed, in its order; an item needed in two
        branches is two entries. Expand a group to see its ingredients — nothing is left out of a
        collapsed one. Each cost includes everything below its own requirement, and {{ NO_VALUE }}
        is a cost the backend could not establish, not zero.
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
