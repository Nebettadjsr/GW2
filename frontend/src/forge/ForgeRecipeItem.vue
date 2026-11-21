<script setup lang="ts">
import { computed } from 'vue'
import type { ForgeRecipeCurrency, ForgeRecipePart } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'

const props = withDefaults(defineProps<{ part: ForgeRecipePart | ForgeRecipeCurrency; showProgress: boolean; fill?: boolean }>(), { fill: true })
const item = computed(() => 'item' in props.part ? props.part.item
  : { itemId: null, name: props.part.name, iconUrl: props.part.iconUrl, wikiUrl: props.part.wikiUrl })
const name = computed(() => item.value.name ?? `Item ${item.value.itemId}`)
const acquired = computed(() => props.part.acquired?.toLocaleString() ?? '—')
const required = computed(() => ('quantity' in props.part ? props.part.quantity : props.part.needed).toLocaleString())
const percent = computed(() => props.part.progressPercent ?? null)
const progressLabel = computed(() => percent.value === null ? 'Wallet balance unknown'
  : `${acquired.value} of ${required.value} acquired${props.part.coveredByParent ? ', including owned components' : ''}${props.part.approximate ? '; requirement estimated' : ''}`)
</script>

<template>
  <div class="recipe-entry" :class="{ 'recipe-entry--fill': showProgress && fill, 'recipe-entry--complete': showProgress && percent === 100 }"
    :style="showProgress ? { '--progress': `${percent ?? 0}%` } : undefined" :title="showProgress ? progressLabel : undefined" :data-item-id="item.itemId"
    :data-currency-id="'currencyId' in part ? part.currencyId : undefined">
    <span class="numeric recipe-quantity">{{ part.approximate ? '≈' : '' }}{{ required }}</span>
    <ItemIcon :icon-url="item.iconUrl" :item-id="item.itemId" :size="24" />
    <component :is="item.wikiUrl ? 'a' : 'span'" :href="item.wikiUrl ?? undefined" target="_blank" rel="noopener noreferrer">{{ name }}</component>
    <small v-if="showProgress" class="recipe-progress" role="progressbar" :aria-label="`${name} acquired`" :aria-valuenow="percent ?? undefined"
      :aria-valuetext="progressLabel" :aria-valuemin="0" :aria-valuemax="100" data-test="forge-recipe-progress">
      <template v-if="percent !== null">{{ acquired }} / {{ required }} · {{ part.approximate ? '≈' : '' }}{{ percent }}%</template>
      <template v-else>Wallet balance unknown</template>
    </small>
  </div>
</template>

<style scoped>
.recipe-entry { display: grid; grid-template-columns: minmax(2ch, max-content) 24px minmax(0, 1fr); column-gap: var(--space-1); row-gap: 2px; align-items: center; min-width: 0; padding: 2px 0; line-height: 1.35; }
.recipe-entry--fill { background-image: linear-gradient(to left, color-mix(in srgb, var(--color-success) 16%, transparent) var(--progress), transparent var(--progress)); border-radius: var(--radius-control); }
.recipe-quantity { text-align: right; white-space: nowrap; }
.recipe-entry > :nth-child(3) { min-width: 0; overflow-wrap: anywhere; }
.recipe-progress { grid-column: 3; font-size: 0.75rem; line-height: 1.25; color: var(--color-muted); font-variant-numeric: tabular-nums; }
.recipe-entry--complete .recipe-progress { color: var(--color-success); }
</style>
