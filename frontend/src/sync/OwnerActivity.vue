<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { connection } from '@/api/accountSession'
import { ACCOUNT_CHANGED_CODE, ApiRequestError } from '@/api/http'
import { systemApi, type ForegroundActivity, type OperationsBucket, type OperationsPercentile, type OperationsPeriod, type OperationsSummary } from '@/api/systemApi'

/**
 * Owner-only "Application activity" (STORY-OPS-002). Mounted only while the shell holds owner capability,
 * so unmounting (disconnect, denial, replacement) removes the privileged DOM. State lives in memory for
 * one account generation and lease; a late answer from an earlier proof is discarded. Read once on mount
 * and on the explicit refresh control, never on a timer. Files are chosen from the server's period manifest
 * and downloaded by opaque id; the browser builds no ranges, paths or CSV.
 */
const props = defineProps<{ activeLast60Minutes?: number | null }>()
/** A confirmed 401/403 for an owner read: the parent owner boundary must withdraw owner presentation. */
const emit = defineEmits<{ denied: [] }>()

const PAGE = 10
const summary = ref<OperationsSummary | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
const page = ref(0)
const historyOpen = ref(false)
const periods = ref<OperationsPeriod[]>([])
const periodsState = ref<'loading' | 'ready' | 'unavailable'>('loading')
const selectedId = ref('')
const exportError = ref<string | null>(null)
const exporting = ref(false)

let epoch = 0
let controller: AbortController | null = null
let exportController: AbortController | null = null
let exportToken = 0
let unmounted = false

function invalidate(): void {
  epoch += 1
  controller?.abort()
  controller = null
  cancelExport()
  summary.value = null
  periods.value = []
  periodsState.value = 'loading'
  selectedId.value = ''
  error.value = null
  loading.value = false
  exportError.value = null
  exporting.value = false
  page.value = 0
}

/** Aborts and invalidates any export in flight and clears its busy state. */
function cancelExport(): void {
  exportToken += 1
  exportController?.abort()
  exportController = null
  exporting.value = false
  exportError.value = null
}

async function load(): Promise<void> {
  if (connection.phase !== 'connected' || unmounted) return
  cancelExport()
  const mine = ++epoch
  const generation = connection.generation
  const lease = connection.leaseVersion
  const live = () => !unmounted && mine === epoch && connection.phase === 'connected' &&
    connection.generation === generation && connection.leaseVersion === lease
  loading.value = true
  try {
    const [result, manifest] = await Promise.all([
      systemApi.readOperationsSummary(),
      systemApi.readOperationsPeriods().catch((cause: unknown) => {
        // A denial is handled with the summary; any other manifest failure only disables the file choice.
        if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403 || cause.code === ACCOUNT_CHANGED_CODE)) throw cause
        return null
      })
    ])
    if (!live()) return
    if (!Array.isArray(result?.minutes) || !result.databaseSize) {
      throw new Error('Unexpected activity response')
    }
    summary.value = result
    page.value = 0
    applyManifest(manifest)
    error.value = null
  } catch (cause) {
    if (!live() || (cause instanceof ApiRequestError && cause.code === ACCOUNT_CHANGED_CODE)) return
    summary.value = null
    const denied = cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)
    if (denied) emit('denied')
    error.value = denied
      ? 'Activity is not available for this account.'
      : 'Application activity is temporarily unavailable.'
  } finally {
    if (live()) loading.value = false
  }
}

/** Nonempty periods only; the server's newest-first order is re-asserted per group. */
function applyManifest(manifest: { available: boolean; periods: OperationsPeriod[] } | null): void {
  if (!manifest || !manifest.available || !Array.isArray(manifest.periods)) {
    periods.value = []
    periodsState.value = 'unavailable'
    selectedId.value = ''
    return
  }
  periods.value = manifest.periods.filter((p) => p.retainedBytes > 0)
  periodsState.value = 'ready'
  if (!periods.value.some((p) => p.selectionId === selectedId.value)) {
    selectedId.value = days.value[0]?.selectionId ?? ''
  }
}

function newestFirst(kind: 'DAY' | 'WEEK'): OperationsPeriod[] {
  return periods.value.filter((p) => p.kind === kind).sort((a, b) => Date.parse(b.fromUtc) - Date.parse(a.fromUtc))
}
const days = computed(() => newestFirst('DAY'))
const weeks = computed(() => newestFirst('WEEK'))

function periodLabel(p: OperationsPeriod): string {
  const tags = [p.state === 'OPEN' ? 'open' : 'closed']
  if (p.coverage === 'PARTIAL') tags.push('partial')
  if (p.truncated) tags.push('truncated')
  if (p.pruned) tags.push('pruned')
  return `${formatTime(p.fromUtc)} – ${formatTime(p.toUtc)} · ${tags.join(', ')}`
}

