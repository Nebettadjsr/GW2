<script setup lang="ts">
import { computed } from 'vue'
import type { ForgeNode } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
import { forgeAcquisitionHint } from './forgeAcquisitionHints'

const props = defineProps<{ tree: ForgeNode | null }>()

// Present the supplied path in dependency order. No acquisition choice or amount is calculated here.
const steps = computed(() => {
  const result: { node: ForgeNode; key: string }[] = []
  function visit(node: ForgeNode, key: string): void {
    node.children.forEach((child, index) => visit(child, `${key}.${index}`))
    if ((node.recipeId !== null && node.crafts > 0) || node.toAcquire > 0) result.push({ node, key })
  }
  if (props.tree) visit(props.tree, 'root')
  return result
})

function action(node: ForgeNode): string {
  if (node.recipeSource === 'VENDOR') return 'Buy'
  if (node.recipeId !== null) return 'Craft'
  return forgeAcquisitionHint(node.item.itemId)?.action ?? 'Acquire'
}

function location(node: ForgeNode): string {
  if (node.recipeSource === 'VENDOR') return node.hint ?? 'Merchant'
  if (node.recipeId !== null && node.recipeId < 0) return 'Mystic Forge'
  if (node.recipeId !== null) return node.assignedCharacter ?? node.disciplines ?? 'Crafting station'
  return forgeAcquisitionHint(node.item.itemId)?.source ?? 'Trading Post / acquisition'
}
</script>

<template>
  <ol v-if="steps.length" class="crafting-steps" data-test="forge-crafting-steps">
    <li v-for="(step, index) in steps" :key="step.key" class="crafting-step" data-test="forge-crafting-step">
      <span class="step-number">{{ index + 1 }}</span>
      <div class="step-content">
        <div class="step-output">
          <span>{{ action(step.node) }}</span>
          <strong class="numeric">{{ step.node.recipeId !== null ? step.node.produced : step.node.toAcquire }}</strong>
          <ItemIcon :icon-url="step.node.item.iconUrl" :item-id="step.node.item.itemId" :size="28" />
          <a v-if="step.node.item.wikiUrl" :href="step.node.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ step.node.item.name ?? `Item ${step.node.item.itemId}` }}</a>
          <span v-else>{{ step.node.item.name ?? `Item ${step.node.item.itemId}` }}</span>
        </div>
        <p class="step-source">{{ location(step.node) }}</p>
        <ul v-if="step.node.children.length" class="step-ingredients">
          <li v-for="(ingredient, childIndex) in step.node.children" :key="childIndex">
            <span class="numeric">{{ ingredient.needed }}</span>
            <ItemIcon :icon-url="ingredient.item.iconUrl" :item-id="ingredient.item.itemId" :size="22" />
            <a v-if="ingredient.item.wikiUrl" :href="ingredient.item.wikiUrl" target="_blank" rel="noopener noreferrer">{{ ingredient.item.name ?? `Item ${ingredient.item.itemId}` }}</a>
            <span v-else>{{ ingredient.item.name ?? `Item ${ingredient.item.itemId}` }}</span>
          </li>
        </ul>
        <p v-if="step.node.recipeId === null && forgeAcquisitionHint(step.node.item.itemId)" class="step-hint">
          {{ forgeAcquisitionHint(step.node.item.itemId)?.detail }}
          <a :href="forgeAcquisitionHint(step.node.item.itemId)?.href" target="_blank" rel="noopener noreferrer">Acquisition options ↗</a>
        </p>
        <p v-else-if="step.node.hint" class="step-hint">{{ step.node.hint }}</p>
        <p v-if="step.node.newSurplus > 0" class="step-hint">{{ step.node.newSurplus }} extra batch units available for later steps.</p>
        <p v-if="step.node.recipeKnown === false" class="step-warning">Recipe not unlocked</p>
        <p v-else-if="step.node.recipeId !== null && step.node.recipeId > 0 && !step.node.performable" class="step-warning">No eligible crafting character</p>
        <p v-if="step.node.cutoff" class="step-warning">{{ step.node.cutoff }}</p>
      </div>
    </li>
  </ol>
  <p v-else class="muted">No crafting or acquisition steps in this plan.</p>
</template>

<style scoped>
.crafting-steps { list-style: none; padding: 0; margin: 0; }
.crafting-step { display: grid; grid-template-columns: 2rem minmax(0, 1fr); gap: var(--space-3); padding: var(--space-4) 0; border-bottom: 1px solid var(--color-border); }
.crafting-step:first-child { padding-top: 0; }
.crafting-step:last-child { border: 0; padding-bottom: 0; }
.step-number { display: grid; place-items: center; width: 2rem; height: 2rem; border-radius: var(--radius-control); background: var(--color-raised); color: var(--color-highlight); font-weight: 700; }
.step-content { min-width: 0; }
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
