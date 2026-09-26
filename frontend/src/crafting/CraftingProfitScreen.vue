<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, watch } from 'vue'
import { craftingApi, type CraftingApi } from '@/api/craftingApi'
import type { EffectiveSettings } from '@/api/types'
import PageHeader from '@/shell/PageHeader.vue'
import CraftingProfitTable from './CraftingProfitTable.vue'
import ProfitSettingsForm from './ProfitSettingsForm.vue'
import ResultDisplayControls from './ResultDisplayControls.vue'
import ScopeSelector from './ScopeSelector.vue'
import SelectedResultDetail from './SelectedResultDetail.vue'
import { formatCopper } from './formatCopper'
import { useCraftingProfit } from './useCraftingProfit'
import { calculationKey, useProfitResolution, type CalculationInputs } from './useProfitResolution'
import { useProfitTableView, type SortKey } from './useProfitTableView'

/**
 * The Crafting Profit screen, in the three groups `FRONTEND_UX_GUIDELINES.md` 4 asks for: what is
 * calculated, the opportunities to compare, and the details of the one result that is selected.
 *
 * The controls panel holds two subgroups (DOMAIN_SPEC 2.1.1): *Calculation* — scope and the settings
 * behind a labelled disclosure whose summary keeps the effective ones readable — and *Displayed
 * results*, which is the search, the three display filters and the maximum. The comparison table
 * carries the columns worth comparing and scrolls inside its own region so the page still reflows;
 * everything else the response supplied is in the detail region beside it on a wide viewport and
 * below it on a narrow one.
 *
 * The Displayed results subgroup is pure view state (`useProfitTableView`): changing one of its
 * controls cannot issue a request, so `api.calculateProfit` is called for an opened screen, a changed
 * scope, changed settings and an explicit reload only.
 *
 * The screen is kept alive while another application area is open, so returning to it neither loses
 * the chosen scope, settings, search, sort, display controls and selection nor posts the calculation
 * again.
 *
 * The `api` prop exists so a test can supply controlled responses; the browser always gets the real
 * backend client.
 */
const props = withDefaults(defineProps<{ api?: CraftingApi }>(), { api: () => craftingApi })

const profit = useCraftingProfit(props.api)
const table = useProfitTableView(profit.rows)
const resolution = useProfitResolution(props.api)

onMounted(() => {
  void profit.open()
})

/**
 * The effective inputs the backend reported for the result set on screen, or null while there is no
 * settled result to attach a detail to. The detail request carries exactly these
 * (`TARGET_ARCHITECTURE.md` 13.1) — never the browser's own defaults, and never anything derived
 * from a row.
 */
const calculationInputs = computed<CalculationInputs | null>(() => {
  if (profit.isLoading.value || !profit.hasResult.value) return null
  const scope = profit.effectiveScope.value
  const settings = profit.settings.value
  if (scope === null || settings === null) return null
  return { scope, settings }
})

/**
 * Whether this screen is the one the user is looking at. It is kept alive behind another
 * destination, so "left the view" has to be tracked rather than assumed from unmounting.
 */
let isViewActive = true

/**
 * Asks for the selected recipe's detail, or invalidates the one on screen when there is nothing
 * valid to ask about. One request per selection, never one per row: the comparison table itself
 * causes no detail request at all.
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
 * contains the selected recipe and the backend's echoed scope and settings only — sorting,
 * searching, the display filters and the display maximum are absent from it, so re-ordering the
 * table leaves a valid detail alone. A started reload empties `calculationInputs` while it is in
 * flight, which clears the tree immediately and asks again against the answer that replaces it.
 */
