<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { systemApi, type GlobalSystemStatus } from '@/api/systemApi'
import PageHeader from '@/shell/PageHeader.vue'
import type { SyncOperationKey } from './operations'
import { useSharedSyncOperations } from './provideSyncOperations'
import { describeOperationStatus } from './statusPresentation'
import type { SyncOperationView } from './useSyncOperations'

const sync = useSharedSyncOperations()
const systemStatus = ref<GlobalSystemStatus | null>(null)
const systemStatusError = ref<string | null>(null)
const systemStatusLoaded = ref(false)

async function loadSystemStatus(): Promise<void> {
  try {
    systemStatus.value = await systemApi.readStatus()
    systemStatusError.value = null
  } catch (cause) {
    systemStatusError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    systemStatusLoaded.value = true
  }
}

let statusTimer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  void loadSystemStatus()
  statusTimer = setInterval(() => void loadSystemStatus(), 5000)
})
onUnmounted(() => {
  if (statusTimer !== null) clearInterval(statusTimer)
})

function onTrigger(key: SyncOperationKey): void {
  void sync.trigger(key)
}

function onRetryStatus(key: SyncOperationKey): void {
  void sync.retryStatus(key)
}

function formatDate(value: string | null): string {
  if (value === null) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(date)
}

function accountStatus(): { text: string; tone: string } {
  if (systemStatusError.value !== null) return { text: 'Problem', tone: 'failure' }
  if (systemStatus.value?.accountLastRefreshedAt) return { text: 'OK', tone: 'success' }
  return { text: 'Not run yet', tone: 'idle' }
}

function globalStatus(): { text: string; tone: string } {
  const status = systemStatus.value
  if (systemStatusError.value !== null) return { text: 'Problem', tone: 'failure' }
  if (status?.running) return { text: 'Running', tone: 'busy' }
  if (status?.lastFailure) return { text: 'Problem', tone: 'failure' }
  if (status?.lastCheckedAt) return { text: 'OK', tone: 'success' }
  return { text: 'Not run yet', tone: 'idle' }
}

function priceStatus(): { text: string; tone: string } {
  if (systemStatusError.value !== null || systemStatus.value?.priceCacheError) {
    return { text: 'Problem', tone: 'failure' }
  }
  if (systemStatus.value?.cachedPriceItems !== null && systemStatus.value?.cachedPriceItems !== undefined) {
    return { text: 'Available', tone: 'success' }
  }
  return { text: 'Not checked', tone: 'idle' }
}

const account = computed(accountStatus)
const global = computed(globalStatus)
const prices = computed(priceStatus)

function operation(key: SyncOperationKey): SyncOperationView {
  const found = sync.operations.find((candidate) => candidate.key === key)
  if (found === undefined) throw new Error(`Missing System Status operation: ${key}`)
  return found
}

</script>

