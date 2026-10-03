<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, watch } from 'vue'
import { craftingApi, type CraftingApi } from '@/api/craftingApi'
import type { EffectiveDiscoverySettings } from '@/api/types'
import TradingPostPriceDisclaimer from '@/items/TradingPostPriceDisclaimer.vue'
import PageHeader from '@/shell/PageHeader.vue'
import DiscoveryScopeSelector from './DiscoveryScopeSelector.vue'
import DiscoverySettingsForm from './DiscoverySettingsForm.vue'
import DiscoveryTable from './DiscoveryTable.vue'
import SelectedDiscoveryDetail from './SelectedDiscoveryDetail.vue'
import { useCraftingDiscovery } from './useCraftingDiscovery'
import {
  discoveryCalculationKey,
  useDiscoveryResolution,
  type DiscoveryCalculationInputs
} from './useDiscoveryResolution'
import { useDiscoveryTableView, type DiscoverySortKey } from './useDiscoveryTableView'
import { useIngredientSearch } from './useIngredientSearch'
import { ref } from 'vue'

/** Crafting Discovery evaluates one attempt per missing recipe for the selected character. Eligibility and all calculation inputs remain backend-owned; search and sort are view state. */
const props = withDefaults(defineProps<{ api?: CraftingApi }>(), { api: () => craftingApi })

const discovery = useCraftingDiscovery(props.api)
const ingredientRecipeIds = ref<ReadonlySet<number>>(new Set())
const table = useDiscoveryTableView(discovery.rows, ingredientRecipeIds)
useIngredientSearch(props.api, table.searchText, ingredientRecipeIds)
const resolution = useDiscoveryResolution(props.api)

onMounted(() => {
  void discovery.open()
})

/** Effective inputs echoed by the backend; detail is never calculated from browser defaults or row values. */
const calculationInputs = computed<DiscoveryCalculationInputs | null>(() => {
  if (discovery.isLoading.value || !discovery.hasResult.value) return null
  const scope = discovery.effectiveScope.value
  const settings = discovery.settings.value
  if (scope === null || settings === null) return null
  return {
    scope,
    settings
  }
})

/**
 * Whether this screen is the one the user is looking at. It is kept alive behind another destination,
 * so "left the view" has to be tracked rather than assumed from unmounting.
 */
let isViewActive = true

/**
 * Asks for the selected recipe's detail, or invalidates the one on screen when there is nothing valid
 * to ask about. One request per selection, never one per row: the comparison list itself causes no
 * detail request at all.
 */
function syncResolution(): void {
  const recipeId = table.selectedRecipeId.value
  const inputs = calculationInputs.value
  if (!isViewActive || recipeId === null || inputs === null) {
    resolution.invalidate()
    return
  }
  void resolution.request(recipeId, inputs)
}

/**
 * The events 13.4 requires a detail to be invalidated by, and nothing else. The key deliberately
 * contains the selected recipe and the backend's echoed scope and settings only —
 * sorting and searching are absent from it, so re-ordering the list leaves a valid detail alone. A
 * started reload empties `calculationInputs` while it is in flight, which clears the tree immediately
 * and asks again against the answer that replaces it.
 */
watch(
  () =>
    `${table.selectedRecipeId.value ?? 'none'}|${discoveryCalculationKey(calculationInputs.value)}`,
  () => syncResolution()
)

// Leaving invalidates the detail even though the page survives; returning asks again, freshly.
onDeactivated(() => {
  isViewActive = false
  resolution.invalidate()
})

onActivated(() => {
  isViewActive = true
  syncResolution()
})

/** Empty means the backend answered successfully with no candidates, not that nothing was asked. */
const isEmptyResult = computed(() => discovery.hasResult.value && discovery.rows.value.length === 0)
const isSearchEmpty = computed(
  () => discovery.rows.value.length > 0 && table.matchingRows.value.length === 0
)

/** How many candidates the search keeps, and how many the backend actually calculated. */
const matchingCount = computed(() => table.matchingRows.value.length)
const displayedCount = computed(() => table.visibleRows.value.length)
const maximumRejected = ref(false)
const calculatedCount = computed(() => discovery.rows.value.length)

/** What the detail region says when nothing is selected, in the results region's own situation. */
const detailPlaceholder = computed(() => {
  // Loading first, for the same reason the results region orders its states that way: while the
  // selector is still being read, "no character is available" is not yet known to be true.
  if (discovery.isLoading.value) return 'Details appear once the calculation has answered.'
  if (!discovery.canCalculate.value)
    return 'No character and discipline is available to calculate, so there is nothing to select.'
  if (discovery.requestError.value !== null)
    return 'No result was loaded for this character, so there is nothing to select.'
  if (isEmptyResult.value)
    return 'This character has nothing left to discover in this discipline, so there is nothing to select.'
  return 'Choose a recipe in the comparison list to see everything the backend supplied for it.'
})

