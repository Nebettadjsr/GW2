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
const props = defineProps<{ detail: CharacterOverview; roster?: boolean }>()

function duration(seconds: number): string {
  const hours = Math.floor(seconds / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  return `${hours.toLocaleString()} hours${minutes ? ` ${minutes} minutes` : ''}`
}
function date(value?: string | null): Date | null {
  if (!value) return null
  const result = new Date(value)
  return Number.isNaN(result.getTime()) ? null : result
}
function timestamp(value: Date | null): string | null {
  return value ? new Intl.DateTimeFormat(undefined, {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'
  }).format(value) : null
}
function relative(from: Date, to: Date): string {
  let months = (to.getFullYear() - from.getFullYear()) * 12 + to.getMonth() - from.getMonth()
  let anchor = new Date(from)
  anchor.setMonth(from.getMonth() + months)
  if (anchor > to) { months--; anchor = new Date(from); anchor.setMonth(from.getMonth() + months) }
  const years = Math.floor(months / 12)
  const remainingMonths = months % 12
  const days = Math.max(0, Math.floor((to.getTime() - anchor.getTime()) / 86_400_000))
  const unit = (value: number, name: string) => `${value} ${name}${value === 1 ? '' : 's'}`
  if (years) return [unit(years, 'year'), remainingMonths ? unit(remainingMonths, 'month') : ''].filter(Boolean).join(' ')
  if (months) return [unit(months, 'month'), days >= 7 ? unit(Math.floor(days / 7), 'week') : days ? unit(days, 'day') : ''].filter(Boolean).join(' ')
  if (days >= 7) return `${unit(Math.floor(days / 7), 'week')}${days % 7 ? ` ${unit(days % 7, 'day')}` : ''}`
  return days ? unit(days, 'day') : 'less than a day'
}
const createdAt = computed(() => date(props.detail.createdAt))
const birthdayAt = computed(() => date(props.detail.nextBirthdayAt))
const createdDate = computed(() => timestamp(createdAt.value))
const birthdayDate = computed(() => timestamp(birthdayAt.value))
const playtime = computed(() => typeof props.detail.ageSeconds === 'number' ? duration(props.detail.ageSeconds) : null)
const perDay = computed(() => {
  if (typeof props.detail.ageSeconds !== 'number' || !createdAt.value) return null
  const days = (Date.now() - createdAt.value.getTime()) / 86_400_000
  if (days <= 0) return null
  const seconds = Math.round(props.detail.ageSeconds / days)
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s per day`
})
const deaths = computed(() => typeof props.detail.deaths === 'number' ? props.detail.deaths.toLocaleString() : null)
const deathsPerHour = computed(() => typeof props.detail.deaths === 'number' && typeof props.detail.ageSeconds === 'number' && props.detail.ageSeconds > 0
  ? `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(props.detail.deaths / (props.detail.ageSeconds / 3600))} per hour` : null)
const created = computed(() => createdAt.value ? `${relative(createdAt.value, new Date())} ago` : null)
const birthday = computed(() => {
  if (!birthdayAt.value) return null
  return birthdayAt.value.getTime() <= Date.now() ? 'today' : `in ${relative(new Date(), birthdayAt.value)}`
})
</script>

<template>
  <div class="summary" :class="{ 'summary--roster': roster }" data-test="character-summary">
    <ul class="summary__crafting" aria-label="Crafting disciplines" data-test="summary-crafting">
      <li v-for="d in detail.craftingDisciplines" :key="d.discipline" :class="{ 'summary__inactive': !d.active }" data-test="summary-discipline">
        <img v-if="characterIcon(d.discipline)" :src="characterIcon(d.discipline)!" width="20" height="20" alt="" />
        {{ d.discipline }}: {{ d.rating }}
      </li>
    </ul>
    <dl class="summary__stats">
      <div><dt>Playtime</dt><dd data-test="summary-playtime">{{ playtime ?? 'unknown' }}</dd><dd v-if="perDay" class="summary__secondary">{{ perDay }}</dd></div>
      <div class="summary__deaths"><dt>Deaths</dt><dd data-test="summary-deaths">{{ deaths ?? 'unknown' }}</dd><dd v-if="deathsPerHour" class="summary__secondary">{{ deathsPerHour }}</dd></div>
      <div class="summary__created"><dt>Creation Date</dt><dd data-test="summary-created">{{ created ?? 'unknown' }}</dd><dd v-if="createdDate" class="summary__secondary">{{ createdDate }}</dd></div>
      <div class="summary__birthday"><dt>Next Birthday</dt><dd data-test="summary-birthday">{{ birthday ?? 'unknown' }}</dd><dd v-if="birthdayDate" class="summary__secondary">{{ birthdayDate }}</dd></div>
    </dl>
  </div>
</template>

<style scoped>
.summary { margin: 8px 0 28px; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 24px; align-items: stretch; text-align: left; }
.summary--roster { margin: 0; }
.summary__stats { display: contents; }
.summary__stats > div, .summary__crafting { min-width: 0; }
.summary__stats dt { font-weight: 700; line-height: 1.4; }
.summary__stats dd { margin: 0; line-height: 1.4; }
.summary__secondary, .summary__inactive { color: #96999c; }
.summary__crafting { list-style: none; margin: 0; padding: 0; }
.summary__crafting li { display: flex; align-items: center; gap: 8px; line-height: 1.5; }
</style>
