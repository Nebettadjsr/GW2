<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { connection, disconnect, retryConnection } from '@/api/accountSession'
import { clearDraft, draft, isAttempting, showDraft, submitDraft } from './keyDraft'

/**
 * The API-key area (`TARGET_ARCHITECTURE.md` 8.2): a masked field with Connect, Replace and
 * Disconnect, and the connection state in words. The key is handed to the session module and the
 * field is cleared at once; nothing here stores, logs or displays it.
 */
const busy = ref(false)
type HelpKind = 'create' | 'safety'
const help = ref<HelpKind | null>(null)
const helpTriggers: Record<HelpKind, HTMLButtonElement | null> = { create: null, safety: null }
const helpDialog = ref<HTMLDialogElement | null>(null)

async function openHelp(kind: HelpKind): Promise<void> {
  help.value = kind
  await nextTick()
  helpDialog.value?.showModal()
}

/** Closes the popup; the native `close` event returns focus to the trigger that opened it. */
function closeHelp(): void {
  helpDialog.value?.close()
}

function onHelpClosed(): void {
  const kind = help.value
  help.value = null
  if (kind) helpTriggers[kind]?.focus()
}

onBeforeUnmount(() => {
  if (!isAttempting()) clearDraft()
})

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
  try {
    await submitDraft()
  } finally {
    busy.value = false
  }
}

async function onDisconnect(): Promise<void> {
  clearDraft()
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
          aria-haspopup="dialog"
          data-test="help-create"
          @click="openHelp('create')"
        >
          How do I get an API key?
        </button>
        <button
          :ref="(el) => (helpTriggers.safety = el as HTMLButtonElement | null)"
          type="button"
          class="link-button"
          aria-haspopup="dialog"
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
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12z" />
            <circle cx="12" cy="12" r="3" />
            <path v-if="showDraft" d="M3 3l18 18" />
          </svg>
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

    <dialog
      v-if="help"
      id="api-key-help"
      ref="helpDialog"
      class="connection-panel__help"
      aria-labelledby="api-key-help-title"
      data-test="help-dialog"
      @close="onHelpClosed"
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
          An API key gives <strong>read-only access to the game data</strong> you select. It is not your
          password and cannot sign in, trade, spend gold or change your account.
        </p>
        <p>
          This tab remembers the key so a reload can reconnect. The server uses it only to check your account
          and fetch your data, and does not save it, but may keep fetched game data.
          <strong>Disconnect</strong> forgets the key here without deleting that data;
          <strong>Replace key</strong> checks the new key.
        </p>
        <p>
          Revoking a key stops future access, but does not delete kept data or instantly stop work under way.
          <a href="https://wiki.guildwars2.com/wiki/API:API_key" target="_blank" rel="noopener noreferrer"
            >About API keys</a
          >
          ·
          <a href="https://account.arena.net/applications" target="_blank" rel="noopener noreferrer"
            >Manage keys</a
          >
        </p>
      </template>
      <button type="button" data-test="help-close" @click="closeHelp">Close</button>
    </dialog>

    <p v-if="connection.error" class="connection-panel__error" role="alert" data-test="connection-error">
      {{ connection.error }}
    </p>
    <p v-if="connection.logoutNotice" class="connection-panel__notice" role="status" data-test="logout-notice">
      {{ connection.logoutNotice }}
    </p>
  </section>
</template>
