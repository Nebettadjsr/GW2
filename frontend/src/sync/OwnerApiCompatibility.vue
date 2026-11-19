<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { connection } from '@/api/accountSession'
import { ACCOUNT_CHANGED_CODE, ApiRequestError } from '@/api/http'
import { systemApi, type ApiCompatibilityView, type CompatStatus } from '@/api/systemApi'

/**
 * Owner-only "API compatibility" (STORY-OPS-008). Mounted only while the shell holds owner capability.
 * Starting a check asks the backend to run one bounded, fixed public-ArenaNet sample; reading and polling
 * only fetch the backend's cached result and never cause ArenaNet requests. A late answer from an earlier
 * account generation, lease or unmounted view is discarded; a confirmed 401/403 asks the parent to withdraw
 * owner presentation. Nothing is persisted in the browser.
 */
const emit = defineEmits<{ denied: [] }>()
const POLL_MS = 2000

const view = ref<ApiCompatibilityView | null>(null)
const error = ref<string | null>(null)
const starting = ref(false)

let epoch = 0
let timer: ReturnType<typeof setTimeout> | null = null
let unmounted = false

function stopPolling(): void {
  if (timer !== null) { clearTimeout(timer); timer = null }
}

function reset(): void {
  epoch += 1
  stopPolling()
  view.value = null
  error.value = null
  starting.value = false
}

function guard() {
  const mine = epoch
  const generation = connection.generation
  const lease = connection.leaseVersion
  return () => !unmounted && mine === epoch && connection.phase === 'connected' &&
    connection.generation === generation && connection.leaseVersion === lease
}

/** @returns true when the failure was handled (stale, account change or denial) */
function handleFailure(cause: unknown, live: () => boolean): boolean {
  if (!live()) return true
  if (cause instanceof ApiRequestError && cause.code === ACCOUNT_CHANGED_CODE) return true
  if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
    reset()
    error.value = 'API compatibility is not available for this account.'
    emit('denied')
    return true
  }
  return false
}

function apply(next: ApiCompatibilityView, live: () => boolean): void {
  if (!next || !next.result || typeof next.result.state !== 'string') throw new Error('Unexpected response')
  view.value = next
  error.value = null
  stopPolling()
  if (next.result.state === 'RUNNING' && live()) timer = setTimeout(() => { void load() }, POLL_MS)
}

async function load(): Promise<void> {
  if (connection.phase !== 'connected' || unmounted) return
  const live = guard()
  try {
    const next = await systemApi.readApiCompatibility()
    if (!live()) return
    apply(next, live)
  } catch (cause) {
    if (handleFailure(cause, live)) return
    // An unreadable status is never shown as a result; keep polling only while a check was known to run.
    error.value = 'The API compatibility result could not be read.'
    if (view.value?.result.state === 'RUNNING') timer = setTimeout(() => { void load() }, POLL_MS)
  }
}

async function start(): Promise<void> {
  if (starting.value || view.value?.result.state === 'RUNNING' || connection.phase !== 'connected') return
  const live = guard()
  starting.value = true
  error.value = null
  try {
    const next = await systemApi.startApiCompatibilityCheck()
    if (!live()) return
    apply(next, live)
  } catch (cause) {
    if (handleFailure(cause, live)) return
    if (cause instanceof ApiRequestError && cause.status === 409) {
      await load()
      if (live()) error.value = 'A check is already running.'
    } else {
      error.value = 'The check could not be started.'
    }
  } finally {
    if (live()) starting.value = false
  }
}

watch(() => [connection.phase, connection.generation, connection.leaseVersion],
  () => { reset(); void load() }, { immediate: true })

onBeforeUnmount(() => { unmounted = true; reset() })

const running = computed(() => starting.value || view.value?.result.state === 'RUNNING')
const result = computed(() => view.value?.result ?? null)
const complete = computed(() => result.value?.state === 'COMPLETE')

