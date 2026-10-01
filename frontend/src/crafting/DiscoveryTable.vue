<script setup lang="ts">
import { computed } from 'vue'
import type { CraftingRow } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import { formatCopper, formatSignedCopper, moneyTone } from './formatCopper'
import { recipeLabel } from './recipeLabel'
import { rowDiagnostic } from './rowState'
import type { DiscoverySortKey, SortDirection } from './useDiscoveryTableView'

/**
 * One row per missing discoverable recipe. Every amount describes one discovery attempt; the sell
 * value includes the recipe output quantity. Values come from the backend, and unprofitable
 * discoveries remain visible because profit is not an eligibility rule.
 *
 * Selecting a row is keyboard-operable through its recipe button and also works by clicking the row.
 */
const props = defineProps<{
  rows: readonly CraftingRow[]
  sortKey: DiscoverySortKey
  sortDirection: SortDirection
  selectedRecipeId: number | null
}>()

const emit = defineEmits<{ sort: [key: DiscoverySortKey]; select: [recipeId: number] }>()

/** Each row's rendered text, prepared once per row set rather than on every re-render. */
const displayRows = computed(() =>
  props.rows.map((row) => ({
    recipeId: row.recipeId,
    // The recipe's *output item* — a recipe has no icon of its own, and no URL is built from an id.
    outputItemId: row.outputItemId,
    iconUrl: row.iconUrl,
    name: recipeLabel(row),
    level: row.minRating,
    buyCost: formatCopper(row.buyCostCopper),
    sellValue: formatCopper(row.totalSellValueCopper),
    profit: formatSignedCopper(row.totalProfitCopper),
    profitTone: moneyTone(row.totalProfitCopper),
    diagnostic: rowDiagnostic(row)
  }))
)

interface Column {
  key: DiscoverySortKey
  label: string
  /** A qualifier for the recipe level. */
  note?: string
  numeric: boolean
}

const columns: Column[] = [
  { key: 'outputName', label: 'Recipe', numeric: false },
  { key: 'minRating', label: 'Level', note: 'recipe minimum', numeric: true },
  { key: 'buyCostCopper', label: 'Materials to buy', numeric: true },
  { key: 'totalSellValueCopper', label: 'Sell value', numeric: true },
  { key: 'totalProfitCopper', label: 'Profit', numeric: true }
]

function ariaSort(
  column: Column,
  activeKey: DiscoverySortKey,
  direction: SortDirection
): 'ascending' | 'descending' | 'none' {
  if (column.key !== activeKey) return 'none'
  return direction === 'asc' ? 'ascending' : 'descending'
}

/**
 * Anything a row nests that is interactive in its own right. A click that started inside one of
 * these is that control's click, so the row leaves it alone.
 */
const NESTED_CONTROLS = 'a, button, input, select, textarea, label, summary, [role="button"], [role="link"]'

function onRowClick(event: MouseEvent, recipeId: number): void {
  const target = event.target
  if (target instanceof Element && target.closest(NESTED_CONTROLS) !== null) return
  emit('select', recipeId)
}
</script>

