<script setup lang="ts">
import { computed } from 'vue'
import type { InventoryFreshness } from '@/api/types'
const props = defineProps<{ freshness?: InventoryFreshness }>()
const date = computed(() => {
  const value = props.freshness?.completedAt
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : new Intl.DateTimeFormat(undefined,
    { dateStyle: 'medium', timeStyle: 'short' }).format(parsed)
})
</script>

<template>
  <p class="meta" data-test="inventory-freshness">
    <template v-if="freshness?.status === 'PENDING'">Not synchronized yet. Refresh account data to load this inventory.</template>
    <template v-else>
      Last synchronized: <time v-if="date" :datetime="freshness?.completedAt ?? undefined">{{ date }}</time><span v-else>unknown</span>.
      <template v-if="freshness?.status === 'STALE'">This saved snapshot may be out of date.</template>
      <template v-else-if="freshness?.status === 'CURRENT'">Up to date when loaded.</template>
      <template v-else>Freshness could not be confirmed.</template>
    </template>
  </p>
</template>
