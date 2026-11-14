<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { systemApi, type SavedSourceOverview, type GlobalSystemStatus, type OwnerSystemDetails, type SharedHealth } from '@/api/systemApi'
import { connection } from '@/api/accountSession'
import { ACCOUNT_CHANGED_CODE, ApiRequestError } from '@/api/http'
import ConnectionPanel from '@/account/ConnectionPanel.vue'
import PageHeader from '@/shell/PageHeader.vue'
import OwnerCatalogControl from './OwnerCatalogControl.vue'
import OwnerActivity from './OwnerActivity.vue'
import type { OwnerOperationKey, SyncOperationKey } from './operations'
import { useSharedSyncOperations } from './provideSyncOperations'
import { describeOperationStatus } from './statusPresentation'
import type { SyncOperationView } from './useSyncOperations'

/**
 * Owner capability is decided only by the backend's answer to the owner-details request, held in
 * memory for the current account generation. `checking` and `unavailable` show nothing privileged.
 */
type OwnerState = 'checking' | 'owner' | 'not-owner' | 'unavailable'

const sync = useSharedSyncOperations()
const systemStatus = ref<GlobalSystemStatus | null>(null)
const systemStatusError = ref<string | null>(null)
const systemStatusLoaded = ref(false)
const ownerState = ref<OwnerState>('checking')
const ownerDetails = ref<OwnerSystemDetails | null>(null)

const isConnected = computed(() => connection.phase === 'connected')

type DatasetField = 'items' | 'recipes' | 'tradingPostPrices'
const OWNER_CONTROLS: readonly {
  key: OwnerOperationKey; title: string; description: string
  field: DatasetField; noun: string; unknownMeaning: string
}[] = [
  { key: 'FULL_ITEM_CATALOG', title: 'Items', description: 'Update all item information.',
    field: 'items', noun: 'items', unknownMeaning: 'Not yet fetched' },
  { key: 'FULL_RECIPE_CATALOG', title: 'Recipes', description: 'Update all recipe information.',
    field: 'recipes', noun: 'recipes', unknownMeaning: 'Refresh time unknown' },
  { key: 'FULL_TP_CATALOG', title: 'Trading Post prices', description: 'Update all tradable items, including those without current offers.',
    field: 'tradingPostPrices', noun: 'price entries', unknownMeaning: 'Refresh time unknown' }
]

let statusInflight: number | null = null
/**
 * Identifies the current proof (account generation plus lease). Bumped on every connect, disconnect,
 * account replacement, lease renewal/permission change and on disposal, so a generic-status answer
 * requested under an earlier proof can neither apply its grant-scoped counts nor block a fresh request.
 */
let statusEpoch = 0
let ownerInflight: number | null = null
/** Bumped whenever owner presentation is withdrawn; any discovery answer from before is discarded. */
let ownerEpoch = 0
let isUnmounted = false

/** True while the answer being applied still belongs to the account it was requested for. */
function isCurrent(generation: number): boolean {
  return connection.phase === 'connected' && connection.generation === generation
}

async function loadSystemStatus(): Promise<void> {
  if (!isConnected.value || isUnmounted) return
  const generation = connection.generation
  const epoch = statusEpoch
  const lease = connection.leaseVersion
  if (statusInflight === epoch) return
  statusInflight = epoch
  const isLive = () => !isUnmounted && epoch === statusEpoch && lease === connection.leaseVersion && isCurrent(generation)
  try {
    const status = await systemApi.readStatus()
    if (!isLive()) return
    systemStatus.value = status
    systemStatusError.value = null
  } catch (cause) {
    if (!isLive() || isAccountChange(cause)) return
    systemStatusError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    if (statusInflight === epoch) statusInflight = null
    if (isLive()) systemStatusLoaded.value = true
  }
}

