import { onUnmounted, ref, watch, type Ref } from 'vue'
import type { CraftingApi } from '@/api/craftingApi'

const SEARCH_DEBOUNCE_MS = 250

/** Debounced lookup against the backend's in-memory reverse graph. */
export function useIngredientSearch(
  api: CraftingApi,
  searchText: Ref<string>,
  matchingRecipeIds: Ref<ReadonlySet<number>> = ref(new Set())
): Ref<ReadonlySet<number>> {
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0

  watch(searchText, (text) => {
    generation++
    const requestGeneration = generation
    if (timer !== undefined) clearTimeout(timer)
    matchingRecipeIds.value = new Set()
    const query = text.trim()
    if (query === '') return

    timer = setTimeout(() => {
      void api.searchIngredientRecipes(query).then((recipeIds) => {
        if (requestGeneration === generation) matchingRecipeIds.value = new Set(recipeIds)
      }).catch(() => {
        // Output, direct-ingredient and other local search fields remain usable during API failure.
        if (requestGeneration === generation) matchingRecipeIds.value = new Set()
      })
    }, SEARCH_DEBOUNCE_MS)
  })

  onUnmounted(() => {
    generation++
    if (timer !== undefined) clearTimeout(timer)
  })
  return matchingRecipeIds
}
