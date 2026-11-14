<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { connectKey, connection, disconnect, retryConnection } from '@/api/accountSession'

/**
 * The API-key area (`TARGET_ARCHITECTURE.md` 8.2): a masked field with Connect, Replace and
 * Disconnect, and the connection state in words. The key is handed to the session module and the
 * field is cleared at once; nothing here stores, logs or displays it.
 */
const draft = ref('')
const busy = ref(false)
/** Presentation-only: whether the typed draft (never a stored key) is shown as text. */
const showDraft = ref(false)
/** True while this panel's own connect attempt runs; it bumps the account generation itself. */
let ownAttempt = false
type HelpKind = 'create' | 'safety'
const help = ref<HelpKind | null>(null)
const helpTriggers: Record<HelpKind, HTMLButtonElement | null> = { create: null, safety: null }
const helpClose = ref<HTMLButtonElement | null>(null)

// An account change that did not come from this panel's own attempt discards the draft.
watch(
  () => connection.generation,
  () => {
    if (ownAttempt) return
    draft.value = ''
    showDraft.value = false
  },
  { flush: 'sync' }
)

async function openHelp(kind: HelpKind): Promise<void> {
  help.value = help.value === kind ? null : kind
  if (help.value) {
    await nextTick()
    helpClose.value?.focus()
  }
}

async function closeHelp(): Promise<void> {
  const kind = help.value
  help.value = null
  if (kind) {
    await nextTick()
    helpTriggers[kind]?.focus()
  }
}

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
  ownAttempt = true
  let accepted = false
  try {
    accepted = await connectKey(draft.value)
  } finally {
    ownAttempt = false
    busy.value = false
  }
  if (accepted) {
    draft.value = ''
    showDraft.value = false
  }
}

async function onDisconnect(): Promise<void> {
  draft.value = ''
  showDraft.value = false
  await disconnect()
}
</script>

<template>
  <section class="connection-panel" aria-labelledby="connection-heading" data-test="connection-panel">
    <h2 id="connection-heading" class="connection-panel__title">GW2 API key</h2>
    <p class="connection-panel__status" role="status" data-test="connection-status">{{ statusText }}</p>

    <form class="connection-panel__form" @submit.prevent="submit">
      <label for="gw2-api-key">{{ isConnected ? 'Replace with another key' : 'API key' }}</label>
      <span class="connection-panel__help-links">
        <button
          :ref="(el) => (helpTriggers.create = el as HTMLButtonElement | null)"
          type="button"
          class="link-button"
          :aria-expanded="help === 'create'"
          aria-controls="api-key-help"
          data-test="help-create"
          @click="openHelp('create')"
        >
          How do I get an API key?
        </button>
        <button
          :ref="(el) => (helpTriggers.safety = el as HTMLButtonElement | null)"
          type="button"
          class="link-button"
          :aria-expanded="help === 'safety'"
          aria-controls="api-key-help"
          data-test="help-safety"
          @click="openHelp('safety')"
        >
          Is my API key safe here?
        </button>
      </span>
      <span class="connection-panel__field">
        <input
          id="gw2-api-key"
          v-model="draft"
          :type="showDraft ? 'text' : 'password'"
          autocomplete="off"
          spellcheck="false"
          :disabled="isWorking"
          data-test="api-key-input"
        />
        <button
          type="button"
          class="connection-panel__eye"
          :aria-label="showDraft ? 'Hide API key' : 'Show API key'"
          :aria-pressed="showDraft ? 'true' : 'false'"
          :disabled="isWorking"
          data-test="toggle-key-visibility"
          @click="showDraft = !showDraft"
        >
          <span aria-hidden="true">{{ showDraft ? '🙈' : '👁' }}</span>
        </button>
      </span>
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

    <div
      v-if="help"
      id="api-key-help"
      class="connection-panel__help"
      role="dialog"
      aria-modal="true"
      aria-labelledby="api-key-help-title"
      data-test="help-dialog"
      @keydown.esc.stop="closeHelp"
    >
      <h3 id="api-key-help-title">{{ help === 'create' ? 'How to get an API key' : 'Is my API key safe here?' }}</h3>
      <template v-if="help === 'create'">
        <p>
          Sign in to the ArenaNet account you use to play Guild Wars 2. If you normally sign in through Steam or
          Epic, choose that platform’s sign-in option.
        </p>
        <p>
          Open <strong>Applications → New Key</strong>, give the key a recognizable name, and select these
          permissions: <strong>account, inventories, characters, unlocks, progression, and builds</strong>.
        </p>
        <p>Copy the generated key, paste it here, and choose <strong>Connect</strong>.</p>
        <p>
          <a href="https://account.arena.net/applications" target="_blank" rel="noopener noreferrer"
            >Open your ArenaNet applications page</a
          >
        </p>
      </template>
      <template v-else>
        <p>
          An API key gives <strong>read-only access to the account and game data</strong> its permissions allow. It
          is not your game password and cannot log in, move or sell items, spend gold, or change your account.
        </p>
        <p>
          This app keeps the key in this tab’s browser storage so a reload can reconnect, and sends it to the
          app’s server only to check your account and fetch your data. The server does not save the key. It may
          keep game data it fetched, even after you disconnect.
        </p>
        <p>
          <strong>Disconnect</strong> forgets the key in this browser and ends your session, but does not delete
          that kept data. <strong>Replace key</strong> checks the new key and uses the data for its account.
        </p>
        <p>
          Revoking a key stops future access with it. It does not delete data already kept by this app or
          instantly stop work already under way.
          <a href="https://wiki.guildwars2.com/wiki/API:API_key" target="_blank" rel="noopener noreferrer"
            >About API keys</a
          >
          ·
          <a href="https://account.arena.net/applications" target="_blank" rel="noopener noreferrer"
            >Manage keys</a
          >
        </p>
      </template>
      <button ref="helpClose" type="button" data-test="help-close" @click="closeHelp">Close</button>
    </div>

    <p v-if="connection.error" class="connection-panel__error" role="alert" data-test="connection-error">
      {{ connection.error }}
    </p>
    <p v-if="connection.logoutNotice" class="connection-panel__notice" role="status" data-test="logout-notice">
      {{ connection.logoutNotice }}
    </p>
  </section>
</template>
