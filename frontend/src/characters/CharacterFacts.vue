<script setup lang="ts">
import type { CharacterOverview } from '@/api/types'

/** The selected character's already-supplied facts (no fetched or invented fields). */
defineProps<{ detail: CharacterOverview; created?: string | null }>()
</script>

<template>
  <dl class="facts" data-test="character-facts">
    <dt>Profession</dt><dd>{{ detail.profession }}</dd>
    <dt>Race</dt><dd>{{ detail.race }}</dd>
    <dt>Level</dt><dd>{{ detail.level ?? 'unknown' }}</dd>
    <template v-if="created"><dt>Created</dt><dd>{{ created }}</dd></template>
    <template v-if="detail.craftingDisciplines.length > 0">
      <dt>Crafting</dt>
      <dd>{{ detail.craftingDisciplines.map((d) => `${d.discipline} ${d.rating}${d.active ? '' : ' (inactive)'}`).join(', ') }}</dd>
    </template>
  </dl>
</template>

<style scoped>
.facts { display: grid; grid-template-columns: max-content 1fr; gap: 2px 12px; margin: 0; }
.facts dd { margin: 0; }
</style>
