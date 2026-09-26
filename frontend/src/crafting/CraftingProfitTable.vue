<script setup lang="ts">
import { computed } from 'vue'
import type { CraftingRow } from '@/api/types'
import { formatCopper, formatCount, formatSignedCopper, moneyTone } from './formatCopper'
import { recipeLabel } from './recipeLabel'
import { rowDiagnostic } from './rowState'
import type { SortDirection, SortKey } from './useProfitTableView'

/**
 * The comparison table: one row per recipe the backend returned, in the columns
 * `DOMAIN_SPEC.md` 2.1.1 requires for comparing opportunities — recipe/item, craftable count, own
 * materials value, profit per craft, total sell value and total profit
 * (`FRONTEND_UX_GUIDELINES.md` 4).
 *
 * Everything else the response carries — the trading-post quote, the buy cost, the material lists,
 * the ordinary blocking reasons and every raw state code — is in the selected-result detail instead
 * of in every row. Nothing is dropped from the client; it is moved out of the scanning view. There
 * is no general State column: the few situations a row's own numbers cannot express keep a minimal
 * diagnostic beside the recipe name (`rowState.rowDiagnostic`).
 *
 * Every cell shows a supplied value or the explicit "not supplied" marker; nothing is derived,
 * defaulted to zero, or recomputed here. `totalSellValueCopper` and `totalProfitCopper` in
 * particular are the backend's own totals, not this table's product of a count and a per-craft
 * value, and the supplied own-materials value keeps its per-craft basis rather than being scaled
 * into a total it was never stated as. Selecting a row is a real button carrying the recipe's
 * name, so selection is keyboard-operable without a custom widget; clicking anywhere else on the
 * row does the same thing with the mouse. The row itself is deliberately not a control — the button
 * stays the one thing in the tab order and the one thing with an accessible name, and the row click
 * is an additional pointer affordance rather than a second, nameless widget.
 */
const props = defineProps<{
  rows: readonly CraftingRow[]
  sortKey: SortKey
  sortDirection: SortDirection
  selectedRecipeId: number | null
}>()

const emit = defineEmits<{ sort: [key: SortKey]; select: [recipeId: number] }>()

/** Each row's rendered text, prepared once per row set rather than on every re-render. */
const displayRows = computed(() =>
  props.rows.map((row) => ({
    recipeId: row.recipeId,
    name: recipeLabel(row),
    disciplines: row.disciplines,
    craftable: formatCount(row.craftableCount),
    ownMaterials: formatCopper(row.matsSellValueCopper),
    profit: formatSignedCopper(row.profitCopper),
    profitTone: moneyTone(row.profitCopper),
    sellValue: formatCopper(row.totalSellValueCopper),
    total: formatSignedCopper(row.totalProfitCopper),
    totalTone: moneyTone(row.totalProfitCopper),
    diagnostic: rowDiagnostic(row)
  }))
)

interface Column {
  key: SortKey
  label: string
  /** The basis of a money column, so a per-craft value is never read as a total. */
  note?: string
  numeric: boolean
  /** A total: emphasized, because these are the two figures the table exists to compare. */
  total?: boolean
}

const columns: Column[] = [
  { key: 'outputName', label: 'Recipe', numeric: false },
  { key: 'disciplines', label: 'Disciplines', numeric: false },
  { key: 'craftableCount', label: 'Craftable', note: 'crafts', numeric: true },
  { key: 'matsSellValueCopper', label: 'Own materials', note: 'cost, per craft', numeric: true },
  { key: 'profitCopper', label: 'Profit', note: 'per craft', numeric: true },
  { key: 'totalSellValueCopper', label: 'Total sell value', note: 'all crafts', numeric: true, total: true },
  { key: 'totalProfitCopper', label: 'Total profit', note: 'all crafts', numeric: true, total: true }
]

function ariaSort(
  column: Column,
  activeKey: SortKey,
  direction: SortDirection
): 'ascending' | 'descending' | 'none' {
  if (column.key !== activeKey) return 'none'
  return direction === 'asc' ? 'ascending' : 'descending'
}

/**
 * Anything a row nests that is interactive in its own right. A click that started inside one of
 * these is that control's click, so the row leaves it alone: the recipe button selects once rather
 * than twice, and a link added later keeps following its own href.
 */
const NESTED_CONTROLS = 'a, button, input, select, textarea, label, summary, [role="button"], [role="link"]'

function onRowClick(event: MouseEvent, recipeId: number): void {
  const target = event.target
  if (target instanceof Element && target.closest(NESTED_CONTROLS) !== null) return
  emit('select', recipeId)
}
</script>

