<script setup lang="ts">
import { computed } from 'vue'
import type { ForgeRecipePart } from '@/api/types'
import { formatCopper } from '@/crafting/formatCopper'
import ForgeRecipeItem from './ForgeRecipeItem.vue'

const props = defineProps<{ tree: ForgeRecipePart; showProgress: boolean }>()

interface RecipeRow {
  key: string
  group: ForgeRecipePart
  primary: ForgeRecipePart | null
  materials: ForgeRecipePart[]
  groupRows: number
  first: boolean
  shaded: boolean
}

// The backend supplies the full reference, independently of stock and the remaining-work plan.
const rows = computed(() => {
  const result: RecipeRow[] = []
  const groups = props.tree.children.length ? [...props.tree.children].reverse() : [props.tree]
  for (const [groupIndex, group] of groups.entries()) {
    const primaryItems = group.children.length ? group.children : [null]
    for (const [primaryIndex, primary] of primaryItems.entries()) {
      result.push({ key: `${groupIndex}.${primaryIndex}`, group, primary, materials: primary?.children ?? [],
        groupRows: primaryItems.length, first: primaryIndex === 0, shaded: result.length % 2 === 1 })
    }
  }
  return result
})

function name(node: ForgeRecipePart): string {
  return node.item.name ?? `Item ${node.item.itemId}`
}

</script>

<template>
  <table class="recipe-table" :aria-label="`Recipe breakdown for ${name(tree)}`" data-test="forge-recipe-table">
    <colgroup><col class="group-column" /><col class="primary-column" /><col class="materials-column" /></colgroup>
    <tbody>
      <tr v-for="row in rows" :key="row.key" :class="{ shaded: row.shaded }">
        <td v-if="row.first" :rowspan="row.groupRows" class="recipe-group" :class="{ 'progress-cell': showProgress }" :style="showProgress ? { '--progress': `${row.group.progressPercent ?? 0}%` } : undefined">
          <ForgeRecipeItem :part="row.group" :fill="false" :show-progress="showProgress" />
        </td>
        <td class="recipe-primary" :class="{ 'progress-cell': showProgress }" :style="showProgress ? { '--progress': `${row.primary?.progressPercent ?? 0}%` } : undefined">
          <template v-if="row.primary">
            <ForgeRecipeItem :part="row.primary" :fill="false" :show-progress="showProgress" />
            <small v-if="row.primary.attempts !== null" class="estimate">≈{{ row.primary.attempts.toLocaleString() }} attempts (estimated)</small>
            <small v-if="row.primary.cutoff" class="estimate">{{ row.primary.cutoff }}</small>
          </template>
        </td>
        <td class="recipe-materials">
          <ul v-if="row.materials.length" class="material-list">
            <li v-for="(material, index) in row.materials" :key="`${material.item.itemId}-${index}`">
              <ForgeRecipeItem :part="material" :show-progress="showProgress" />
            </li>
          </ul>
          <div v-if="row.primary && (row.primary.currencies.length || row.primary.copper !== null)" class="material-list">
            <ForgeRecipeItem v-for="currency in row.primary.currencies" :key="currency.currencyId" :part="currency" :show-progress="showProgress" />
            <span v-if="row.primary.copper !== null">{{ formatCopper(row.primary.copper) }}</span>
          </div>
        </td>
      </tr>
    </tbody>
  </table>
  <p v-if="tree.cutoff" class="estimate">{{ tree.cutoff }}</p>
</template>

<style scoped>
.recipe-table { width: 100%; table-layout: fixed; border-collapse: collapse; font-size: var(--text-sm); }
.group-column { width: 28%; }
.primary-column { width: 32%; }
.materials-column { width: 40%; }
.recipe-table td { padding: var(--space-2); vertical-align: middle; border-bottom: 1px solid var(--color-border); }
.recipe-group, .recipe-primary { border-right: 1px solid var(--color-border); }
.recipe-table .recipe-group { background: var(--color-surface); }
.shaded .recipe-primary, .shaded .recipe-materials { background: var(--color-raised); }
.recipe-table .progress-cell { background-image: linear-gradient(to left, color-mix(in srgb, var(--color-success) 16%, transparent) var(--progress), transparent var(--progress)); }
.material-list { display: grid; gap: var(--space-1); list-style: none; padding: 0; margin: 0; }
.estimate { display: block; margin-top: var(--space-1); line-height: 1.35; color: var(--color-muted); }
</style>
