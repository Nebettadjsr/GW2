<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, watch } from 'vue'
import { craftingApi, type CraftingApi } from '@/api/craftingApi'
import type { EffectiveSettings } from '@/api/types'
import TradingPostPriceDisclaimer from '@/items/TradingPostPriceDisclaimer.vue'
import PageHeader from '@/shell/PageHeader.vue'
import CraftingProfitTable from './CraftingProfitTable.vue'
import ProfitSettingsForm from './ProfitSettingsForm.vue'
import ResultDisplayControls from './ResultDisplayControls.vue'
import ScopeSelector from './ScopeSelector.vue'
import SelectedResultDetail from './SelectedResultDetail.vue'
import { useCraftingProfit } from './useCraftingProfit'
import { calculationKey, useProfitResolution, type CalculationInputs } from './useProfitResolution'
import { useProfitTableView, type SortKey } from './useProfitTableView'

const props = withDefaults(defineProps<{ api?: CraftingApi }>(), { api: () => craftingApi })

const profit = useCraftingProfit(props.api)
const table = useProfitTableView(profit.rows)
const resolution = useProfitResolution(props.api)

onMounted(() => {
  void profit.open()
})

const calculationInputs = computed<CalculationInputs | null>(() => {
  if (profit.isLoading.value || !profit.hasResult.value) return null
  const scope = profit.effectiveScope.value
  const settings = profit.settings.value
  if (scope === null || settings === null) return null
  return { scope, settings }
})

let isViewActive = true

function syncResolution(): void {
  const recipeId = table.selectedRecipeId.value
  const inputs = calculationInputs.value

  if (!isViewActive || recipeId === null || inputs === null) {
    resolution.invalidate()
    return
  }

  void resolution.request(recipeId, inputs)
}

watch(
  () => `${table.selectedRecipeId.value ?? 'none'}|${calculationKey(calculationInputs.value)}`,
  () => syncResolution()
)

onDeactivated(() => {
  isViewActive = false
  resolution.invalidate()
})

onActivated(() => {
  isViewActive = true
  syncResolution()
})

const isEmptyResult = computed(() => profit.hasResult.value && profit.rows.value.length === 0)

const isFilteredEmpty = computed(
  () => profit.rows.value.length > 0 && table.matchingRows.value.length === 0
)

const displayedCount = computed(() => table.visibleRows.value.length)
const matchingCount = computed(() => table.matchingRows.value.length)
const calculatedCount = computed(() => profit.rows.value.length)

const activeRestrictions = computed<string[]>(() => {
  const active: string[] = []
  const needle = table.searchText.value.trim()

  if (needle !== '') active.push(`search “${needle}”`)
  if (table.hideZeroCraftable.value) active.push('hiding a craftable count of 0')
  if (table.hideNotAllowed.value) active.push('hiding recipes reported as not allowed')
  if (table.hideNonPositiveProfit.value) active.push('hiding a profit per craft of 0 or less')

  return active
})

const detailPlaceholder = computed(() => {
  if (profit.isLoading.value) return 'Details appear once the calculation has answered.'

  if (profit.requestError.value !== null) {
    return 'No result was loaded for this scope, so there is nothing to select.'
  }

  if (isEmptyResult.value) {
    return 'The backend returned no recipes for this scope, so there is nothing to select.'
  }

  return 'Choose a recipe in the comparison table to see everything the backend supplied for it.'
})

function onSearchInput(event: Event): void {
  const target = event.target
  if (target instanceof HTMLInputElement) table.searchText.value = target.value
}

function onScopeSelected(scopeOptionId: string): void {
  void profit.selectScope(scopeOptionId)
}

function onSettingsApplied(settings: EffectiveSettings): void {
  void profit.applySettings(settings)
}

function onSort(key: SortKey): void {
  table.toggleSort(key)
}

function onSelect(recipeId: number): void {
  table.select(recipeId)
}