/** Server-authoritative discovery; a 403 is ordinary non-owner discovery, not an error. */
async function loadOwnerDetails(): Promise<void> {
  if (!isConnected.value) return
  const generation = connection.generation
  const epoch = ownerEpoch
  if (ownerInflight === epoch) return
  ownerInflight = epoch
  try {
    const details = await systemApi.readOwnerDetails()
    if (isUnmounted || epoch !== ownerEpoch || !isCurrent(generation)) return
    ownerDetails.value = details
    ownerState.value = 'owner'
  } catch (cause) {
    if (isUnmounted || epoch !== ownerEpoch || !isCurrent(generation) || isAccountChange(cause)) return
    // Only a confirmed denial forgets tracked tasks; an outage hides owner presentation but leaves
    // the shell-owned tracking untouched (it is neither a new task nor an authorization success).
    if (cause instanceof ApiRequestError && cause.status === 403) withdrawOwner('not-owner', true)
    else withdrawOwner('unavailable', false)
  } finally {
    if (ownerInflight === epoch) ownerInflight = null
  }
}

function isAccountChange(cause: unknown): boolean {
  return cause instanceof ApiRequestError && cause.code === ACCOUNT_CHANGED_CODE
}

/**
 * Removes every owner payload and control at once. Shell-owned owner task tracking is cleared only
 * when asked: account/lease changes are already handled by the tracker itself, so merely
 * re-discovering capability (for example on remount) must not drop an active task.
 */
function withdrawOwner(next: OwnerState, resetTasks: boolean): void {
  ownerEpoch += 1
  ownerState.value = next
  ownerDetails.value = null
  if (resetTasks) sync.resetOwnerOperations()
}

// A new account generation (or no connection) forgets everything of the former account immediately.
watch(
  () => [isConnected.value, connection.generation, connection.leaseVersion] as [boolean, number, number],
  ([connected, generation], previous) => {
    const previousGeneration = previous?.[1] ?? -1
    // Any proof change invalidates in-flight generic answers and withdraws grant-scoped own counts at once.
    statusEpoch += 1
    if (systemStatus.value) systemStatus.value = { ...systemStatus.value, accountOverview: undefined }
    // A renewed lease for the same account only re-discovers owner capability; ordinary data stays.
    if (generation !== previousGeneration || !connected) {
      systemStatus.value = null
      systemStatusError.value = null
      systemStatusLoaded.value = false
    }
    withdrawOwner('checking', false)
    if (connected) {
      void loadSystemStatus()
      void loadOwnerDetails()
    }
  },
  { immediate: true }
)

const statusTimer = setInterval(() => {
  void loadSystemStatus()
  // Non-owners are discovered once per generation; owner details keep refreshing while owner or unreachable.
  if (ownerState.value === 'owner' || ownerState.value === 'unavailable') void loadOwnerDetails()
}, 5000)
onUnmounted(() => {
  isUnmounted = true
  statusEpoch += 1
  clearInterval(statusTimer)
})

// An owner action or owner task read denied by the backend removes owner presentation without a
// retry. Only owner-only operations count: an ordinary account-refresh 403 says nothing about owners.
const ownerDenied = computed(() => OWNER_CONTROLS.some(({ key }) => {
  const op = operation(key)
  return op.submissionStatus === 403 || op.lookupStatus === 403
}))
watch(ownerDenied, (denied) => { if (denied) withdrawOwner('not-owner', true) }, { flush: 'sync' })

function onTrigger(key: SyncOperationKey): void {
  void sync.trigger(key)
}

function onRetryStatus(key: SyncOperationKey): void {
  void sync.retryStatus(key)
}

function formatDate(value: string | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(date)
}

interface Label { text: string; tone: string }

function accountStatus(): Label {
  if (systemStatusError.value !== null) return { text: 'Problem', tone: 'failure' }
  if (systemStatus.value?.accountLastRefreshedAt) return { text: 'OK', tone: 'success' }
  return { text: 'Not run yet', tone: 'idle' }
}

/** Generic shared health is exactly one of four labels and nothing more. */
function sharedLabel(health: SharedHealth | undefined): Label {
  if (systemStatusError.value !== null) return { text: 'Problem', tone: 'failure' }
  switch (health) {
    case 'OK': return { text: 'OK', tone: 'success' }
    case 'RUNNING': return { text: 'Running', tone: 'busy' }
    case 'PROBLEM': return { text: 'Problem', tone: 'failure' }
    default: return { text: 'Not run yet', tone: 'idle' }
  }
}

interface SavedRow { key: string; label: string; value: string; note: string }

