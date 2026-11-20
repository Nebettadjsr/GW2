<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ForgeNode } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'

/**
 * One node of the selected project's how-to. Quantities are the backend's: `needed = fromOwned +
 * fromSurplus + toCraft + toAcquire`. Owned stock is inventory progress; `toCraft` is planned work.
 * A locked ordinary recipe still lists its full ingredients; it is only labelled, never hidden.
 */
const props = defineProps<{ node: ForgeNode; depth: number }>()
const open = ref(props.depth < 2)
const label = computed(() => props.node.item.name ?? `Item ${props.node.item.itemId}`)
const via = computed(() => {
  if (props.node.recipeId === null) return null
  return props.node.recipeId < 0 ? 'Mystic Forge' : props.node.recipeSource === 'VENDOR' ? 'Vendor' : 'Crafting'
})
const knowledge = computed(() => {
  const n = props.node
  if (n.recipeId === null || n.recipeId < 0) return null
  if (n.recipeKnown === false) return 'Recipe not unlocked'
  if (n.recipeKnown === null) return 'Recipe knowledge unknown'
  return n.assignedCharacter ? `Craftable by ${n.assignedCharacter}` : 'Recipe unlocked'
})
</script>

<template>
  <li class="forge-node" data-test="forge-node">
    <div class="forge-node__head">
      <button v-if="node.children.length > 0" type="button" class="forge-node__toggle" :aria-expanded="open"
        :aria-label="`${open ? 'Collapse' : 'Expand'} ${label}`" data-test="forge-node-toggle" @click="open = !open">
        {{ open ? '▾' : '▸' }}
      </button>
      <span v-else class="forge-node__toggle" aria-hidden="true"></span>
      <ItemIcon :icon-url="node.item.iconUrl" :item-id="node.item.itemId" :size="24" />
      <a v-if="node.item.wikiUrl" :href="node.item.wikiUrl" target="_blank" rel="noopener noreferrer" class="forge-node__name">{{ label }}</a>
      <span v-else class="forge-node__name">{{ label }}</span>
      <span class="forge-node__qty numeric" data-test="forge-node-needed">× {{ node.needed }}</span>
      <span v-if="via" class="forge-chip">{{ via }}<template v-if="node.crafts > 0"> · {{ node.crafts }}×</template></span>
    </div>
    <p class="forge-node__facts" data-test="forge-node-facts">
      <span v-if="node.fromOwned > 0">Owned {{ node.fromOwned }}</span>
      <span v-if="node.fromSurplus > 0">From surplus {{ node.fromSurplus }}</span>
      <span v-if="node.toCraft > 0">To craft {{ node.toCraft }}</span>
      <span v-if="node.toAcquire > 0" class="forge-missing">To acquire {{ node.toAcquire }}</span>
      <span v-if="knowledge" :class="{ 'forge-missing': node.recipeKnown === false }">{{ knowledge }}</span>
      <span v-if="node.minRating > 0">Rating {{ node.minRating }}</span>
      <span v-if="node.disciplines">{{ node.disciplines }}</span>
      <span v-if="node.recipeKnown === false || (node.recipeId !== null && node.recipeId > 0 && !node.performable)">Structural path only, not executable yet</span>
    </p>
    <p v-if="node.hint" class="forge-node__hint">{{ node.hint }}</p>
    <ul v-if="open && node.children.length > 0" class="forge-node__children">
      <MysticForgeNode v-for="child in node.children" :key="`${child.item.itemId}-${child.recipeId}`" :node="child" :depth="depth + 1" />
    </ul>
  </li>
</template>

<style scoped>
.forge-node { list-style: none; margin: 0.25rem 0; }
.forge-node__head { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
.forge-node__toggle { width: 1.5rem; min-width: 1.5rem; padding: 0; }
.forge-node__name { overflow-wrap: anywhere; min-width: 0; }
.forge-node__facts { display: flex; gap: 0.75rem; flex-wrap: wrap; margin: 0 0 0 2rem; color: var(--color-muted); font-size: var(--text-sm); }
.forge-node__hint { margin: 0 0 0 2rem; color: var(--color-muted); font-size: var(--text-sm); white-space: normal; }
.forge-node__children { margin: 0 0 0 1.25rem; padding: 0; border-left: 1px solid var(--color-border); padding-left: 0.75rem; }
.forge-chip { border: 1px solid var(--color-border); border-radius: var(--radius-pill); padding: 0 0.5rem; font-size: var(--text-sm); }
.forge-missing { color: var(--color-warning); }
</style>
