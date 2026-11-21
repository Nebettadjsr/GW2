<script setup lang="ts">
import { computed } from 'vue'
import { formatCopper } from '@/crafting/formatCopper'

const props = defineProps<{ copper: number | null }>()
const label = computed(() => props.copper === null ? 'Price unavailable' : formatCopper(props.copper))
const units = computed(() => {
  if (props.copper === null) return []
  const gold = Math.floor(props.copper / 10000)
  const silver = Math.floor(props.copper % 10000 / 100)
  return [
    ...(gold > 0 ? [{ kind: 'gold', amount: gold }] : []),
    ...(gold > 0 || silver > 0 ? [{ kind: 'silver', amount: silver }] : []),
    { kind: 'copper', amount: props.copper % 100 }
  ]
})
</script>

<template>
  <span v-if="copper === null" class="unavailable">Price unavailable</span>
  <span v-else class="coin-amount numeric" role="img" :aria-label="label" :title="label">
    <span v-for="unit in units" :key="unit.kind" class="coin-unit" aria-hidden="true">
      <span>{{ unit.amount.toLocaleString() }}</span>
      <svg class="coin" :class="`coin--${unit.kind}`" viewBox="0 0 20 20" focusable="false">
        <ellipse cx="10" cy="11" rx="8" ry="7.5" fill="currentColor" />
        <path d="M2 10a8 7 0 0 0 16 0v2a8 7 0 0 1-16 0Z" fill="#000" opacity=".3" />
        <ellipse cx="10" cy="9" rx="8" ry="7" fill="currentColor" stroke="#0006" stroke-width=".7" />
        <ellipse cx="10" cy="9" rx="5.8" ry="4.9" fill="none" stroke="#fff8" stroke-width=".7" />
        <path d="m10 5 1.3 2.5 2.7.5-2 1.8.4 2.7L10 11.2l-2.4 1.3.4-2.7L6 8l2.7-.5Z" fill="#000" opacity=".22" />
        <path d="M4 6a8 7 0 0 1 9-3" fill="none" stroke="#fff" stroke-width="1.3" opacity=".65" stroke-linecap="round" />
      </svg>
    </span>
  </span>
</template>

<style scoped>
.coin-amount { display: inline-flex; align-items: center; gap: var(--space-2); white-space: nowrap; }
.coin-unit { display: inline-flex; align-items: center; gap: 3px; }
.coin { width: 18px; height: 18px; flex: none; }
.coin--gold { color: #e3b64f; }
.coin--silver { color: #b9c5d0; }
.coin--copper { color: #bc7847; }
.unavailable { color: var(--color-muted); font-size: var(--text-sm); }
</style>
