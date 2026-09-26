<script setup lang="ts">
import PageHeader from '@/shell/PageHeader.vue'
import type { SyncOperationKey } from './operations'
import { useSharedSyncOperations } from './provideSyncOperations'
import { describeOperationStatus, describeTaskState } from './statusPresentation'
import type { SyncOperationView } from './useSyncOperations'

/**
 * The synchronization area: one trigger per backend operation and the state of the task it created
 * (`FRONTEND_UX_GUIDELINES.md` 2, 6; `CURRENT_ARCHITECTURE.md` 5.7–5.9).
 *
 * This is a whole application area of its own, so the operation controls and their task detail no
 * longer sit on top of the crafting analysis. Nothing is started by opening it: every request follows
 * a deliberate click, and the tracking state belongs to the shell, so opening this page neither
 * repeats a trigger nor starts a second polling loop.
 *
 * What the user reads first is what happened; task identifiers, timestamps and the backend's own
 * state code are available under "Technical details" and nowhere else.
 */
const sync = useSharedSyncOperations()

function onTrigger(key: SyncOperationKey): void {
  void sync.trigger(key)
}

function onRetryStatus(key: SyncOperationKey): void {
  void sync.retryStatus(key)
}

/** Description, plus the reason a disabled trigger is disabled, as the button's own description. */
function describedBy(operation: SyncOperationView): string {
  const ids = [`sync-about-${operation.key}`]
  if (operation.isBusy) ids.push(`sync-busy-${operation.key}`)
  return ids.join(' ')
}

/** Technical evidence exists once a task was accepted; before that there is nothing to disclose. */
function hasDiagnostics(operation: SyncOperationView): boolean {
  return operation.taskId !== null
}
</script>

<template>
  <PageHeader
    heading="Synchronization"
    intro="Copy account and game data from Guild Wars 2 into this tool, and refresh Trading Post prices. Each button starts one backend operation; nothing here starts on its own."
  />

  <p class="meta prose" data-test="sync-session-note">
    Tracking belongs to this open page: switching to another area keeps it, and leaving a page never
    cancels or restarts backend work. Reloading the browser starts with nothing tracked — the
    operations carry on in the backend, but this page can no longer follow them.
  </p>

  <section class="stack" aria-labelledby="sync-operations-heading" data-test="sync-controls">
    <h2 id="sync-operations-heading">Operations</h2>

    <ul class="operations">
      <li
        v-for="operation in sync.operations"
        :key="operation.key"
        class="panel operation"
        :data-test="`sync-operation-${operation.key}`"
      >
        <div class="operation__head">
          <button
            type="button"
            class="button--primary"
            :data-test="`sync-trigger-${operation.key}`"
            :disabled="operation.isBusy"
            :aria-describedby="describedBy(operation)"
            @click="onTrigger(operation.key)"
          >
            {{ operation.label }}
          </button>

          <span
            class="status"
            :class="`status--${describeOperationStatus(operation).tone}`"
            role="status"
            :data-test="`sync-state-${operation.key}`"
          >
            {{ describeOperationStatus(operation).text }}
          </span>
        </div>

        <p :id="`sync-about-${operation.key}`" class="meta prose">{{ operation.description }}</p>

        <p
          v-if="operation.isBusy"
          :id="`sync-busy-${operation.key}`"
          class="meta"
          :data-test="`sync-busy-reason-${operation.key}`"
        >
          Unavailable until this operation's current task finishes. The other operations stay
          available.
        </p>

        <p
          v-if="operation.status !== null && operation.status.failure !== null"
          class="notice notice--error"
          :data-test="`sync-task-failure-${operation.key}`"
        >
          {{ operation.status.failure.message }}
          <span class="meta block">
            Reported by the backend as
            <code>{{ operation.status.failure.error }}</code
            >. Steps that had already finished stay in place; which ones those were is not reported.
          </span>
        </p>

        <p
          v-if="operation.submissionError !== null"
          class="notice"
          :class="operation.isAdmissionUnknown ? 'notice--warning' : 'notice--error'"
          :data-test="`sync-submission-error-${operation.key}`"
        >
          <template v-if="operation.isAdmissionUnknown">
            The request got no answer, so whether the backend started this operation is unknown.
            Starting it again may start a second run.
          </template>
          <template v-else>
            The backend did not start this operation, so nothing is running from this click.
          </template>
          <span class="meta block">Backend answer: {{ operation.submissionError }}</span>
        </p>

        <p
          v-if="operation.lookupError !== null"
          class="notice notice--warning"
          :data-test="`sync-lookup-error-${operation.key}`"
        >
          <template v-if="operation.isTaskUnresolvable">
            This backend no longer knows the task, so its outcome cannot be established here — that
            means neither success, nor failure, nor a rollback. Task records only live as long as the
            backend process, so asking again would establish nothing.
          </template>
          <template v-else>
            The task state is no longer being followed, so the outcome is not established here. The
            operation itself is unaffected and may still be running in the backend.
          </template>
          <span class="meta block">Lookup answer: {{ operation.lookupError }}</span>

          <span v-if="operation.canRetryStatus" class="notice__actions">
            <button
              type="button"
              :data-test="`sync-status-retry-${operation.key}`"
              @click="onRetryStatus(operation.key)"
            >
              Check state again
            </button>
          </span>
        </p>

        <details
          v-if="hasDiagnostics(operation)"
          class="diagnostics"
          :data-test="`sync-diagnostics-${operation.key}`"
        >
          <summary>Technical details</summary>
          <dl class="diagnostics__body">
            <dt>Task</dt>
            <dd :data-test="`sync-task-${operation.key}`">{{ operation.taskId }}</dd>

            <template v-if="operation.status !== null">
              <dt>Backend state</dt>
              <dd :data-test="`sync-reported-state-${operation.key}`">
                <code>{{ operation.status.state }}</code>
                — {{ describeTaskState(operation.status.state).text }}
              </dd>

              <dt>Submitted</dt>
              <dd :data-test="`sync-submitted-${operation.key}`">
                {{ operation.status.submittedAt }}
              </dd>

              <template v-if="operation.status.startedAt !== null">
                <dt>Started</dt>
                <dd :data-test="`sync-started-${operation.key}`">
                  {{ operation.status.startedAt }}
                </dd>
              </template>

              <template v-if="operation.status.finishedAt !== null">
                <dt>Finished</dt>
                <dd :data-test="`sync-finished-${operation.key}`">
                  {{ operation.status.finishedAt }}
                </dd>
              </template>
            </template>
          </dl>
        </details>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.operations {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  list-style: none;
  margin: 0;
  padding: 0;
  /* Action rows read as a column of decisions, not as a wall of full-width panels. */
  max-width: 64rem;
}

.operation > * + * {
  margin-top: var(--space-3);
}

.operation__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

/* The trigger carries the operation's name, so it needs room for a full sentence — and keeps its
   width in every state, since the label does not change while a task is tracked. */
.operation__head button {
  flex: 1 1 22rem;
  justify-content: flex-start;
  text-align: left;
}

/* Secondary evidence inside a notice: its own line, quieter, never the first thing read. */
.block {
  display: block;
  margin-top: var(--space-2);
}
</style>