watch(
  () => `${table.selectedRecipeId.value ?? 'none'}|${calculationKey(calculationInputs.value)}`,
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

/** Empty means the backend answered successfully with no rows, not that nothing was asked. */
const isEmptyResult = computed(() => profit.hasResult.value && profit.rows.value.length === 0)
const isFilteredEmpty = computed(
  () => profit.rows.value.length > 0 && table.matchingRows.value.length === 0
)

/**
 * The three counts kept apart, because they answer different questions: how many rows are on screen,
 * how many the search and filters keep, and how many the backend actually calculated. Only the last
 * is the result set; the other two are what the display controls made of it.
 */
const displayedCount = computed(() => table.visibleRows.value.length)
const matchingCount = computed(() => table.matchingRows.value.length)
const calculatedCount = computed(() => profit.rows.value.length)

/** What is currently restricting the list, so a short or empty list is never unexplained. */
const activeRestrictions = computed<string[]>(() => {
  const active: string[] = []
  const needle = table.searchText.value.trim()
  if (needle !== '') active.push(`search “${needle}”`)
  if (table.hideZeroCraftable.value) active.push('hiding a craftable count of 0')
  if (table.hideNotAllowed.value) active.push('hiding recipes reported as not allowed')
  if (table.hideNonPositiveProfit.value) active.push('hiding a profit per craft of 0 or less')
  return active
})

/**
 * The settings the backend reported it calculated with, worded for the disclosure's summary line so
 * closing the group never hides what is in effect. Every part comes from that echo; no default is
 * repeated here.
 */
const effectiveSettingsSummary = computed(() => {
  const settings = profit.settings.value
  if (settings === null) return 'not reported yet'
  return [
    settings.useOwnMats ? 'own materials used' : 'own materials kept',
    settings.allowBuying ? 'buying allowed' : 'buying off',
    `max buy ${formatCopper(settings.maxBuyCopper)}`,
    settings.listingSell ? 'listing sell' : 'instant sell',
    settings.listingBuy ? 'listing buy' : 'instant buy',
    settings.dailyBuyInsteadOfCraft ? 'daily items bought' : 'daily items crafted',
    settings.allowNonTradeableMaterials
      ? 'non-Trading-Post materials allowed'
      : 'non-Trading-Post materials excluded'
  ].join(' · ')
})

/** What the detail region says when nothing is selected, in the results region's own situation. */
const detailPlaceholder = computed(() => {
  if (profit.isLoading.value) return 'Details appear once the calculation has answered.'
  if (profit.requestError.value !== null)
    return 'No result was loaded for this scope, so there is nothing to select.'
  if (isEmptyResult.value) return 'The backend returned no recipes for this scope, so there is nothing to select.'
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
    <PageHeader
      heading="Crafting Profit"
      intro="Crafting opportunities the backend calculated for the selected scope, with the profit it reported for each."
    >
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

    <!--
      One panel, two subgroups: what is calculated, and what of the answer is displayed
      (DOMAIN_SPEC 2.1.1). The search belongs to the second one — like the filters and the maximum it
      narrows the rows already returned and never reaches the backend.
    -->
    <section class="panel" aria-labelledby="crafting-controls-heading">
      <h2 id="crafting-controls-heading" class="panel__title">Calculation controls</h2>

      <fieldset class="calculation-controls" data-test="calculation-controls">
        <legend>Calculation</legend>

        <ScopeSelector
          :options="profit.scopeOptions.value"
          :selected-id="profit.selectedScopeId.value"
          @select="onScopeSelected"
        />

        <details class="settings-disclosure" data-test="settings-disclosure">
          <summary>
            Price and material settings
            <span class="meta" data-test="effective-settings">{{ effectiveSettingsSummary }}</span>
          </summary>
          <ProfitSettingsForm :settings="profit.settings.value" @apply="onSettingsApplied" />
        </details>
      </fieldset>

      <ResultDisplayControls
        :hide-zero-craftable="table.hideZeroCraftable.value"
        :hide-not-allowed="table.hideNotAllowed.value"
        :hide-non-positive-profit="table.hideNonPositiveProfit.value"
        :max-displayed="table.maxDisplayed.value"
        :show-all="table.showAll.value"
        @update:hide-zero-craftable="table.hideZeroCraftable.value = $event"
        @update:hide-not-allowed="table.hideNotAllowed.value = $event"
        @update:hide-non-positive-profit="table.hideNonPositiveProfit.value = $event"
        @update:max-displayed="table.setMaxDisplayed($event)"
        @update:show-all="table.showAll.value = $event"
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
        <h2 id="crafting-results-heading">Opportunities</h2>

        <p v-if="profit.isLoading.value" class="notice notice--info" role="status" data-test="loading">
          Loading crafting opportunities…
        </p>

        <p
          v-else-if="profit.requestError.value !== null"
          class="notice notice--error"
          data-test="request-error"
        >
          The calculation could not be loaded, so no result is shown for this scope.
          <span class="meta detail-line">Backend answer: {{ profit.requestError.value }}</span>
          <span class="notice__actions">
            <button type="button" data-test="request-retry" @click="onReload">Try again</button>
          </span>
        </p>

        <p v-else-if="isEmptyResult" class="notice" data-test="empty">
          The backend returned no recipes for this scope.
        </p>

        <template v-else-if="profit.hasResult.value">
          <p class="meta" data-test="summary">
            Showing {{ displayedCount }} of {{ matchingCount }} matching
            {{ matchingCount === 1 ? 'recipe' : 'recipes' }} · {{ calculatedCount }} calculated for
            this scope
            <span v-if="profit.effectiveScope.value !== null" data-test="effective-scope">
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
            {{ calculatedCount }} {{ calculatedCount === 1 ? 'recipe' : 'recipes' }} for this scope and
            still holds all of them; change the search or switch a filter off to see them again.
            <span class="meta detail-line" data-test="active-restrictions">
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

      <!--
        `tabindex` because the panel becomes its own scroll container on a wide viewport: a detail
        taller than the viewport has to be scrollable by keyboard as well as by pointer.
      -->
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
/* The two subgroups of the controls panel are told apart by their own boundary and legend. */
.calculation-controls {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.search input {
  width: 16rem;
}

/* The settings stay one group and one control, and their effect stays readable while closed. */
.settings-disclosure > summary {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-1) var(--space-3);
  padding: var(--space-2) 0;
  cursor: pointer;
  font-weight: 600;
}

.settings-disclosure > summary > .meta {
  font-weight: 400;
}

.settings-disclosure[open] > summary {
  margin-bottom: var(--space-3);
}
</style>
