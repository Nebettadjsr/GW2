<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { connection } from '@/api/accountSession'
import { ACCOUNT_CHANGED_CODE, ApiRequestError } from '@/api/http'
import { systemApi, type ApiCompatibilityView } from '@/api/systemApi'

/**
 * Owner-only compact "GW2 API" status card (STORY-OPS-008). Mounted only while the shell holds owner
 * capability. The backend checks automatically (after startup, then every six hours); this card only reads
 * the cached result on a modest poll and never starts a check or causes ArenaNet requests. A late answer from
 * an earlier account generation, lease or unmounted view is discarded; a confirmed 401/403 asks the parent to
 * withdraw owner presentation. Nothing is persisted in the browser.
 */
const emit = defineEmits<{ denied: [] }>()
const IDLE_POLL_MS = 30_000
const RUNNING_POLL_MS = 2_000

const view = ref<ApiCompatibilityView | null>(null)
const unreadable = ref(false)

let epoch = 0
/** Only the newest read may publish, so an older GET never overwrites a newer answer. */
let sequence = 0
let timer: ReturnType<typeof setTimeout> | null = null
let unmounted = false

function stopPolling(): void {
  if (timer !== null) { clearTimeout(timer); timer = null }
}

function reset(): void {
  epoch += 1
  stopPolling()
  view.value = null
  unreadable.value = false
}

function guard() {
  const mine = epoch
  const generation = connection.generation
  const lease = connection.leaseVersion
  return () => !unmounted && mine === epoch && connection.phase === 'connected' &&
    connection.generation === generation && connection.leaseVersion === lease
}

function schedule(live: () => boolean): void {
  stopPolling()
  if (!live()) return
  const ms = view.value?.result.state === 'RUNNING' ? RUNNING_POLL_MS : IDLE_POLL_MS
  timer = setTimeout(() => { void load() }, ms)
}

async function load(): Promise<void> {
  if (connection.phase !== 'connected' || unmounted) return
  const live = guard()
  const seq = ++sequence
  try {
    const next = await systemApi.readApiCompatibility()
    if (!live() || seq !== sequence) return
    if (!next || !next.result || typeof next.result.state !== 'string') throw new Error('Unexpected response')
    view.value = next
    unreadable.value = false
    schedule(live)
  } catch (cause) {
    if (!live() || seq !== sequence) return
    if (cause instanceof ApiRequestError && cause.code === ACCOUNT_CHANGED_CODE) return
    if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
      reset()
      emit('denied')
      return
    }
    // An unreadable status is never shown as a result; keep trying on the same modest cadence.
    view.value = null
    unreadable.value = true
    schedule(live)
  }
}

watch(() => [connection.phase, connection.generation, connection.leaseVersion],
  () => { reset(); void load() }, { immediate: true })

onBeforeUnmount(() => { unmounted = true; reset() })

type Tone = 'success' | 'caution' | 'unknown'
const presentation = computed<{ text: string; tone: Tone }>(() => {
  const v = view.value
  if (unreadable.value) return { text: 'Unavailable', tone: 'unknown' }
  if (!v) return { text: 'Not checked', tone: 'unknown' }
  const r = v.result
  if (r.state === 'RUNNING') return { text: 'Checking…', tone: 'unknown' }
  if (r.state !== 'COMPLETE' || !r.overall) return { text: 'Not checked', tone: 'unknown' }
  // Only a fresh, fully-OK result may look successful.
  if (v.stale) return { text: 'Not checked', tone: 'unknown' }
  if (r.overall === 'OK') return { text: 'Good', tone: 'success' }
  if (r.overall === 'UNAVAILABLE') return { text: 'Unavailable', tone: 'unknown' }
  return { text: 'Needs update', tone: 'caution' }
})
const lastChecked = computed(() => {
  const iso = view.value?.result.state === 'COMPLETE' ? view.value.result.checkedAt : null
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(d)
})
</script>

<template>
  <article class="panel status-card" data-test="api-compat">
    <div class="status-card__heading">
      <h3>GW2 API</h3>
      <span class="status" :class="`status--${presentation.tone}`" role="status" data-test="api-compat-state">{{ presentation.text }}</span>
    </div>
    <dl class="status-facts">
      <dt>Last checked</dt><dd data-test="api-compat-checked-at">{{ lastChecked }}</dd>
    </dl>
  </article>
</template>

<style scoped>
/* Mirrors the sibling catalog cards (OwnerCatalogControl); parent scoped styles do not reach this component. */
.status-card { min-width: 0; display: flex; flex-direction: column; }
.status-card__heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--space-3); }
.status-card__heading h3 { margin: 0; font-size: var(--text-md, 1rem); }
.status-facts { display: grid; grid-template-columns: max-content minmax(0, 1fr); column-gap: var(--space-3); row-gap: var(--space-1, 0.25rem); margin: var(--space-3) 0 0; }
.status-facts dt { color: var(--color-muted); }
.status-facts dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
</style>
