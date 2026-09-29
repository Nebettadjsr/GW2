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
import { formatCopper } from './formatCopper'
import { useCraftingDiscovery } from './useCraftingDiscovery'
import {
  discoveryCalculationKey,
  useDiscoveryResolution,
  type DiscoveryCalculationInputs
} from './useDiscoveryResolution'
import { useDiscoveryTableView, type DiscoverySortKey } from './useDiscoveryTableView'

/**
 * The Crafting Discovery Helper page (`DOMAIN_SPEC.md` 2.2.2), in the three groups
 * `FRONTEND_UX_GUIDELINES.md` 4 asks for: what is calculated, the candidates to compare, and the
 * details of the one candidate that is selected.
 *
 * The controls panel follows the pattern STORY-WEB-011 settled on Crafting Profit: one panel with a
 * *Calculation* subgroup — the character/discipline scope, the separate inventory character and the
 * settings behind a labelled disclosure whose summary keeps the effective ones readable — and a
 * *Displayed results* subgroup, which here is the search alone. Discovery deliberately has no display
 * filters: hiding candidates by profit is a Profit control, and a negative profit is not a discovery
 * exclusion rule (`DOMAIN_SPEC.md` 37).
 *
 * The screen decides nothing about discovery. Which recipes exist as candidates, the account-wide
 * knowledge rule, the rating filter and normal-discovery eligibility are all the backend's
 * (`DOMAIN_SPEC.md` 34/35); search and sort are pure view state, so changing either cannot issue a
 * request. `api.calculateDiscovery` is called for an opened screen, a changed scope, a changed
 * inventory character, changed settings and an explicit reload only.
 *
 * With no character discipline to select — nothing synced, or a selector read that failed — the page
 * says so and sends **no** calculation, because this route has no default scope to fall back on.
 *
 * The screen is kept alive while another application area is open, so returning to it neither loses
 * the chosen scope, settings, search, sort and selection nor posts the calculation again.
 *
 * The `api` prop exists so a test can supply controlled responses; the browser always gets the real
 * backend client.
 */
const props = withDefaults(defineProps<{ api?: CraftingApi }>(), { api: () => craftingApi })

const discovery = useCraftingDiscovery(props.api)
const table = useDiscoveryTableView(discovery.rows)
const resolution = useDiscoveryResolution(props.api)

onMounted(() => {
  void discovery.open()
})

/**
 * The effective inputs the backend reported for the result set on screen, or null while there is no
 * settled result to attach a detail to. The detail request carries exactly these
 * (`TARGET_ARCHITECTURE.md` 13.1) — the nullable inventory character included, never the browser's own
 * defaults, and never anything derived from a row.
 */
const calculationInputs = computed<DiscoveryCalculationInputs | null>(() => {
  if (discovery.isLoading.value || !discovery.hasResult.value) return null
  const scope = discovery.effectiveScope.value
  const settings = discovery.settings.value
  if (scope === null || settings === null) return null
  return {
    scope,
    inventoryCharacterName: discovery.effectiveInventoryCharacter.value,
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
 * contains the selected recipe and the backend's echoed scope, inventory character and settings only —
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
const calculatedCount = computed(() => discovery.rows.value.length)

/**
 * The settings the backend reported it calculated with, worded for the disclosure's summary line so
 * closing the group never hides what is in effect. Every part comes from that echo; no default is
 * repeated here, and the fixed daily value is reported as the calculation's, not as a choice.
 */
const effectiveSettingsSummary = computed(() => {
  const settings = discovery.settings.value
  if (settings === null) return 'not reported yet'
  return [
    settings.useOwnMats ? 'own materials used' : 'own materials kept',
    settings.allowBuying ? 'buying allowed' : 'buying off',
    `max buy ${formatCopper(settings.maxBuyCopper)}`,
    settings.listingSell ? 'listing sell' : 'instant sell',
    settings.listingBuy ? 'listing buy' : 'instant buy',
    settings.dailyBuyInsteadOfCraft ? 'daily items bought (fixed)' : 'daily items crafted (fixed)'
  ].join(' · ')
})

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

function onInventoryCharacterSelected(characterName: string | null): void {
  void discovery.selectInventoryCharacter(characterName)
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

function onReload(): void {
  void discovery.reload()
}
</script>

<template>
  <div class="screen">
    <PageHeader
      heading="Crafting Discovery"
      intro="Recipes the selected character can still discover at their current crafting rating, with the cost and the immediate profit the backend calculated for each."
    >
      <template #actions>
        <button
          type="button"
          class="button--primary"
          data-test="discovery-reload"
          :disabled="discovery.isLoading.value"
          @click="onReload"
        >
          Reload results
        </button>
      </template>
    </PageHeader>

    <!--
      One panel, two subgroups: what is calculated, and what of the answer is displayed. The search
      belongs to the second one — it narrows the candidates already returned and never reaches the
      backend.
    -->
    <section class="panel" aria-labelledby="discovery-controls-heading">
      <h2 id="discovery-controls-heading" class="panel__title">Calculation controls</h2>

      <fieldset class="calculation-controls" data-test="discovery-calculation-controls">
        <legend>Calculation</legend>

        <DiscoveryScopeSelector
          :options="discovery.scopeOptions.value"
          :selected-id="discovery.selectedScopeId.value"
          :inventory-character-names="discovery.inventoryCharacterNames.value"
          :selected-inventory-character="discovery.selectedInventoryCharacter.value"
          @select="onScopeSelected"
          @select-inventory-character="onInventoryCharacterSelected"
        />

        <details class="settings-disclosure" data-test="discovery-settings-disclosure">
          <summary>
            Price and material settings
            <span class="meta" data-test="discovery-effective-settings">
              {{ effectiveSettingsSummary }}
            </span>
          </summary>
          <DiscoverySettingsForm :settings="discovery.settings.value" @apply="onSettingsApplied" />
        </details>
      </fieldset>

      <fieldset class="display-controls" data-test="discovery-display-controls">
        <legend>Displayed results</legend>
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
      </fieldset>

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

    <div class="results-split">
      <section
        class="results-split__main stack"
        aria-labelledby="discovery-results-heading"
        data-test="discovery-results-region"
      >
        <h2 id="discovery-results-heading">Discoverable recipes</h2>
        <TradingPostPriceDisclaimer v-if="discovery.hasResult.value" />

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
          either. Synchronize the account, then reload.
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
            Showing {{ matchingCount }} of {{ calculatedCount }}
            {{ calculatedCount === 1 ? 'recipe' : 'recipes' }} the backend returned
            <span v-if="discovery.effectiveScope.value !== null" data-test="discovery-effective-scope">
              · {{ discovery.effectiveScope.value.discipline }} lvl
              {{ discovery.effectiveScope.value.rating }} —
              {{ discovery.effectiveScope.value.characterName }}
            </span>
            <span data-test="discovery-effective-inventory">
              · materials
              <template v-if="discovery.effectiveInventoryCharacter.value !== null">
                from {{ discovery.effectiveInventoryCharacter.value }}
              </template>
              <template v-else>from every owned stack</template>
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
              :rows="table.matchingRows.value"
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
        :settings="discovery.settings.value"
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
.calculation-controls,
.display-controls {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.search {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.search input {
  width: 18rem;
  max-width: 100%;
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