function onReload(): void {
  void profit.reload()
}
</script>

<template>
  <div class="screen">
    <PageHeader heading="Crafting Profit">
      <template #actions>
        <button
          type="button"
          class="button--primary"
          data-test="reload"
          :disabled="profit.isLoading.value"
          @click="onReload"
        >
          Reload results
        </button>
      </template>
    </PageHeader>

    <section class="controls-panel" aria-label="Crafting profit controls">
      <div class="controls-grid">
        <section
          class="control-column"
          aria-labelledby="calculation-heading"
          data-test="calculation-controls"
        >
          <h3 id="calculation-heading" class="control-column__title">Calculation</h3>

          <div class="calculation-layout">
            <div class="calculation-layout__main">
              <ScopeSelector
                :options="profit.scopeOptions.value"
                :selected-id="profit.selectedScopeId.value"
                @select="onScopeSelected"
              />

              <ProfitSettingsForm
                :settings="profit.settings.value"
                layout-part="values"
                @apply="onSettingsApplied"
              />
            </div>

            <ProfitSettingsForm
              :settings="profit.settings.value"
              layout-part="checks"
              @apply="onSettingsApplied"
            />
          </div>
        </section>

        <section
          class="control-column control-column--display"
          aria-labelledby="display-options-heading"
        >
          <h3 id="display-options-heading" class="control-column__title">Display options</h3>

          <ResultDisplayControls
            :hide-zero-craftable="table.hideZeroCraftable.value"
            :hide-not-allowed="table.hideNotAllowed.value"
            :hide-non-positive-profit="table.hideNonPositiveProfit.value"
            @update:hide-zero-craftable="table.hideZeroCraftable.value = $event"
            @update:hide-not-allowed="table.hideNotAllowed.value = $event"
            @update:hide-non-positive-profit="table.hideNonPositiveProfit.value = $event"
          >
            <template #search>
              <label class="search">
                <span>Search</span>
                <input
                  type="search"
                  data-test="search"
                  placeholder="Recipe, item id, discipline, state"
                  :value="table.searchText.value"
                  @input="onSearchInput"
                />
              </label>
            </template>
          </ResultDisplayControls>
        </section>
      </div>

      <p
        v-if="profit.selectorError.value !== null"
        class="notice notice--warning"
        data-test="selector-error"
      >
        The scope options could not be loaded, so only the backend's default scope is offered.
        <span class="meta detail-line">Backend answer: {{ profit.selectorError.value }}</span>
      </p>
    </section>

    <div class="results-split">
      <section
        class="results-split__main stack"
        aria-labelledby="crafting-results-heading"
        data-test="results-region"
      >
        <div class="opportunities-toolbar">
          <h2 id="crafting-results-heading">Opportunities</h2>

          <div class="opportunities-toolbar__limit">
            <label class="results-limit">
              <span>Show at most</span>
              <input
                type="number"
                min="1"
                step="1"
                data-test="max-displayed"
                :value="table.maxDisplayed.value"
                :disabled="table.showAll.value"
                @change="
                  table.setMaxDisplayed(
                    Number(($event.target as HTMLInputElement).value)
                  )
                "
              />
            </label>

            <label class="show-all">
              <input
                type="checkbox"
                data-test="show-all"
                :checked="table.showAll.value"
                @change="
                  table.showAll.value =
                    ($event.target as HTMLInputElement).checked
                "
              />
              <span>Show all</span>
            </label>
          </div>

          <div class="opportunities-toolbar__warning">
            <TradingPostPriceDisclaimer v-if="profit.hasResult.value" />
          </div>
        </div>
        <p
          v-if="profit.isLoading.value"
          class="notice notice--info"
          role="status"
          data-test="loading"
        >
          Loading crafting opportunities…
        </p>

        <p
          v-else-if="profit.requestError.value !== null"
          class="notice notice--error"
          data-test="request-error"
        >
          The calculation could not be loaded, so no result is shown for this scope.

          <span class="meta detail-line">
            Backend answer: {{ profit.requestError.value }}
          </span>

          <span class="notice__actions">
            <button type="button" data-test="request-retry" @click="onReload">
              Try again
            </button>
          </span>
        </p>

        <p v-else-if="isEmptyResult" class="notice" data-test="empty">
          The backend returned no recipes for this scope.
        </p>

        <template v-else-if="profit.hasResult.value">
          <p class="meta" data-test="summary">
            Showing {{ displayedCount }} of {{ matchingCount }} matching
            {{ matchingCount === 1 ? 'recipe' : 'recipes' }} ·
            {{ calculatedCount }} calculated for this scope

            <span
              v-if="profit.effectiveScope.value !== null"
              data-test="effective-scope"
            >
              · scope {{ profit.effectiveScope.value.kind }}

              <template v-if="profit.effectiveScope.value.discipline !== null">
                / {{ profit.effectiveScope.value.discipline }}
              </template>

              <template v-if="profit.effectiveScope.value.characterName !== null">
                / {{ profit.effectiveScope.value.characterName }}
              </template>
            </span>
          </p>

          <p v-if="isFilteredEmpty" class="notice" data-test="no-matches">
            No recipe matches the current search and display filters. The calculation returned
            {{ calculatedCount }}
            {{ calculatedCount === 1 ? 'recipe' : 'recipes' }} for this scope and still holds all
            of them; change the search or switch a filter off to see them again.

            <span
              class="meta detail-line"
              data-test="active-restrictions"
            >
              Currently applied: {{ activeRestrictions.join(' · ') }}
            </span>
          </p>

          <template v-else>
            <div
              class="table-region"
              role="region"
              aria-label="Crafting opportunities, scrollable"
              tabindex="0"
            >
              <CraftingProfitTable
                :rows="table.visibleRows.value"
                :sort-key="table.sortKey.value"
                :sort-direction="table.sortDirection.value"
                :selected-recipe-id="table.selectedRecipeId.value"
                @sort="onSort"
                @select="onSelect"
              />
            </div>
          </template>
        </template>
      </section>

      <SelectedResultDetail
        class="results-split__aside"
        tabindex="0"
        :row="table.selectedRow.value"
        :hidden-reason="table.selectionHiddenReason.value"
        :settings="profit.settings.value"
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