const OVERALL_TEXT: Record<CompatStatus, string> = {
  OK: 'Compatible',
  ATTENTION: 'Needs attention',
  UNAVAILABLE: 'Could not be fully checked',
  MISMATCH: 'Mismatch found'
}
const headline = computed(() => {
  if (running.value) return 'Running…'
  const r = result.value
  if (!r || r.state === 'NOT_CHECKED') return 'Not checked'
  if (!r.overall) return 'Not checked'
  const text = OVERALL_TEXT[r.overall]
  return view.value?.stale ? `${text} (stale — check again)` : text
})
/** Only a fresh, fully-OK result may look successful. */
const tone = computed(() => {
  const r = result.value
  if (running.value || !r || r.state !== 'COMPLETE' || !r.overall) return 'neutral'
  if (view.value?.stale) return 'warning'
  return r.overall === 'OK' ? 'ok' : r.overall === 'MISMATCH' ? 'error' : 'warning'
})
function when(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toISOString().replace('T', ' ').replace(/:\d\d(\.\d+)?Z$/, ' UTC')
}
</script>

<template>
  <section class="api-compat" aria-label="API compatibility" data-test="api-compat">
    <h2>API compatibility</h2>
    <p class="meta" data-test="api-compat-scope">
      Compares a small, fixed sample of public ArenaNet responses (version directory, item, recipe, Trading Post
      price, currencies and a Jade recipe canary) with the fields this app reads. It is a bounded sample, not the
      whole API; authenticated account endpoint payloads are not live-verified. A newer schema version is a
      reason to review, not proof of breakage.
      <a href="https://api.guildwars2.com/v2.json?v=latest" target="_blank" rel="noopener noreferrer" data-test="api-compat-directory-link">Version directory</a>
      ·
      <a href="https://wiki.guildwars2.com/wiki/API:Changelog" target="_blank" rel="noopener noreferrer" data-test="api-compat-changelog-link">Changelog</a>
    </p>
    <div class="api-compat__actions">
      <button type="button" class="btn" :disabled="running" data-test="api-compat-check" @click="start">
        {{ running ? 'Checking…' : 'Check API compatibility' }}
      </button>
      <strong :class="['api-compat__state', `api-compat__state--${tone}`]" role="status" data-test="api-compat-state">{{ headline }}</strong>
    </div>
    <p v-if="error" class="notice notice--warning" role="alert" data-test="api-compat-error">{{ error }}</p>

    <template v-if="complete && result">
      <dl class="status-facts" data-test="api-compat-summary">
        <dt>Last checked</dt><dd data-test="api-compat-checked-at">{{ when(result.checkedAt) }}</dd>
        <dt>Duration</dt><dd>{{ result.durationMs }} ms</dd>
        <dt>Recipe requests use</dt><dd data-test="api-compat-profile">{{ result.recipeProfile }}</dd>
        <dt>Newer than reviewed</dt>
        <dd data-test="api-compat-newer">{{ result.newerVersions.length ? result.newerVersions.join(', ') : 'none' }}</dd>
      </dl>
      <ul class="api-compat__probes" data-test="api-compat-probes">
        <li v-for="p in result.probes" :key="p.id" :data-test="`api-compat-probe-${p.id}`">
          <strong>{{ p.label }}</strong> — <span :class="`api-compat__badge api-compat__badge--${p.status.toLowerCase()}`">{{ p.status }}</span>
          <div class="meta">{{ p.detail }} <code>{{ p.endpoint }}</code> · {{ p.profile }}</div>
        </li>
      </ul>
      <details data-test="api-compat-versions">
        <summary>Advertised schema versions ({{ result.versions.length }})</summary>
        <ul>
          <li v-for="v in result.versions" :key="v.version">
            <code>{{ v.version }}</code> {{ v.reviewed ? '' : '(new, not reviewed)' }} — {{ v.description }}
          </li>
        </ul>
      </details>
    </template>
  </section>
</template>

<style scoped>
.api-compat { display: flex; flex-direction: column; gap: var(--space-3); }
.api-compat h2 { margin: 0; }
.api-compat__actions { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.api-compat__probes { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-2); }
.api-compat__state--ok, .api-compat__badge--ok { color: var(--color-success, #1b7f3b); }
.api-compat__state--warning, .api-compat__badge--attention, .api-compat__badge--unavailable { color: var(--color-warning, #9a6700); }
.api-compat__state--error, .api-compat__badge--mismatch { color: var(--color-danger, #b3261e); }
</style>