/** A count is shown only for a published source; every other state says why, never "0". */
function savedValue(source: SavedSourceOverview | undefined, pick: 'count' | 'total'): { value: string; note: string } | null {
  if (source === undefined) return null
  const n = pick === 'count' ? source.count : (source.total ?? null)
  switch (source.state) {
    case 'CURRENT':
    case 'STALE':
      if (n === null) return { value: 'Unknown', note: '' }
      return { value: n.toLocaleString(), note: source.state === 'STALE' ? 'may be out of date' : '' }
    case 'NOT_LOADED': return { value: 'Not loaded yet', note: '' }
    default: return { value: 'Unknown', note: 'could not be read' }
  }
}

const savedRows = computed<SavedRow[]>(() => {
  const o = systemStatus.value?.accountOverview
  if (!o || systemStatusError.value !== null) return []
  const defs: [string, string, SavedSourceOverview | undefined, 'count' | 'total'][] = [
    ['characters', 'Characters', o.characters, 'count'],
    ['recipes', 'Unlocked recipes', o.recipes, 'count'],
    ['material-types', 'Material types', o.materials, 'count'],
    ['material-total', 'Total materials stored', o.materials, 'total']
  ]
  const rows: SavedRow[] = []
  for (const [key, label, src, pick] of defs) {
    const v = savedValue(src, pick)
    if (v) rows.push({ key, label, ...v })
  }
  return rows
})

const ownerLayout = computed(() => ownerState.value === 'owner' && !!ownerDetails.value?.accounts)
const account = computed(accountStatus)
const global = computed(() => sharedLabel(systemStatus.value?.globalHealth))
const prices = computed(() => sharedLabel(systemStatus.value?.priceHealth))

function operation(key: SyncOperationKey): SyncOperationView {
  const found = sync.operations.find((candidate) => candidate.key === key)
  if (found === undefined) throw new Error(`Missing System Status operation: ${key}`)
  return found
}
</script>

<template>
  <div class="system-status-page">
    <PageHeader heading="System Status" />

    <ConnectionPanel />

    <template v-if="isConnected">
    <p v-if="!systemStatusLoaded" class="meta" role="status">Loading status…</p>
    <p v-else-if="systemStatusError" class="notice notice--error" role="alert">
      Status unavailable: {{ systemStatusError }}
    </p>

    <p class="meta" data-test="status-data-explainer">
      Items, recipes and Trading Post prices are shared by all accounts.
    </p>

    <section class="status-grid health-grid" :class="{ 'health-grid--single': !ownerLayout }" aria-label="System health">
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
        <dl v-if="savedRows.length" class="account-overview" aria-label="Saved account overview">
          <div v-for="row in savedRows" :key="row.key">
            <dt>{{ row.label }}</dt>
            <dd :data-test="`saved-${row.key}`">{{ row.value }}<span v-if="row.note" class="meta">{{ row.note }}</span></dd>
          </div>
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

      <div v-if="ownerState === 'owner' && ownerDetails?.accounts" class="shared-health">
      <article class="panel status-card" data-test="owner-accounts">
        <div class="status-card__heading"><h2>Accounts</h2></div>
        <p v-if="ownerDetails.accounts.state !== 'AVAILABLE' || ownerDetails.accounts.totalAccounts === null" class="meta" data-test="owner-accounts-unavailable">
          Account counts are temporarily unavailable.
        </p>
        <dl v-else class="status-facts">
          <dt>Stored accounts</dt><dd data-test="owner-accounts-total">{{ ownerDetails.accounts.totalAccounts.toLocaleString() }}</dd>
          <dt>Active in last 60 min</dt><dd data-test="owner-accounts-active">{{ ownerDetails.accounts.activeLast60Minutes?.toLocaleString() ?? '—' }}</dd>
        </dl>
      </article>
      </div>
    </section>

    <p v-if="!ownerLayout" class="service-line" role="status" data-test="shared-service-line">
      <span class="service-line__label">Shared game data:</span>
      <span class="service-line__item" data-test="global-refresh-status">Items and recipes
        <span class="status" :class="`status--${global.tone}`" data-test="global-health">{{ global.text }}</span></span>
      <span class="service-line__item" data-test="price-cache-status">Trading Post prices
        <span class="status" :class="`status--${prices.tone}`" data-test="price-cache-health">{{ prices.text }}</span></span>
    </p>

    <p v-if="ownerState === 'unavailable'" class="notice notice--warning" role="status" data-test="owner-status-unavailable">
      Owner tools are temporarily unavailable. Retrying automatically.
    </p>

    <section v-if="ownerState === 'owner' && ownerDetails" class="owner-admin" aria-label="Owner administration" data-test="owner-admin">
      <h2>Shared game data</h2>
      <div class="status-grid">
        <OwnerCatalogControl
          v-for="control in OWNER_CONTROLS"
          :key="control.key"
          :operation="operation(control.key)"
          :title="control.title"
          :description="control.description"
          :dataset="ownerDetails[control.field]"
          :noun="control.noun"
          :unknown-meaning="control.unknownMeaning"
          :stale-prices="control.key === 'FULL_TP_CATALOG' ? ownerDetails.stalePriceItems : undefined"
          @trigger="onTrigger(control.key)"
          @retry-status="onRetryStatus(control.key)"
        />
      </div>
      <article class="status-card" data-test="owner-diagnostics">
        <p v-if="ownerDetails.lastFailure" class="status-card__error notice notice--error" data-test="global-last-failure">{{ ownerDetails.lastFailure }}</p>
        <p v-if="ownerDetails.priceCacheError" class="status-card__error notice notice--error">{{ ownerDetails.priceCacheError }}</p>
        <details class="panel background-checks">
        <summary>Automatic reference checks</summary>
        <dl class="status-facts">
          <dt>Last checked</dt><dd>{{ formatDate(ownerDetails.lastCheckedAt) }}</dd>
          <dt>Last changed</dt><dd>{{ formatDate(ownerDetails.lastChangedAt) }}</dd>
          <dt>Recipe refresh</dt><dd>{{ formatDate(ownerDetails.lastRecipeSyncAt) }}</dd>
          <dt>Crafting graph rebuilt</dt><dd>{{ formatDate(ownerDetails.lastGraphRebuildAt) }}</dd>
        </dl>
        </details>
      </article>
    </section>
    <OwnerActivity v-if="ownerState === 'owner' && ownerDetails" :active-last60-minutes="ownerDetails.accounts?.activeLast60Minutes ?? null" />
    </template>
  </div>
