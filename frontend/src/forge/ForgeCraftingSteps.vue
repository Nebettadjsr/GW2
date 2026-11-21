<script setup lang="ts">
import { computed } from 'vue'
import type { ForgeCraftingStep, ForgeCurrency, ForgeNode, ForgeShoppingLine } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import ForgeMerchantDetails from './ForgeMerchantDetails.vue'

const props = withDefaults(defineProps<{
  tree?: ForgeNode | null
  steps?: ForgeCraftingStep[]
  shopping?: ForgeShoppingLine[]
  currencies?: ForgeCurrency[]
  batchTarget?: boolean
}>(), { steps: () => [], shopping: () => [], currencies: () => [] })
const vendorPurchases = computed(() => new Map(props.shopping
  .filter(line => line.source === 'VENDOR').map(line => [line.item.itemId, line])))

function action(node: ForgeCraftingStep): string {
  if (props.batchTarget && node.root) return 'Forge'
  return node.recipeSource === 'VENDOR' ? 'Buy' : 'Craft'
}

function quantity(node: ForgeCraftingStep): number {
  if (props.batchTarget && node.root) return node.crafts
  if (node.recipeSource === 'VENDOR') return vendorPurchases.value.get(node.item.itemId)?.toAcquire ?? node.produced
  return node.recipeId !== null ? node.produced : node.toAcquire
}

function location(node: ForgeCraftingStep): string {
  if (props.batchTarget && node.root) return 'Mystic Forge · random output'
  if (node.recipeId !== null && node.recipeId < 0) {
    return node.hint?.endsWith('minimum yield')
      ? `Mystic Forge · ${node.crafts.toLocaleString()} attempts · minimum yield` : node.hint ?? 'Mystic Forge'
  }
  if (node.recipeId !== null) return node.assignedCharacter ?? node.disciplines ?? 'Crafting station'
  return `Mystic Forge · ≈${node.crafts.toLocaleString()} attempts · estimated`
}
</script>

<template>
  <ol v-if="steps.length" class="crafting-steps" data-test="forge-crafting-steps">
    <li v-for="(step, index) in steps" :key="`${step.recipeId ?? step.item.itemId}-${index}`" class="crafting-step" data-test="forge-crafting-step" :data-item-id="step.item.itemId">
      <span class="step-number">{{ index + 1 }}</span>
      <div class="step-content" :class="{ 'step-content--purchase': step.recipeSource === 'VENDOR' }">
        <div class="step-output">
          <span>{{ action(step) }}</span>
          <strong class="numeric">{{ quantity(step).toLocaleString() }}</strong>
          <span v-if="batchTarget && step.root">{{ step.crafts === 1 ? 'batch' : 'batches' }}</span>
          <ItemIcon :icon-url="step.item.iconUrl" :item-id="step.item.itemId" :size="28" />
          <a v-if="step.item.wikiUrl" :href="step.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ step.item.name ?? `Item ${step.item.itemId}` }}</a>
          <span v-else>{{ step.item.name ?? `Item ${step.item.itemId}` }}</span>
        </div>
        <ForgeMerchantDetails v-if="step.recipeSource === 'VENDOR'" :item-id="step.item.itemId" :purchase="vendorPurchases.get(step.item.itemId)" :currencies="currencies" compact />
        <p v-else class="step-source">{{ location(step) }}</p>
        <ul v-if="step.ingredients.length && step.recipeSource !== 'VENDOR'" class="step-ingredients">
          <li v-for="(ingredient, childIndex) in step.ingredients" :key="childIndex">
            <span class="numeric">{{ ingredient.quantity.toLocaleString() }}</span>
            <ItemIcon :icon-url="ingredient.item.iconUrl" :item-id="ingredient.item.itemId" :size="22" />
            <a v-if="ingredient.item.wikiUrl" :href="ingredient.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ ingredient.item.name ?? `Item ${ingredient.item.itemId}` }}</a>
            <span v-else>{{ ingredient.item.name ?? `Item ${ingredient.item.itemId}` }}</span>
          </li>
        </ul>
        <p v-if="step.newSurplus > 0 && step.recipeSource !== 'VENDOR'" class="step-hint">{{ step.newSurplus }} extra batch units available for later steps.</p>
        <p v-if="step.recipeKnown === false" class="step-warning">Recipe not unlocked</p>
        <p v-else-if="step.recipeId !== null && step.recipeId > 0 && !step.performable" class="step-warning">No eligible crafting character</p>
        <p v-if="step.cutoff" class="step-warning">{{ step.cutoff }}</p>
      </div>
    </li>
  </ol>
  <p v-else class="muted">No crafting steps in this plan; see Shopping for what to acquire.</p>
</template>

<style scoped>
.crafting-steps { list-style: none; padding: 0; margin: 0; }
.crafting-step { display: grid; grid-template-columns: 2rem minmax(0, 1fr); gap: var(--space-3); padding: var(--space-4) 0; border-bottom: 1px solid var(--color-border); }
.crafting-step:first-child { padding-top: 0; }
.crafting-step:last-child { border: 0; padding-bottom: 0; }
.step-number { display: grid; place-items: center; width: 2rem; height: 2rem; border-radius: var(--radius-control); background: var(--color-raised); color: var(--color-highlight); font-weight: 700; }
.step-content { min-width: 0; }
.step-content--purchase { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) max-content; gap: var(--space-2) var(--space-4); align-items: center; }
.step-content--purchase > .step-output { grid-column: 1 / 3; grid-row: 1; }
.step-content--purchase > .step-warning { grid-column: 1 / -1; }
.step-output { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2); font-weight: 600; }
.step-output a, .step-ingredients a { min-width: 0; overflow-wrap: anywhere; }
.step-source, .step-hint, .muted { color: var(--color-muted); font-size: var(--text-sm); }
.step-source { margin: var(--space-1) 0 var(--space-3); }
.step-hint { margin-top: var(--space-2); }
.step-ingredients { display: grid; gap: var(--space-2); list-style: none; padding: 0; margin: 0; }
.step-ingredients li { display: grid; grid-template-columns: minmax(2rem, max-content) 22px minmax(0, 1fr); gap: var(--space-2); align-items: center; }
.step-ingredients .numeric { text-align: right; }
.step-warning { color: var(--color-warning); font-size: var(--text-sm); margin-top: var(--space-2); }
</style>