.calculation-layout {
  display: grid;
  grid-template-columns: minmax(0, 55fr) minmax(0, 45fr);
  gap: var(--space-5);
  align-items: start;
}

.calculation-layout__main {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

@media (max-width: 1100px) {
  .calculation-layout {
    grid-template-columns: 1fr;
  }
}

.opportunities-toolbar {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: var(--space-4);
}

.opportunities-toolbar h2 {
  margin: 0;
}

.opportunities-toolbar__limit {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.results-limit,
.show-all {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}

.results-limit input {
  width: 5rem;
}

.opportunities-toolbar__warning {
  display: flex;
  justify-content: flex-end;
  min-width: 0;
}

.controls-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.controls-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-3);
}

.control-column {
  min-width: 0;
  padding: var(--space-3) var(--space-4);

  border: 1px solid var(--color-border);
  border-radius: 10px;
}

.control-column--display {
  border-left: 1px solid var(--color-border);
}

.control-column__title {
  margin: 0 0 var(--space-3);
  color: var(--color-muted);
  font-size: var(--text-sm);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.search {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
}

.search > span {
  flex: 0 0 auto;
}

.search input {
  width: 20rem;
  max-width: 100%;
}

@media (max-width: 900px) {
  .controls-grid {
    grid-template-columns: 1fr;
  }

  .opportunities-toolbar {
    grid-template-columns: 1fr;
    align-items: start;
  }

  .opportunities-toolbar__warning {
    justify-content: flex-start;
  }
}
</style>