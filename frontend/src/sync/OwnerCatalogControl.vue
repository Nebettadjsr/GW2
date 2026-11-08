<script setup lang="ts">
import { computed } from 'vue'
import { describeOperationStatus } from './statusPresentation'
import type { SyncOperationView } from './useSyncOperations'

const props = defineProps<{ operation: SyncOperationView; description: string }>()
const emit = defineEmits<{ trigger: []; retryStatus: [] }>()

const key = computed(() => props.operation.key)
const state = computed(() => describeOperationStatus(props.operation))
/** An already-running refresh is ordinary busy feedback, not a failure of this client. */
const isBusyRefusal = computed(() => props.operation.submissionCode === 'SYNC_ALREADY_RUNNING')
</script>

<template>
  <article class="panel status-card" :data-test="`owner-control-${key}`">
    <div class="status-card__heading">
      <h3>{{ operation.label }}</h3>
      <span class="status" :class="`status--${state.tone}`" role="status" :data-test="`sync-state-${key}`">{{ state.text }}</span>
    </div>
    <p class="meta">{{ description }}</p>
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
.status-card { min-width: 0; }
.status-card__heading,
.status-card__action { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); }
.status-card__heading { justify-content: space-between; }
.status-card__heading h3 { margin: 0; font-size: var(--text-md, 1rem); }
.status-card__action { padding-top: var(--space-3); border-top: 1px solid var(--color-border); }
.status-card__error, .status-card__message { margin: var(--space-3) 0 0; }
</style>