watch(() => [connection.phase, connection.generation, connection.leaseVersion],
  () => { invalidate(); void load() }, { immediate: true })

onBeforeUnmount(() => { unmounted = true; invalidate() })

async function download(): Promise<void> {
  if (exporting.value || connection.phase !== 'connected') return
  // The opaque id and fallback name are captured at submission; later selection changes cannot relabel it.
  const chosen = periods.value.find((p) => p.selectionId === selectedId.value)
  if (!chosen) return
  const id = chosen.selectionId
  const fallbackName = chosen.filename
  const mine = epoch
  const token = ++exportToken
  const generation = connection.generation
  const lease = connection.leaseVersion
  const live = () => !unmounted && mine === epoch && token === exportToken && connection.phase === 'connected' &&
    connection.generation === generation && connection.leaseVersion === lease
  exporting.value = true
  exportError.value = null
  exportController?.abort()
  const mineController = new AbortController()
  exportController = mineController
  try {
    const file = await systemApi.downloadOperationsPeriod(id, mineController.signal)
    if (!live()) return
    // The object URL exists only for this authorized response and is revoked right after the download starts.
    const url = URL.createObjectURL(new Blob([file.text], { type: 'text/csv' }))
    try {
      const link = document.createElement('a')
      link.href = url
      link.download = file.filename ?? fallbackName
      link.rel = 'noopener'
      link.click()
    } finally {
      URL.revokeObjectURL(url)
    }
  } catch (cause) {
    if (!live() || (cause instanceof ApiRequestError && cause.code === ACCOUNT_CHANGED_CODE)) return
    if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
      // Confirmed denial: withdraw all owner state, abort other work and tell the parent boundary.
      invalidate()
      error.value = 'Activity is not available for this account.'
      emit('denied')
      return
    }
    exportError.value = cause instanceof ApiRequestError && cause.status === 410
      ? 'That period is no longer available. Refresh to see the current list.'
      : 'The file could not be downloaded.'
  } finally {
    if (live()) {
      exporting.value = false
      if (exportController === mineController) exportController = null
    }
  }
}