<template>
  <div class="system-status-page">
    <PageHeader heading="System Status" />

    <p v-if="!systemStatusLoaded" class="meta" role="status">Loading status…</p>
    <p v-else-if="systemStatusError" class="notice notice--error" role="alert">
      Status unavailable: {{ systemStatusError }}
    </p>

    <section class="status-grid" aria-label="System health">
      <article class="panel status-card" data-test="account-status">
        <div class="status-card__heading">
          <h2>Account data</h2>
          <span class="status" :class="`status--${account.tone}`" data-test="account-health">{{ account.text }}</span>
        </div>
        <dl class="status-facts">
          <dt>Last refreshed</dt>
          <dd>{{ formatDate(systemStatus?.accountLastRefreshedAt ?? null) }}</dd>
          <template v-if="systemStatus?.accountRefreshScope">
            <dt>Data refreshed</dt>
            <dd>{{ systemStatus.accountRefreshScope }}</dd>
          </template>
        </dl>
        <div class="status-card__action">
          <button
            type="button"
            class="button--secondary"
            data-test="sync-trigger-ACCOUNT_SYNC"
            :disabled="operation('ACCOUNT_SYNC').isBusy"
            @click="onTrigger('ACCOUNT_SYNC')"
          >Refresh account data</button>
          <span
            class="status"
            :class="`status--${describeOperationStatus(operation('ACCOUNT_SYNC')).tone}`"
            role="status"
            data-test="sync-state-ACCOUNT_SYNC"
          >{{ describeOperationStatus(operation('ACCOUNT_SYNC')).text }}</span>
          <button
            v-if="operation('ACCOUNT_SYNC').canRetryStatus"
            type="button"
            class="button--secondary"
            data-test="sync-status-retry-ACCOUNT_SYNC"
            @click="onRetryStatus('ACCOUNT_SYNC')"
          >Retry status check</button>
        </div>
        <p
          v-if="operation('ACCOUNT_SYNC').isBusy"
          class="meta status-card__message"
          data-test="sync-busy-reason-ACCOUNT_SYNC"
        >Refresh in progress.</p>
        <p
          v-if="operation('ACCOUNT_SYNC').status?.failure"
          class="status-card__error notice notice--error"
          data-test="sync-task-failure-ACCOUNT_SYNC"
        >{{ operation('ACCOUNT_SYNC').status?.failure?.message }} <code>{{ operation('ACCOUNT_SYNC').status?.failure?.error }}</code></p>
        <p v-if="operation('ACCOUNT_SYNC').submissionError" class="status-card__error notice notice--error" data-test="sync-submission-error-ACCOUNT_SYNC">
          {{ operation('ACCOUNT_SYNC').submissionError }}
        </p>
        <p v-if="operation('ACCOUNT_SYNC').lookupError" class="status-card__error notice notice--warning" data-test="sync-lookup-error-ACCOUNT_SYNC">
          {{ operation('ACCOUNT_SYNC').lookupError }}
        </p>
        <details v-if="operation('ACCOUNT_SYNC').taskId" class="diagnostics" data-test="sync-diagnostics-ACCOUNT_SYNC">
          <summary>Task details</summary>
          <dl class="diagnostics__body">
            <dt>Task</dt><dd data-test="sync-task-ACCOUNT_SYNC">{{ operation('ACCOUNT_SYNC').taskId }}</dd>
            <template v-if="operation('ACCOUNT_SYNC').status">
              <dt>State</dt><dd data-test="sync-reported-state-ACCOUNT_SYNC">{{ operation('ACCOUNT_SYNC').status?.state }}</dd>
              <dt>Submitted</dt><dd data-test="sync-submitted-ACCOUNT_SYNC">{{ operation('ACCOUNT_SYNC').status?.submittedAt }}</dd>
              <template v-if="operation('ACCOUNT_SYNC').status?.startedAt"><dt>Started</dt><dd data-test="sync-started-ACCOUNT_SYNC">{{ operation('ACCOUNT_SYNC').status?.startedAt }}</dd></template>
              <template v-if="operation('ACCOUNT_SYNC').status?.finishedAt"><dt>Finished</dt><dd data-test="sync-finished-ACCOUNT_SYNC">{{ operation('ACCOUNT_SYNC').status?.finishedAt }}</dd></template>
            </template>
          </dl>
        </details>
      </article>

      <article class="panel status-card" data-test="global-refresh-status">
        <div class="status-card__heading">
          <h2>Global game data</h2>
          <span class="status" :class="`status--${global.tone}`" data-test="global-health">{{ global.text }}</span>
        </div>
        <dl class="status-facts">
          <dt>Last checked</dt><dd>{{ formatDate(systemStatus?.lastCheckedAt ?? null) }}</dd>
          <dt>Last changed</dt><dd>{{ formatDate(systemStatus?.lastChangedAt ?? null) }}</dd>
          <dt>Recipes synced</dt><dd>{{ formatDate(systemStatus?.lastRecipeSyncAt ?? null) }}</dd>
          <dt>Crafting graph rebuilt</dt><dd>{{ formatDate(systemStatus?.lastGraphRebuildAt ?? null) }}</dd>
        </dl>
        <div class="status-card__action">
          <button
            type="button"
            class="button--secondary"
            data-test="sync-trigger-GLOBAL_SYNC"
            :disabled="operation('GLOBAL_SYNC').isBusy || systemStatus?.running"
            @click="onTrigger('GLOBAL_SYNC')"
          >Check global data</button>
          <span
            class="status"
            :class="`status--${describeOperationStatus(operation('GLOBAL_SYNC')).tone}`"
            role="status"
            data-test="sync-state-GLOBAL_SYNC"
          >{{ systemStatus?.running ? 'Running' : describeOperationStatus(operation('GLOBAL_SYNC')).text }}</span>
          <button
            v-if="operation('GLOBAL_SYNC').canRetryStatus"
            type="button"
            class="button--secondary"
            data-test="sync-status-retry-GLOBAL_SYNC"
            @click="onRetryStatus('GLOBAL_SYNC')"
          >Retry status check</button>
        </div>
        <p v-if="operation('GLOBAL_SYNC').isBusy" class="meta status-card__message" data-test="sync-busy-reason-GLOBAL_SYNC">Refresh in progress.</p>
        <p v-if="systemStatus?.lastFailure && !operation('GLOBAL_SYNC').status?.failure" class="status-card__error notice notice--error" data-test="global-last-failure">{{ systemStatus.lastFailure }}</p>
        <p
          v-if="operation('GLOBAL_SYNC').status?.failure"
          class="status-card__error notice notice--error"
          data-test="sync-task-failure-GLOBAL_SYNC"
        >{{ operation('GLOBAL_SYNC').status?.failure?.message }} <code>{{ operation('GLOBAL_SYNC').status?.failure?.error }}</code></p>
        <p v-if="operation('GLOBAL_SYNC').submissionError" class="status-card__error notice notice--error" data-test="sync-submission-error-GLOBAL_SYNC">
          {{ operation('GLOBAL_SYNC').submissionError }}
        </p>
        <p v-if="operation('GLOBAL_SYNC').lookupError" class="status-card__error notice notice--warning" data-test="sync-lookup-error-GLOBAL_SYNC">
          {{ operation('GLOBAL_SYNC').lookupError }}
        </p>
        <details v-if="operation('GLOBAL_SYNC').taskId" class="diagnostics" data-test="sync-diagnostics-GLOBAL_SYNC">
          <summary>Task details</summary>
          <dl class="diagnostics__body">
            <dt>Task</dt><dd data-test="sync-task-GLOBAL_SYNC">{{ operation('GLOBAL_SYNC').taskId }}</dd>
            <template v-if="operation('GLOBAL_SYNC').status">
              <dt>State</dt><dd data-test="sync-reported-state-GLOBAL_SYNC">{{ operation('GLOBAL_SYNC').status?.state }}</dd>
              <dt>Submitted</dt><dd data-test="sync-submitted-GLOBAL_SYNC">{{ operation('GLOBAL_SYNC').status?.submittedAt }}</dd>
              <template v-if="operation('GLOBAL_SYNC').status?.startedAt"><dt>Started</dt><dd data-test="sync-started-GLOBAL_SYNC">{{ operation('GLOBAL_SYNC').status?.startedAt }}</dd></template>
              <template v-if="operation('GLOBAL_SYNC').status?.finishedAt"><dt>Finished</dt><dd data-test="sync-finished-GLOBAL_SYNC">{{ operation('GLOBAL_SYNC').status?.finishedAt }}</dd></template>
            </template>
          </dl>
        </details>
      </article>

      <article class="panel status-card" data-test="price-cache-status">
        <div class="status-card__heading">
          <h2>Trading Post price cache</h2>
          <span class="status" :class="`status--${prices.tone}`" data-test="price-cache-health">{{ prices.text }}</span>
        </div>
        <p v-if="systemStatus?.priceCacheError" class="status-card__error notice notice--error">{{ systemStatus.priceCacheError }}</p>
        <dl class="status-facts">
          <dt>Cached item prices</dt><dd>{{ systemStatus?.cachedPriceItems ?? '—' }}</dd>
          <dt>Stale prices</dt><dd>{{ systemStatus?.stalePriceItems ?? '—' }}</dd>
          <dt>Newest price fetched</dt><dd>{{ formatDate(systemStatus?.newestPriceFetchedAt ?? null) }}</dd>
        </dl>
      </article>
    </section>

    <p class="meta process-note">Status timestamps reset when the backend restarts.</p>
  </div>
</template>

<style scoped>
.system-status-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 20rem), 1fr));
  gap: var(--space-4);
}

.status-card { min-width: 0; }

.status-card__heading,
.status-card__action {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.status-card__heading h2 {
  margin: 0;
  font-size: var(--text-lg);
}

.status-facts {
  display: grid;
  grid-template-columns: minmax(8rem, 1fr) minmax(0, 1.3fr);
  column-gap: var(--space-3);
  row-gap: var(--space-2);
  margin: var(--space-4) 0;
}

.status-facts dt { color: var(--color-muted); }
.status-facts dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }

.status-card__action {
  justify-content: flex-start;
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.status-card__error { margin: var(--space-3) 0 0; }

.status-card__message { margin: var(--space-2) 0 0; }
.process-note { margin: 0; }
</style>
