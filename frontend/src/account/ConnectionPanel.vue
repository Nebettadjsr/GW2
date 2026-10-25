<script setup lang="ts">
import { computed, ref } from 'vue'
import { connectKey, connection, disconnect, retryConnection } from '@/api/accountSession'

/**
 * The API-key area (`TARGET_ARCHITECTURE.md` 8.2): a masked field with Connect, Replace and
 * Disconnect, and the connection state in words. The key is handed to the session module and the
 * field is cleared at once; nothing here stores, logs or displays it.
 */
const draft = ref('')
const busy = ref(false)

const isConnected = computed(() => connection.phase === 'connected')
const isWorking = computed(() => busy.value || connection.phase === 'restoring' || connection.phase === 'connecting')

const statusText = computed(() => {
  switch (connection.phase) {
    case 'connected':
      return 'Connected to your Guild Wars 2 account.'
    case 'restoring':
      return 'Reconnecting with the key kept in this browser session…'
    case 'connecting':
      return 'Checking the key…'
    case 'unavailable':
      return 'The saved key could not be checked right now.'
    default:
      return 'Not connected. Enter a read-only GW2 API key to load your account data.'
  }
})

async function submit(): Promise<void> {
  if (isWorking.value) return
  busy.value = true
  const accepted = await connectKey(draft.value)
  busy.value = false
  if (accepted) draft.value = ''
}

async function onDisconnect(): Promise<void> {
  draft.value = ''
  await disconnect()
}
</script>

<template>
  <section class="connection-panel" aria-labelledby="connection-heading" data-test="connection-panel">
    <h2 id="connection-heading" class="connection-panel__title">GW2 API key</h2>
    <p class="connection-panel__status" role="status" data-test="connection-status">{{ statusText }}</p>

    <form class="connection-panel__form" @submit.prevent="submit">
      <label for="gw2-api-key">{{ isConnected ? 'Replace with another key' : 'API key' }}</label>
      <input
        id="gw2-api-key"
        v-model="draft"
        type="password"
        autocomplete="off"
        spellcheck="false"
        :disabled="isWorking"
        data-test="api-key-input"
      />
      <button type="submit" :disabled="isWorking" data-test="connect">
        {{ isConnected ? 'Replace key' : 'Connect' }}
      </button>
      <button
        v-if="connection.phase === 'unavailable'"
        type="button"
        :disabled="isWorking"
        data-test="retry-connection"
        @click="retryConnection()"
      >
        Try again
      </button>
      <button
        v-if="isConnected || connection.phase === 'unavailable'"
        type="button"
        data-test="disconnect"
        @click="onDisconnect"
      >
        Disconnect
      </button>
    </form>

    <p v-if="connection.error" class="connection-panel__error" role="alert" data-test="connection-error">
      {{ connection.error }}
    </p>
    <p v-if="connection.logoutNotice" class="connection-panel__notice" role="status" data-test="logout-notice">
      {{ connection.logoutNotice }}
    </p>
  </section>
</template>