function onSearchInput(event: Event): void {
  const target = event.target
  if (target instanceof HTMLInputElement) table.searchText.value = target.value
}

function onScopeSelected(scopeOptionId: string): void {
  void discovery.selectScope(scopeOptionId)
}

function onSettingsApplied(settings: EffectiveDiscoverySettings): void {
  void discovery.applySettings(settings)
}

function onSort(key: DiscoverySortKey): void {
  table.toggleSort(key)
}

function onSelect(recipeId: number): void {
  table.select(recipeId)
}

function onMaximumDisplayedChange(event: Event): void {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  maximumRejected.value = !table.setMaxDisplayed(Number(target.value))
  if (maximumRejected.value) target.value = String(table.maxDisplayed.value)
}

function onReload(): void {
  void discovery.reload()
}
</script>

<template>
  <div class="screen">
    <PageHeader
      heading="Crafting Discovery"
      intro="Discover a recipe by crafting it once. Level fast or find profitable Recipes."
    >
      <template #actions>
        <button
          type="button"
          class="button--primary"
          data-test="discovery-reload"
          :disabled="discovery.isLoading.value"
          @click="onReload"
        >
          Refresh prices &amp; results
        </button>
      </template>
    </PageHeader>

    <div class="results-split">
      <section
        class="results-split__main stack"
        aria-labelledby="discovery-results-heading"
        data-test="discovery-results-region"
      >
        <!-- Discovery always evaluates one craft per recipe. These are the only calculation
             controls; search affects the displayed rows, not the backend calculation. -->
        <section class="discovery-controls" aria-label="Discovery controls">
          <div class="discovery-controls__top">
            <div class="discovery-controls__scope" data-test="discovery-calculation-controls">
              <DiscoveryScopeSelector
                :options="discovery.scopeOptions.value"
                :selected-id="discovery.selectedScopeId.value"
                @select="onScopeSelected"
              />
            </div>
            <div class="discovery-controls__search" data-test="discovery-display-controls">
              <label class="search">
                <span>Search</span>
                <input
                  type="search"
                  data-test="discovery-search"
                  placeholder="Recipe, level, item id, discipline, state"
                  :value="table.searchText.value"
                  @input="onSearchInput"
                />
              </label>
            </div>
          </div>
          <DiscoverySettingsForm :settings="discovery.settings.value" @apply="onSettingsApplied" />

          <p
            v-if="discovery.selectorError.value !== null"
            class="notice notice--warning"
            data-test="discovery-selector-error"
          >
            The character and discipline options could not be loaded, so no discovery calculation was
            requested — this page has no default character to fall back on.
            <span class="meta detail-line">Backend answer: {{ discovery.selectorError.value }}</span>
            <span class="notice__actions">
              <button type="button" data-test="discovery-selector-retry" @click="onReload">Try again</button>
            </span>
          </p>
        </section>

        <div class="opportunities-toolbar">
          <h2 id="discovery-results-heading">Discoverable recipes</h2>

          <div class="opportunities-toolbar__limit" data-test="discovery-limit">
            <label class="results-limit">
              <span>Show at most</span>
              <input
                type="number"
                min="1"
                step="1"
                data-test="discovery-max-displayed"
                :value="table.maxDisplayed.value"
                :disabled="table.showAll.value"
                @change="onMaximumDisplayedChange"
              />
            </label>
            <label class="show-all">
              <input
                type="checkbox"
                data-test="discovery-show-all"
                :checked="table.showAll.value"
                @change="table.showAll.value = ($event.target as HTMLInputElement).checked"
              />
              <span>Show all</span>
            </label>
            <p
              v-if="maximumRejected"
              class="notice notice--warning opportunities-toolbar__limit-error"
              role="alert"
              data-test="discovery-max-displayed-rejected"
            >
              Enter a maximum of at least 1.
            </p>
          </div>

          <div class="opportunities-toolbar__warning">
            <TradingPostPriceDisclaimer v-if="discovery.hasResult.value" />
          </div>
        </div>

        <!--
          Loading comes first on purpose: the selector read is part of it, and until that has answered
          the page does not yet know whether there is a character discipline to offer. Claiming there
          is none while still looking would state something nothing has established.
        -->
        <p
          v-if="discovery.isLoading.value"
          class="notice notice--info"
          role="status"
          data-test="discovery-loading"
        >
          Looking for recipes this character can discover…
        </p>

        <p
          v-else-if="!discovery.canCalculate.value && discovery.selectorError.value === null"
          class="notice notice--warning"
          data-test="discovery-no-character"
        >
          No character with a crafting discipline is available, so there is nothing to calculate.
          Discovery always applies to one character and one discipline, and this page will not guess
          either. Refresh account data, then try again.
        </p>

        <p
          v-else-if="discovery.requestError.value !== null"
          class="notice notice--error"
          data-test="discovery-request-error"
        >
          The calculation could not be loaded, so no result is shown for this character.
          <span class="meta detail-line">Backend answer: {{ discovery.requestError.value }}</span>
          <span class="notice__actions">
            <button type="button" data-test="discovery-request-retry" @click="onReload">
              Try again
            </button>
          </span>
        </p>

        <p v-else-if="isEmptyResult" class="notice" data-test="discovery-empty">
          The backend returned no discoverable recipes for this character and discipline. Every recipe
          it offers here is already unlocked on the account, above the character's rating, or not
          learnable through normal discovery.
        </p>

        <template v-else-if="discovery.hasResult.value">
          <p class="meta" data-test="discovery-summary">
            Showing {{ displayedCount }} of {{ matchingCount }} matching
            {{ matchingCount === 1 ? 'recipe' : 'recipes' }} · {{ calculatedCount }} returned by the backend
            <span v-if="discovery.effectiveScope.value !== null" data-test="discovery-effective-scope">
              · {{ discovery.effectiveScope.value.discipline }} lvl
              {{ discovery.effectiveScope.value.rating }} —
              {{ discovery.effectiveScope.value.characterName }}
            </span>
          </p>

          <p v-if="isSearchEmpty" class="notice" data-test="discovery-no-matches">
            No recipe matches the current search. The calculation returned {{ calculatedCount }}
            {{ calculatedCount === 1 ? 'recipe' : 'recipes' }} and still holds all of them; change the
            search to see them again.
          </p>

          <div
            v-else
            class="table-region"
            role="region"
            aria-label="Discoverable recipes, scrollable"
            tabindex="0"
          >
            <DiscoveryTable
              :rows="table.visibleRows.value"
              :sort-key="table.sortKey.value"
              :sort-direction="table.sortDirection.value"
              :selected-recipe-id="table.selectedRecipeId.value"
              @sort="onSort"
              @select="onSelect"
            />
          </div>
        </template>
      </section>

      <!--
        `tabindex` because the panel becomes its own scroll container on a wide viewport: a detail
        taller than the viewport has to be scrollable by keyboard as well as by pointer.
      -->
      <SelectedDiscoveryDetail
        class="results-split__aside"
        tabindex="0"
        :row="table.selectedRow.value"
        :hidden-by-search="table.selectionHiddenBySearch.value"
        :placeholder="detailPlaceholder"
        :resolution-phase="resolution.phase.value"
        :resolution-detail="resolution.detail.value"
        :resolution-failure="resolution.failure.value"
        :resolution-recipe-id="resolution.requestedRecipeId.value"
      />
    </div>
  </div>
