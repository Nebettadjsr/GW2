<script setup lang="ts">
defineProps<{ updating: boolean; failed: boolean; hasData: boolean }>()
</script>

<template>
  <p v-if="failed && !updating" class="refresh-error" role="status">{{ hasData ? 'Update failed — showing saved data.' : 'Could not load data.' }} <a href="#/synchronization">System Status</a></p>
  <p v-else-if="updating" class="refresh-status" role="status"><span aria-hidden="true">↻</span> Stale data – refreshing…</p>
</template>

<style scoped>
.refresh-status { display: inline-flex; align-items: center; gap: 8px; margin: 8px 0; padding: 5px 9px; color: #89d5a6; border: 2px solid currentColor; border-radius: 8px; animation: refreshing-pulse 1.5s ease-in-out infinite; }
.refresh-error { color: #e9bd79; margin: 8px 0; }
@keyframes refreshing-pulse { 50% { opacity: .55; } }
@media (prefers-reduced-motion: reduce) { .refresh-status { animation: none; } }
</style>
