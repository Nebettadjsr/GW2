<script setup lang="ts">
import { computed } from 'vue'
import { describeOperationStatus } from './statusPresentation'
import type { DatasetSummary } from '@/api/systemApi'
import type { SyncOperationView } from './useSyncOperations'

const props = defineProps<{
  operation: SyncOperationView
  title: string
  description: string
  dataset?: DatasetSummary | null
  /** Plural noun for stored rows, e.g. "items". */
  noun: string
  /** What a row without a fetch time means for this dataset. */
  unknownMeaning: string
  stalePrices?: number | null
}>()
const emit = defineEmits<{ trigger: []; retryStatus: [] }>()

const key = computed(() => props.operation.key)

function formatInstant(value: string | null): string {
  if (value === null) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(date)
}

/** Honest coverage: no count implies completeness, and absent data is never shown as zero or fresh. */
const coverage = computed(() => {
  const d = props.dataset
  if (d === undefined || d === null) return { kind: 'unknown' as const }
  if (d.state !== 'AVAILABLE' || d.storedRows === null) return { kind: 'unavailable' as const }
  if (d.storedRows === 0) return { kind: 'empty' as const }
  const stamped = d.timestampedRows ?? 0
  return {
    kind: stamped === 0 ? 'untimed' as const : 'known' as const,
    stored: d.storedRows,
    stamped,
    unknown: d.unknownTimestampRows ?? 0,
    oldest: formatInstant(d.oldestFetchedAt),
    newest: formatInstant(d.newestFetchedAt)
  }
})
const state = computed(() => describeOperationStatus(props.operation))
/** An already-running refresh is ordinary busy feedback, not a failure of this client. */
const isBusyRefusal = computed(() => props.operation.submissionCode === 'SYNC_ALREADY_RUNNING')
</script>

<template>
  <article class="panel status-card" :data-test="`owner-control-${key}`">
    <div class="status-card__heading">
      <h3>{{ title }}</h3>
      <span v-show="operation.taskId || operation.isBusy || operation.submissionError || operation.lookupError" class="status" :class="`status--${state.tone}`" role="status" :data-test="`sync-state-${key}`">{{ state.text }}</span>
    </div>
    <p class="meta">{{ description }}</p>
    <div class="coverage" :data-test="`dataset-${key}`" :data-coverage="coverage.kind">
      <p v-if="coverage.kind === 'unknown'" class="meta">Stored data: unknown.</p>
      <p v-else-if="coverage.kind === 'unavailable'" class="meta" :data-test="`dataset-unavailable-${key}`">Stored data is temporarily unavailable.</p>
      <p v-else-if="coverage.kind === 'empty'" class="meta" :data-test="`dataset-empty-${key}`">No stored {{ noun }}.</p>
      <dl v-else class="coverage__facts">
        <dt>Stored {{ noun }}</dt><dd :data-test="`dataset-stored-${key}`">{{ coverage.stored.toLocaleString() }}</dd>
        <template v-if="coverage.kind === 'untimed'">
          <dt>Last refreshed</dt><dd :data-test="`dataset-untimed-${key}`">Unknown for all stored {{ noun }}</dd>
        </template>
        <template v-else>
          <template v-if="key === 'FULL_ITEM_CATALOG' && coverage.unknown > 0">
            <dt>Synced items</dt><dd :data-test="`dataset-synced-${key}`">{{ coverage.stamped.toLocaleString() }}</dd>
          </template>
          <template v-if="coverage.unknown > 0">
            <dt>{{ unknownMeaning }}</dt><dd :data-test="`dataset-unknown-${key}`">{{ coverage.unknown.toLocaleString() }}</dd>
          </template>
          <dt>Oldest refresh</dt><dd :data-test="`dataset-oldest-${key}`">{{ coverage.oldest }}</dd>
          <dt>Newest refresh</dt><dd :data-test="`dataset-newest-${key}`">{{ coverage.newest }}</dd>
          <template v-if="key === 'FULL_TP_CATALOG' && stalePrices != null">
            <dt>Need refresh</dt><dd>{{ stalePrices.toLocaleString() }}</dd>
          </template>
        </template>
      </dl>
    </div>
    <div class="status-card__action">
      <button
        type="button"
        class="button--secondary"
        :data-test="`sync-trigger-${key}`"
        :disabled="operation.isBusy"
        @click="emit('trigger')"
      >{{ operation.label }}</button>
      <button
        v-if="operation.canRetryStatus"
        type="button"
        class="button--secondary"
        :data-test="`sync-status-retry-${key}`"
        @click="emit('retryStatus')"
      >Retry status check</button>
    </div>
    <p v-if="operation.isBusy" class="meta status-card__message" :data-test="`sync-busy-reason-${key}`">Refresh in progress.</p>
    <p v-if="operation.status?.failure" class="status-card__error notice notice--error" :data-test="`sync-task-failure-${key}`">
      {{ operation.status.failure.message }} <code>{{ operation.status.failure.error }}</code>
    </p>
    <p v-if="operation.submissionError && isBusyRefusal" class="status-card__error notice notice--warning" :data-test="`sync-busy-refusal-${key}`">
      Another catalog refresh is already running. Try again when it has finished; nothing was started.
    </p>
    <p v-else-if="operation.submissionError" class="status-card__error notice notice--error" :data-test="`sync-submission-error-${key}`">
      {{ operation.submissionError }}
    </p>
    <p v-if="operation.lookupError" class="status-card__error notice notice--warning" :data-test="`sync-lookup-error-${key}`">
      {{ operation.lookupError }}
    </p>
    <details v-if="operation.taskId" class="diagnostics" :data-test="`sync-diagnostics-${key}`">
      <summary>Task details</summary>
      <dl class="diagnostics__body">
        <dt>Task</dt><dd :data-test="`sync-task-${key}`">{{ operation.taskId }}</dd>
        <template v-if="operation.status">
          <dt>State</dt><dd :data-test="`sync-reported-state-${key}`">{{ operation.status.state }}</dd>
          <dt>Submitted</dt><dd>{{ operation.status.submittedAt }}</dd>
          <template v-if="operation.status.startedAt"><dt>Started</dt><dd>{{ operation.status.startedAt }}</dd></template>
          <template v-if="operation.status.finishedAt"><dt>Finished</dt><dd>{{ operation.status.finishedAt }}</dd></template>
        </template>
      </dl>
    </details>
  </article>
</template>

<style scoped>
.status-card { min-width: 0; display: flex; flex-direction: column; }
.status-card__heading,
.status-card__action { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); }
.status-card__heading { justify-content: space-between; }
.status-card__heading h3 { margin: 0; font-size: var(--text-md, 1rem); }
.meta { margin: var(--space-3) 0; }
.status-card__action { margin-top: auto; padding-top: var(--space-3); border-top: 1px solid var(--color-border); }
.coverage { margin: 0 0 var(--space-3); }
.coverage__facts { display: grid; grid-template-columns: max-content minmax(0, 1fr); column-gap: var(--space-3); row-gap: var(--space-1, 0.25rem); margin: 0; }
.coverage__facts dt { color: var(--color-muted); }
.coverage__facts dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
.status-card__error, .status-card__message { margin: var(--space-3) 0 0; }
</style>
