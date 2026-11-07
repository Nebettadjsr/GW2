<script setup lang="ts">
import { computed } from 'vue'
import type { CharacterOverview } from '@/api/types'
import { characterIcon } from './characterIcons'

/**
 * Compact summary under the character title and above the section tabs: playtime, deaths, creation
 * date, time to the backend-supplied next birthday, and crafting disciplines one per line. Unknown
 * (null/absent) values are shown as unknown, never as zero. The birthday boundary is the backend's
 * `nextBirthdayAt`; this only displays its difference from now (no timer, no anniversary arithmetic).
 */
const props = defineProps<{ detail: CharacterOverview }>()

function duration(seconds: number): string {
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  if (days > 0) return `${days} d ${hours} h`
  if (hours > 0) return `${hours} h ${minutes} min`
  return `${minutes} min`
}
const playtime = computed(() => typeof props.detail.ageSeconds === 'number' ? duration(props.detail.ageSeconds) : null)
const deaths = computed(() => typeof props.detail.deaths === 'number' ? props.detail.deaths.toLocaleString() : null)
const created = computed(() => {
  if (!props.detail.createdAt) return null
  const d = new Date(props.detail.createdAt)
  return Number.isNaN(d.getTime()) ? null : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(d)
})
const birthday = computed(() => {
  if (!props.detail.nextBirthdayAt) return null
  const ms = new Date(props.detail.nextBirthdayAt).getTime() - Date.now()
  if (Number.isNaN(ms)) return null
  return ms <= 0 ? 'today' : `in ${duration(Math.ceil(ms / 1000))}`
})
</script>

<template>
  <div class="summary" data-test="character-summary">
    <dl class="summary__stats">
      <div><dt>Playtime</dt><dd data-test="summary-playtime">{{ playtime ?? 'unknown' }}</dd></div>
      <div><dt>Deaths</dt><dd data-test="summary-deaths">{{ deaths ?? 'unknown' }}</dd></div>
      <div><dt>Created</dt><dd data-test="summary-created">{{ created ?? 'unknown' }}</dd></div>
      <div><dt>Next birthday</dt><dd data-test="summary-birthday">{{ birthday ?? 'unknown' }}</dd></div>
    </dl>
    <ul v-if="detail.craftingDisciplines.length > 0" class="summary__crafting" aria-label="Crafting disciplines" data-test="summary-crafting">
      <li v-for="d in detail.craftingDisciplines" :key="d.discipline" data-test="summary-discipline">
        <img v-if="characterIcon(d.discipline)" :src="characterIcon(d.discipline)!" width="16" height="16" alt="" />
        {{ d.discipline }} {{ d.rating }}{{ d.active ? '' : ' (inactive)' }}
      </li>
    </ul>
  </div>
</template>

<style scoped>
.summary { margin: 4px 0 8px; display: flex; flex-wrap: wrap; gap: 4px 32px; align-items: flex-start; }
.summary__stats { display: flex; flex-wrap: wrap; gap: 4px 20px; margin: 0; }
.summary__stats div { display: flex; gap: 6px; }
.summary__stats dt { opacity: 0.75; }
.summary__stats dd { margin: 0; font-weight: 600; }
.summary__crafting { list-style: none; margin: 0; padding: 0; }
.summary__crafting li { display: flex; align-items: center; gap: 6px; }
</style>
