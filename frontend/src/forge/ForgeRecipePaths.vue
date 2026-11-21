<script setup lang="ts">
import { computed } from 'vue'
import type { ForgeRecipeRow } from '@/api/types'

const props = defineProps<{ paths: ForgeRecipeRow[]; selected: number }>()
const emit = defineEmits<{ select: [recipeId: number] }>()
const differentRarities = computed(() => new Set(props.paths.map(path => path.rarity)).size > 1)
function pathLabel(path: ForgeRecipeRow, index: number): string {
  if (differentRarities.value && path.rarity) return [path.rarity, path.pathLabel].filter(Boolean).join(' · ')
  return path.pathLabel ?? `Recipe ${index + 1}`
}
</script>

<template>
  <div class="recipe-paths" data-test="forge-recipe-paths">
    <span id="forge-path-label">Recipe path</span>
    <div v-if="paths.length === 2" class="recipe-paths__toggle" role="group" aria-labelledby="forge-path-label">
      <button v-for="(path, index) in paths" :key="path.recipeId" type="button" :aria-pressed="selected === path.recipeId"
        :data-recipe-id="path.recipeId" data-test="forge-recipe-path" @click="emit('select', path.recipeId)">{{ pathLabel(path, index) }}</button>
    </div>
    <select v-else :value="selected" aria-labelledby="forge-path-label" data-test="forge-recipe-path-select" @change="emit('select', Number(($event.target as HTMLSelectElement).value))">
      <option v-for="(path, index) in paths" :key="path.recipeId" :value="path.recipeId">{{ pathLabel(path, index) }}</option>
    </select>
  </div>
</template>

<style scoped>
.recipe-paths { display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; }
.recipe-paths__toggle { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.recipe-paths button { background: transparent; }
.recipe-paths button[aria-pressed="true"] { background: var(--color-raised); border-color: var(--color-secondary); font-weight: 700; }
.recipe-paths select { width: 100%; max-width: 48rem; }
</style>
