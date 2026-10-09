<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ResolutionDetailView } from '@/api/types'
import { describeRootSourcing } from './resolutionPresentation'
import ResolutionTreeNode from './ResolutionTreeNode.vue'
import type { ResolutionPhase } from './useResolutionDetail'

/**
 * Renders backend resolution data for Profit and Discovery. Both pages use the same compact
 * presentation (`DOMAIN_SPEC.md` 2.1.1): the requirements themselves, with no basis paragraph and no
 * closing tree note. Profit's tree covers the selected result's full counted output quantity and
 * Discovery's one discovery attempt; the difference is in the quantities the backend supplies, not in
 * what this component renders. It never derives tree quantities or costs, and the route hooks own
 * echoed input and request-generation association checks.
 *
 * Loading, unavailable-result, absent-candidate and request-failure states remain distinct. States
 * without a valid tree clear it instead of leaving an older selection visible.
 *
 * It takes the envelope both fresh-detail routes share (`ResolutionDetailView`).
 */
const props = defineProps<{
  phase: ResolutionPhase
  detail: ResolutionDetailView | null
  failure: string | null
  /** Discovery alone asks for account recipe-knowledge indicators in its tree. */
  showRecipeKnowledge?: boolean
  /** The recipe the current phase is about, so no wording can attach to a different row. */
  requestedRecipeId: number | null
}>()

/** Only ever read in the `ready` phase, where the contract guarantees a tree. */
const tree = computed(() => props.detail?.tree ?? null)

/**
 * Said only when the root the backend returned was not produced by the recipe that was asked about: a
 * root carrying the requested recipe needs no sentence restating it.
 */
const rootSourcing = computed(() => {
  const root = tree.value
  const recipeId = props.requestedRecipeId
  if (root === null || recipeId === null) return null
  if (root.recipeId === recipeId) return null
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
      <p v-if="rootSourcing !== null" class="meta" data-test="resolution-root-sourcing">
        {{ rootSourcing }}
      </p>

      <h4 class="detail__basis">Requirements</h4>
      <ul class="resolution__tree" data-test="resolution-tree">
        <ResolutionTreeNode
          :key="treeGeneration"
          :node="tree"
          path="0"
          :show-recipe-knowledge="showRecipeKnowledge"
        />
      </ul>
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