<template>
  <table class="profit-table" data-test="profit-table">
    <!--
      The table's accessible description. It is visually hidden because this table is the horizontal
      scroll container's content: a visible caption would be laid out at the full column width and
      would stop wrapping. It is the one place these sentences are stated: DOMAIN_SPEC 2.1.1 removed
      the permanent on-screen selection/keyboard instructions, not the accessible description of the
      table, and the column headings carry the per-craft/total basis visually.
    -->
    <caption class="visually-hidden">
      Crafting opportunities. Own materials value and profit are stated per single craft; total sell
      value and total profit are the backend's own totals for every craft it counted. Each recipe
      name is a button that opens its details; a click anywhere else on a row does the same.
    </caption>
    <thead>
      <tr>
        <th
          v-for="column in columns"
          :key="column.key"
          :class="{ numeric: column.numeric, sorted: column.key === sortKey, 'is-total': column.total }"
          :aria-sort="ariaSort(column, sortKey, sortDirection)"
          scope="col"
        >
          <button type="button" :data-test="`sort-${column.key}`" @click="emit('sort', column.key)">
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
        data-test="profit-row"
        :class="{ selected: row.recipeId === selectedRecipeId }"
        :aria-current="row.recipeId === selectedRecipeId ? 'true' : undefined"
        @click="onRowClick($event, row.recipeId)"
      >
        <th scope="row" class="recipe">
          <button
            type="button"
            class="recipe-select"
            data-test="select-row"
            :aria-current="row.recipeId === selectedRecipeId ? 'true' : undefined"
            @click="emit('select', row.recipeId)"
          >
            <span class="recipe-marker" aria-hidden="true">{{
              row.recipeId === selectedRecipeId ? '▸' : ''
            }}</span>
            <span class="recipe-text">
              <span class="recipe-name">{{ row.name }}</span>
              <span class="recipe-ids">recipe {{ row.recipeId }}</span>
            </span>
            <span v-if="row.recipeId === selectedRecipeId" class="visually-hidden">Selected</span>
          </button>
          <!--
            The minimal diagnostic of `rowState.rowDiagnostic`, outside the selection button so it is
            read as information about the recipe rather than as part of the control's name. Most rows
            have none; this is not the removed State column.
          -->
          <span
            v-if="row.diagnostic !== null"
            :class="`status status--${row.diagnostic.tone} row-diagnostic`"
            data-test="row-diagnostic"
          >
            {{ row.diagnostic.label }}
          </span>
        </th>
        <td>{{ row.disciplines }}</td>
        <td class="numeric" data-test="craftable-count">{{ row.craftable }}</td>
        <td class="numeric" data-test="own-materials">
          <span class="money money--cost">{{ row.ownMaterials }}</span>
        </td>
        <td class="numeric" data-test="profit-per-craft">
          <span :class="`money money--${row.profitTone}`">{{ row.profit }}</span>
        </td>
        <td class="numeric is-total" data-test="total-sell-value">
          <span class="money">{{ row.sellValue }}</span>
        </td>
        <td class="numeric is-total" data-test="total-profit">
          <span :class="`money money--${row.totalTone}`">{{ row.total }}</span>
        </td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.profit-table {
  border-collapse: collapse;
  /* Sized by its content, not stretched: the region around it owns the available width. */
  min-width: 100%;
  font-size: var(--text-sm);
}

.profit-table th,
.profit-table td {
  border-bottom: 1px solid var(--color-border);
  padding: var(--space-2) var(--space-3);
  text-align: left;
  vertical-align: top;
}

/*
 * Not `position: sticky`: the enclosing region is the horizontal scroll container, so a sticky header
 * inside it would have nothing to stick to while the page scrolls vertically. Keeping the page's own
 * scrolling was the deliberate trade (STORY-WEB-004).
 */
.profit-table thead th {
  background: var(--color-raised);
  white-space: nowrap;
}

.profit-table thead button {
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

.profit-table thead button:hover {
  border: none;
  color: var(--color-secondary);
}

.column-label {
  display: inline-flex;
  flex-direction: column;
  align-items: inherit;
}

/* The basis of the column, so "Profit" and "Total profit" can never be confused for each other. */
.column-note {
  color: var(--color-muted);
  font-size: var(--text-sm);
  font-weight: 400;
  text-transform: lowercase;
}

.numeric .column-label {
  align-items: flex-end;
}

/*
 * The two totals carry the weight, in the header and in the cell, so "all crafts" and "per craft"
 * are told apart by type as well as by the note under the heading — never by color.
 */
.profit-table .is-total {
  font-weight: 600;
}

/* Sits under the recipe name rather than in a column of its own; absent on an ordinary row. */
.row-diagnostic {
  margin-left: 1.5rem;
}

/* The whole row selects, so the whole row says so to a pointer. The keyboard route is the button. */
.profit-table tbody tr {
  cursor: pointer;
}

.profit-table tbody tr:hover {
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

/* Selection is a surface, a border, a marker glyph and `aria-current` — never color alone, and
   visibly different from the shared focus outline. */
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