<template>
  <table class="discovery-table" data-test="discovery-table">
    <!--
      The table's accessible description, visually hidden because this table is the horizontal scroll
      container's content: a visible caption would be laid out at the full column width and would stop
      wrapping.
    -->
    <caption class="visually-hidden">
      Recipes this character can still discover. Each row describes one discovery craft, and its
      output sell value includes the recipe's output quantity. A negative profit does not disqualify a discovery. Each recipe name is a button that
      opens its details; a click anywhere else on a row does the same.
    </caption>
    <thead>
      <tr>
        <th
          v-for="column in columns"
          :key="column.key"
          :class="{ numeric: column.numeric, sorted: column.key === sortKey }"
          :aria-sort="ariaSort(column, sortKey, sortDirection)"
          scope="col"
        >
          <button
            type="button"
            :data-test="`discovery-sort-${column.key}`"
            @click="emit('sort', column.key)"
          >
            <span class="column-label">
              {{ column.label }}
              <span v-if="column.note !== undefined" class="column-note">{{ column.note }}</span>
            </span>
            <span v-if="column.key === sortKey" class="sort-marker" aria-hidden="true">
              {{ sortDirection === 'asc' ? '▲' : '▼' }}
            </span>
          </button>
        </th>
      </tr>
    </thead>
    <tbody>
      <tr
        v-for="row in displayRows"
        :key="row.recipeId"
        data-test="discovery-row"
        :class="{ selected: row.recipeId === selectedRecipeId }"
        :aria-current="row.recipeId === selectedRecipeId ? 'true' : undefined"
        @click="onRowClick($event, row.recipeId)"
      >
        <th scope="row" class="recipe">
          <button
            type="button"
            class="recipe-select"
            data-test="discovery-select-row"
            :aria-current="row.recipeId === selectedRecipeId ? 'true' : undefined"
            @click="emit('select', row.recipeId)"
          >
            <span class="recipe-marker" aria-hidden="true">{{
              row.recipeId === selectedRecipeId ? '▸' : ''
            }}</span>
            <!--
              Inside the button so it sits with the name it belongs to, and decorative, so the
              control's accessible name stays the recipe alone. Rows load their image lazily.
            -->
            <ItemIcon :icon-url="row.iconUrl" :item-id="row.outputItemId" loading="lazy" />
            <span class="recipe-text">
              <span class="recipe-name">{{ row.name }}</span>
              <span class="recipe-ids">recipe {{ row.recipeId }}</span>
            </span>
            <span v-if="row.recipeId === selectedRecipeId" class="visually-hidden">Selected</span>
          </button>
          <!--
            The same minimal diagnostic Crafting Profit shows, outside the selection button so it is
            read as information about the recipe rather than as part of the control's name.
          -->
          <span
            v-if="row.diagnostic !== null"
            :class="`status status--${row.diagnostic.tone} row-diagnostic`"
            data-test="discovery-row-diagnostic"
          >
            {{ row.diagnostic.label }}
          </span>
        </th>
        <td class="numeric" data-test="discovery-level">{{ row.level }}</td>
        <td class="numeric" data-test="discovery-buy-cost">
          <span class="money money--cost">{{ row.buyCost }}</span>
        </td>
        <td class="numeric" data-test="discovery-sell-value">
          <span class="money">{{ row.sellValue }}</span>
        </td>
        <td class="numeric" data-test="discovery-profit">
          <span :class="`money money--${row.profitTone}`">{{ row.profit }}</span>
        </td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.discovery-table {
  border-collapse: collapse;
  /* Sized by its content, not stretched: the region around it owns the available width. */
  min-width: 100%;
  font-size: var(--text-sm);
}

.discovery-table th,
.discovery-table td {
  border-bottom: 1px solid var(--color-border);
  padding: var(--space-2) var(--space-3);
  text-align: left;
  vertical-align: top;
}

/*
 * Not `position: sticky`: the enclosing region is the horizontal scroll container, so a sticky header
 * inside it would have nothing to stick to while the page scrolls vertically — the same trade
 * `CraftingProfitTable` makes.
 */
.discovery-table thead th {
  background: var(--color-raised);
  white-space: nowrap;
}

.discovery-table thead button {
  align-items: flex-start;
  gap: var(--space-2);
  min-height: 0;
  padding: 0;
  border: none;
  border-radius: 0;
  background: none;
  color: inherit;
  font: inherit;
  font-weight: 600;
}

.discovery-table thead button:hover {
  border: none;
  color: var(--color-secondary);
}

.column-label {
  display: inline-flex;
  flex-direction: column;
  align-items: inherit;
}

/* The basis of the column, so a total and a per-craft value can never be confused for each other. */
.column-note {
  color: var(--color-muted);
  font-size: var(--text-sm);
  font-weight: 400;
  text-transform: lowercase;
}

.numeric .column-label {
  align-items: flex-end;
}

/* Sits under the recipe name rather than in a column of its own; absent on an ordinary row. */
.row-diagnostic {
  margin-left: 1.5rem;
}

/* The whole row selects, so the whole row says so to a pointer. The keyboard route is the button. */
.discovery-table tbody tr {
  cursor: pointer;
}

.discovery-table tbody tr:hover {
  background: var(--color-raised);
}

/* The row header is the recipe, so every value in the row has a name as well as a column. */
.recipe {
  font-weight: 400;
}

.recipe-select {
  width: 100%;
  justify-content: flex-start;
  gap: var(--space-2);
  min-height: 0;
  padding: var(--space-1) var(--space-2);
  border-color: transparent;
  background: none;
  text-align: left;
}

.recipe-select:hover {
  background: var(--color-raised);
}

.recipe-marker {
  width: 0.75rem;
  color: var(--color-highlight);
}

.recipe-text {
  display: flex;
  flex-direction: column;
}

.recipe-name {
  font-weight: 600;
}

.recipe-ids {
  color: var(--color-muted);
  font-size: var(--text-sm);
}

/* Selection is a surface, a border, a marker glyph and `aria-current` — never color alone. */
tr.selected {
  background: var(--color-raised);
}

tr.selected th,
tr.selected td {
  border-bottom-color: var(--color-highlight);
}

tr.selected .recipe-select {
  border-color: var(--color-highlight);
}
</style>