</template>

<style scoped>
.system-status-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  width: 100%;
  max-width: 80rem;
  margin-inline: auto;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 20rem), 1fr));
  gap: var(--space-4);
}

.health-grid {
  grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr);
  align-items: start;
}

.health-grid--single { grid-template-columns: minmax(0, 1fr); }
.service-line { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2) var(--space-4); margin: 0; font-size: 0.9rem; }
.service-line__label { color: var(--color-text-muted, inherit); }
.shared-health { display: grid; gap: var(--space-4); min-width: 0; }

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
  grid-template-columns: max-content minmax(0, 1fr);
  column-gap: var(--space-3);
  row-gap: var(--space-2);
  margin: var(--space-4) 0;
}

.status-facts dt { color: var(--color-muted); }
.status-facts dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }

.account-overview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 8rem), 1fr));
  gap: var(--space-4);
  margin: 0 0 var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
}
.account-overview > div { min-width: 0; }
.account-overview dt { color: var(--color-muted); }
.account-overview dd {
  margin: var(--space-2) 0 0;
  font-size: var(--text-xl, 1.5rem);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.account-overview .meta {
  display: block;
  margin-top: var(--space-1);
  font-size: var(--text-sm, 0.875rem);
  font-weight: 400;
}

.status-card__action {
  justify-content: flex-start;
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.status-card__error { margin: var(--space-3) 0 0; }

.status-card__message { margin: var(--space-2) 0 0; }
.owner-admin { display: flex; flex-direction: column; gap: var(--space-4); }
.owner-admin h2, .owner-admin h3 { margin: 0; }
.owner-admin .status-facts {
  grid-template-columns: max-content minmax(0, 1fr) max-content minmax(0, 1fr);
  column-gap: var(--space-5);
}

@media (max-width: 64rem) {
  .owner-admin .status-facts { grid-template-columns: max-content minmax(0, 1fr); }
}

@media (max-width: 48rem) {
  .health-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
