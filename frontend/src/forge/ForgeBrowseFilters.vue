<script setup lang="ts">
import { itemRarityColor } from '@/items/itemRarity'
import { FORGE_TABS as CATEGORIES, type ForgeTab, type ForgeFilterOption } from './useMysticForge'

defineProps<{
  category: ForgeTab
  subtype: string
  subtypes: ForgeFilterOption[]
  rarity: string
  rarities: ForgeFilterOption[]
  query: string
}>()
const emit = defineEmits<{
  category: [value: ForgeTab]
  subtype: [value: string]
  rarity: [value: string]
  query: [value: string]
}>()
</script>

<template>
  <section class="forge-filters" aria-label="Recipe filters" data-test="forge-filters">
    <label>Category
      <select :value="category" data-test="forge-filter-category" @change="emit('category', ($event.target as HTMLSelectElement).value as ForgeTab)">
        <option v-for="option in CATEGORIES" :key="option.id" :value="option.id">{{ option.label }}</option>
      </select>
    </label>
    <label :class="{ 'forge-filters__type--disabled': !subtypes.length }">Type
      <select :value="subtype" :disabled="!subtypes.length" data-test="forge-filter-subtype" @change="emit('subtype', ($event.target as HTMLSelectElement).value)">
        <option v-if="!subtypes.length" value="ALL">Not applicable</option>
        <option v-for="option in subtypes" :key="option.id" :value="option.id">{{ option.label }}</option>
      </select>
    </label>
    <label>Rarity
      <select :value="rarity" :style="{ color: itemRarityColor(rarity) }" data-test="forge-filter-rarity" @change="emit('rarity', ($event.target as HTMLSelectElement).value)">
        <option v-for="option in rarities" :key="option.id" :value="option.id" :style="{ color: itemRarityColor(option.id) }">{{ option.label }}</option>
      </select>
    </label>
    <label class="forge-filters__search">Search
      <input type="search" :value="query" placeholder="Name, item ID or type" data-test="forge-search" @input="emit('query', ($event.target as HTMLInputElement).value)" />
    </label>
  </section>
</template>

<style scoped>
.forge-filters { display: flex; flex-wrap: wrap; gap: var(--space-4) var(--space-5); padding: var(--space-5); border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: var(--color-surface); }
.forge-filters label { display: flex; flex-direction: column; gap: var(--space-2); flex: 1 1 12rem; min-width: 0; }
.forge-filters label.forge-filters__search { flex: 2 1 20rem; }
.forge-filters select, .forge-filters input { width: 100%; min-width: 0; }
.forge-filters__type--disabled { opacity: .55; }
.forge-filters select:disabled { cursor: default; }
</style>
