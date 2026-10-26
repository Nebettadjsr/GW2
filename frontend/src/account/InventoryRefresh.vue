<script setup lang="ts">
import { computed, watch } from 'vue'
import { useSharedSyncOperations } from '@/sync/provideSyncOperations'
import { describeOperationStatus } from '@/sync/statusPresentation'

const emit = defineEmits<{ updated: [] }>()
const sync = useSharedSyncOperations()
const account = sync.operations.find((operation) => operation.key === 'ACCOUNT_SYNC')
if (!account) throw new Error('Account refresh operation is missing.')
const status = computed(() => describeOperationStatus(account))
const successfulTask = computed(() => !account.isBusy && !account.lookupError && !account.submissionError
  && account.status?.state === 'SUCCEEDED' ? account.taskId : null)
watch(successfulTask, (task) => { if (task) emit('updated') })
</script>

<template>
  <section class="inventory-refresh" aria-label="Account refresh">
    <button type="button" class="button--primary" data-test="inventory-refresh"
      :disabled="account.isBusy" @click="sync.trigger('ACCOUNT_SYNC')">Refresh account data</button>
    <span v-if="account.taskId || account.submissionError || account.lookupError" role="status"
      data-test="inventory-refresh-status">{{ status.text }}</span>
    <p class="meta">Downloads your account data, including Bank and Materials, from Guild Wars 2.</p>
    <p v-if="account.isBusy" class="meta">Refresh in progress. You can keep browsing.</p>
    <p v-if="account.status?.failure" class="notice notice--error" role="alert">{{ account.status.failure.message }}</p>
    <p v-if="account.submissionError" class="notice notice--error" role="alert">
      {{ account.isAdmissionUnknown ? 'The request could not be confirmed. Check System Status before starting another refresh.' : 'The refresh could not start. Try again or check your connection in System Status.' }}
    </p>
    <p v-if="account.lookupError" class="notice notice--warning" role="alert">
      The refresh outcome could not be confirmed. Saved data has not been reloaded.
      <button v-if="account.canRetryStatus" type="button" data-test="inventory-refresh-retry"
        @click="sync.retryStatus('ACCOUNT_SYNC')">Check refresh status</button>
      <a v-else href="#/synchronization">Open System Status</a>
    </p>
  </section>
</template>

<style scoped>
.inventory-refresh { margin-block: var(--space-4); }
[role="status"] { margin-inline-start: var(--space-3); }
</style>