// ---- presentation ----

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let value = bytes
  let unit = -1
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1 }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[unit]}`
}

function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toISOString().replace('T', ' ').replace(/:\d\d(\.\d+)?Z$/, ' UTC')
}

function activityText(a: ForegroundActivity | null | undefined): string {
  if (!a || a.state === 'UNAVAILABLE') return 'Unavailable'
  if (a.state === 'TRUNCATED') return `≥ ${(a.lowerBound ?? a.exactCount ?? 0).toLocaleString()}`
  const n = a.exactCount ?? a.lowerBound
  if (n === null || n === undefined) return 'Unavailable'
  return a.state === 'PARTIAL_STARTUP' ? `≥ ${n.toLocaleString()}` : n.toLocaleString()
}

function activityNote(a: ForegroundActivity | null | undefined): string {
  if (!a || a.state === 'UNAVAILABLE') return 'Activity tracking is unavailable.'
  if (a.state === 'TRUNCATED') return 'At least this many; tracking is at its capacity, so the count is a lower bound.'
  if (a.state === 'PARTIAL_STARTUP') return 'Partial: tracking started less than 5 minutes ago.'
  return ''
}

const database = computed(() => {
  const d = summary.value?.databaseSize
  if (!d || d.state === 'UNAVAILABLE' || d.bytes === null) return { text: 'Unavailable', note: 'Could not be measured.' }
  const when = `Measured ${formatTime(d.measuredAt)}`
  return d.state === 'STALE' ? { text: formatBytes(d.bytes), note: `Stale. ${when}` } : { text: formatBytes(d.bytes), note: when }
})

function pct(p: OperationsPercentile | null | undefined): string {
  if (!p) return '—'
  return p.overflow ? '>5 s' : `≤ ${p.upperBoundMs} ms`
}

/** The newest minute is a current measurement only when it is recent and actually observed. */
const current = computed(() => {
  const s = summary.value
  const latest = s?.latestMinute
  if (!s || !latest) return null
  const ageMs = new Date(s.generatedAt).getTime() - new Date(latest.startUtc).getTime()
  const fresh = ageMs <= 5 * 60_000
  return { bucket: latest, fresh, observed: latest.observedSeconds > 0 }
})

const requestRate = computed(() => {
  const c = current.value
  if (!c) return 'No recent observation'
  if (!c.fresh) return `No recent observation (latest ${formatTime(c.bucket.startUtc)})`
  if (!c.observed) return 'No observation in the latest minute'
  // An observed minute with no route groups is a known zero, not a gap.
  const perMinute = ((c.bucket.overall?.requests ?? 0) * 60) / c.bucket.observedSeconds
  return `${Math.round(perMinute * 10) / 10} / min (${c.bucket.observedSeconds} s observed)`
})

const response = computed(() => {
  const c = current.value
  return c && c.fresh && c.observed ? c.bucket.overall : null
})

interface Row { key: string; gap: boolean; label: string; bucket?: OperationsBucket }

/** Recent retained minutes only (sparse by design); a skipped run is "no stored activity", not an outage. */
const rows = computed<Row[]>(() => {
  const s = summary.value
  if (!s) return []
  const sorted = [...s.minutes].sort((a, b) => Date.parse(b.startUtc) - Date.parse(a.startUtc))
  const out: Row[] = []
  sorted.forEach((bucket, i) => {
    out.push({ key: bucket.startUtc, gap: false, label: formatTime(bucket.startUtc), bucket })
    const next = sorted[i + 1]
    if (next) {
      const missing = Math.round((Date.parse(bucket.startUtc) - Date.parse(next.startUtc)) / 60_000) - 1
      if (missing > 0) {
        out.push({ key: `gap-${bucket.startUtc}`, gap: true,
          label: `No stored activity (${missing} minute${missing === 1 ? '' : 's'})` })
      }
    }
  })
  return out
})
const pages = computed(() => Math.max(1, Math.ceil(rows.value.length / PAGE)))
const visible = computed(() => rows.value.slice(page.value * PAGE, page.value * PAGE + PAGE))

function bucketActivity(b: OperationsBucket): string {
  return activityText(b.foregroundAccounts)
}

function bucketNote(b: OperationsBucket): string {
  const notes: string[] = []
  if (b.completeness === 'PARTIAL') notes.push('Partial')
  if (b.foregroundAccounts?.state === 'TRUNCATED') notes.push('Truncated')
  return notes.join(', ')
}
</script>

<template>
  <section class="panel owner-activity" aria-labelledby="owner-activity-heading" data-test="owner-activity">
    <div class="owner-activity__heading">
      <h2 id="owner-activity-heading">Application activity</h2>
      <button type="button" class="button--secondary" :disabled="loading" data-test="activity-refresh" @click="load()">
        {{ loading ? 'Refreshing…' : 'Refresh' }}
      </button>
    </div>

    <p v-if="error" class="notice notice--warning" role="status" data-test="activity-error">{{ error }}</p>
    <template v-else-if="summary">
      <div class="owner-activity__facts" data-test="activity-facts">
        <dl class="owner-activity__fact">
          <dt>Active accounts, last 5 minutes</dt>
          <dd data-test="activity-5min">{{ activityText(summary.foregroundAccounts) }}
            <span class="meta">Distinct verified GW2 accounts with foreground activity; estimate. {{ activityNote(summary.foregroundAccounts) }}</span></dd>
        </dl>
        <dl class="owner-activity__fact">
          <dt>Active in last 60 minutes</dt>
          <dd data-test="activity-60min">{{ props.activeLast60Minutes?.toLocaleString() ?? '—' }}</dd>
        </dl>
        <dl class="owner-activity__fact">
          <dt>Total PostgreSQL database size</dt>
          <dd data-test="activity-db">{{ database.text }} <span class="meta">{{ database.note }}</span></dd>
        </dl>
        <dl class="owner-activity__fact">
          <dt>Requests</dt>
          <dd data-test="activity-requests">{{ requestRate }}</dd>
        </dl>
        <dl class="owner-activity__fact">
          <dt>Server response time</dt>
          <dd data-test="activity-latency">
            <template v-if="response">p50 {{ pct(response.p50) }}, p95 {{ pct(response.p95) }}</template>
            <template v-else>—</template>
            <span class="meta">Backend response time; does not include browser rendering.</span>
          </dd>
        </dl>
        <dl class="owner-activity__fact">
          <dt>Responses</dt>
          <dd data-test="activity-responses">
            <template v-if="response">4xx {{ response.http4xx }} · 5xx {{ response.http5xx }} · failed/incomplete {{ response.failedIncomplete }}</template>
            <template v-else>—</template>
          </dd>
        </dl>
      </div>

      <p v-if="!summary.historyAvailable" class="meta" role="status" data-test="activity-history-unavailable">
        Activity history is unavailable.
      </p>
      <p v-else-if="summary.retentionTruncated" class="meta" role="status" data-test="activity-history-truncated">
        Older history was truncated to stay within its size limit.
      </p>

      <details class="owner-activity__history" :open="historyOpen" @toggle="historyOpen = ($event.target as HTMLDetailsElement).open">
        <summary data-test="activity-history-toggle">History</summary>
        <p class="meta">Recent minutes, last 6 hours.</p>
        <p v-if="!rows.length" class="meta" data-test="activity-history-empty">No stored activity in this window.</p>
        <div v-else class="owner-activity__table-wrap">
          <table class="owner-activity__table" data-test="activity-history-table">
            <caption class="visually-hidden">Recent application activity by minute</caption>
            <thead>
              <tr>
                <th scope="col">Minute (UTC)</th>
                <th scope="col">Active accounts</th>
                <th scope="col">Requests</th>
                <th scope="col">p95 server time</th>
                <th scope="col">4xx / 5xx / failed</th>
                <th scope="col">Note</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in visible" :key="row.key" :data-test="row.gap ? 'activity-gap' : 'activity-row'">
                <template v-if="row.gap">
                  <th scope="row">{{ row.label }}</th><td colspan="5"></td>
                </template>
                <template v-else-if="row.bucket">
                  <th scope="row">{{ row.label }}</th>
                  <td>{{ bucketActivity(row.bucket) }}</td>
                  <td>{{ row.bucket.observedSeconds === 0 ? 'No observation' : (row.bucket.overall?.requests ?? 0).toLocaleString() }}</td>
                  <td>{{ pct(row.bucket.overall?.p95) }}</td>
                  <td>{{ row.bucket.overall ? `${row.bucket.overall.http4xx} / ${row.bucket.overall.http5xx} / ${row.bucket.overall.failedIncomplete}` : '0 / 0 / 0' }}</td>
                  <td>{{ bucketNote(row.bucket) }}</td>
                </template>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="pages > 1" class="owner-activity__controls" data-test="activity-pager">
          <button type="button" class="button--secondary" :disabled="page === 0" @click="page -= 1">Newer</button>
          <span class="meta" aria-live="polite">Page {{ page + 1 }} of {{ pages }}</span>
          <button type="button" class="button--secondary" :disabled="page >= pages - 1" @click="page += 1">Older</button>
        </div>
      </details>

      <form class="owner-activity__export" data-test="activity-export" @submit.prevent="download()">
        <h3>Download activity file</h3>
        <p class="meta">Active accounts are a five-minute estimate of distinct verified accounts, not simultaneous sessions. Missing minutes mean no stored activity, not zero users or an outage.</p>
        <p v-if="periodsState === 'unavailable'" class="meta" role="status" data-test="activity-export-unavailable">Activity files are temporarily unavailable.</p>
        <p v-else-if="!periods.length" class="meta" role="status" data-test="activity-export-empty">No activity has been stored yet, so there is no file to download.</p>
        <div v-else class="owner-activity__pick">
          <label class="visually-hidden" for="owner-activity-period">UTC day or week</label>
          <select id="owner-activity-period" v-model="selectedId" data-test="activity-period">
            <optgroup v-if="days.length" label="Days">
              <option v-for="p in days" :key="p.selectionId" :value="p.selectionId">{{ periodLabel(p) }}</option>
            </optgroup>
            <optgroup v-if="weeks.length" label="Weeks">
              <option v-for="p in weeks" :key="p.selectionId" :value="p.selectionId">{{ periodLabel(p) }}</option>
            </optgroup>
          </select>
          <button type="submit" :disabled="exporting || !selectedId" data-test="activity-export-submit">
            {{ exporting ? 'Preparing…' : 'Download' }}
          </button>
        </div>
        <details class="owner-activity__help">
          <summary data-test="activity-export-help">About these files</summary>
          <ul class="meta">
            <li>Days and weeks use UTC boundaries. Each file is one CSV; weeks are assembled by the server from retained days.</li>
            <li>Closed means the period has ended; partial, truncated or pruned mark known gaps in retained coverage.</li>
            <li>Request counts, errors and backend latency sit on stored active-minute rows. Backend handling time is not browser page-load time and does not show user capacity.</li>
            <li>Account estimate, request rate, latency and errors can be compared only within the same minute; that is correlation, not cause.</li>
          </ul>
        </details>
        <p v-if="exportError" class="notice notice--warning" role="alert" data-test="activity-export-error">{{ exportError }}</p>
      </form>

      <p class="meta" data-test="activity-oracle">
        <a v-if="summary.oracleConsole.configured && summary.oracleConsole.url" :href="summary.oracleConsole.url"
           target="_blank" rel="noopener noreferrer">Open Oracle host metrics</a>
        <template v-else>Host CPU and memory history: Oracle Monitoring link not configured.</template>
      </p>
    </template>
    <p v-else class="meta" role="status" data-test="activity-loading">Loading activity…</p>
  </section>
</template>