</template>

<style scoped>
.discovery-controls {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  min-width: 0;
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: transparent; /* Same unfilled panel treatment as Crafting Profit. */
}
.discovery-controls__top {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: end;
  gap: var(--space-4);
}
.discovery-controls__scope, .discovery-controls__search { min-width: 0; }
.discovery-controls__scope { text-align: left; }
.discovery-controls__scope :deep(label) { text-align: left; }
.discovery-controls__scope :deep(select) {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}
.search { display: flex; flex-direction: column; align-items: flex-start; gap: var(--space-2); }
.search input { width: 100%; box-sizing: border-box; }
@media (max-width: 640px) {
  .discovery-controls__top { grid-template-columns: minmax(0, 1fr); }
}

.opportunities-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: var(--space-4);
}
.opportunities-toolbar h2 { margin: 0; }
.results-limit, .show-all {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}
.results-limit input { width: 5rem; }
.opportunities-toolbar__limit {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
}
.opportunities-toolbar__limit-error {
  flex-basis: 100%;
  margin: 0;
  text-align: center;
}
.opportunities-toolbar__warning {
  display: flex;
  justify-content: flex-end;
  min-width: 0;
  justify-self: end;
}
@media (max-width: 1150px) {
  .opportunities-toolbar { grid-template-columns: 1fr; align-items: start; }
  .opportunities-toolbar__limit { justify-content: flex-start; }
  .opportunities-toolbar__limit-error { text-align: left; }
  .opportunities-toolbar__warning { justify-content: flex-start; justify-self: start; }
}
</style>