<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { connection, deleteAccountData, disconnect, retryConnection } from '@/api/accountSession'
import '@/materials/materialProtectionState'
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
const helpHeading = ref<HTMLHeadingElement | null>(null)
const storageDetails = ref<HTMLDetailsElement | null>(null)
let openedFrom: HelpKind | null = null

async function openHelp(kind: HelpKind): Promise<void> {
  if (help.value === null) openedFrom = kind
  help.value = kind
  await nextTick()
  if (storageDetails.value) storageDetails.value.open = false
  if (helpDialog.value && !helpDialog.value.open) helpDialog.value.showModal()
  helpHeading.value?.focus()
}

/** Closes the popup; the native `close` event returns focus to the trigger that opened it. */
function closeHelp(): void {
  helpDialog.value?.close()
}

function onHelpClosed(): void {
  const kind = openedFrom
  help.value = null
  openedFrom = null
  if (kind) helpTriggers[kind]?.focus()
}

/* Delete cached data: a small native confirmation modal; nothing is requested until Confirm. */
const confirmingDelete = ref(false)
const deleting = ref(false)
const deleteError = ref<string | null>(null)
const deleteTrigger = ref<HTMLButtonElement | null>(null)
const deleteDialog = ref<HTMLDialogElement | null>(null)
const deleteHeading = ref<HTMLHeadingElement | null>(null)
const deleteCancel = ref<HTMLButtonElement | null>(null)
const deleteConfirm = ref<HTMLButtonElement | null>(null)

async function openDelete(): Promise<void> {
  deleteError.value = null
  confirmingDelete.value = true
  await nextTick()
  if (deleteDialog.value && !deleteDialog.value.open) deleteDialog.value.showModal()
  deleteHeading.value?.focus()
}

function cancelDelete(): void {
  if (deleting.value) return
  deleteDialog.value?.close()
}

function onDeleteClosed(): void {
  confirmingDelete.value = false
  deleteError.value = null
  deleteTrigger.value?.focus()
}

/** Escape is the native `cancel` event: honoured only while idle. */
function onDeleteCancel(event: Event): void {
  if (deleting.value) event.preventDefault()
}

/** A click on the backdrop lands on the dialog element itself, never on its content. */
function onDeleteBackdrop(event: MouseEvent): void {
  if (event.target === deleteDialog.value) cancelDelete()
}

/** Keeps Tab inside the modal even where the platform does not trap it. */
function onDeleteKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Tab') return
  const order = [deleteCancel.value, deleteConfirm.value].filter(
    (el): el is HTMLButtonElement => el !== null && !el.disabled
  )
  if (order.length === 0) {
    event.preventDefault()
    return
  }
  const active = document.activeElement
  const index = order.findIndex((el) => el === active)
  if (event.shiftKey && (index <= 0)) {
    event.preventDefault()
    order[order.length - 1]?.focus()
  } else if (!event.shiftKey && (index === order.length - 1)) {
    event.preventDefault()
    order[0]?.focus()
  } else if (index === -1) {
    event.preventDefault()
    order[0]?.focus()
  }
}

async function confirmDelete(): Promise<void> {
  if (deleting.value) return
  deleting.value = true
  deleteError.value = null
  try {
    const result = await deleteAccountData()
    if (result === 'deleted' || result === 'already-deleted' || result === 'stale') {
      deleteDialog.value?.close()
      await nextTick()
      document.getElementById('gw2-api-key')?.focus()
    } else if (result === 'work-in-progress') {
      deleteError.value = 'Account work is still finishing, so nothing was deleted. Confirm again when it has finished.'
    } else {
      deleteError.value = 'The deletion could not be confirmed. Please try again.'
    }
  } finally {
    deleting.value = false
  }
}

onBeforeUnmount(() => {
  if (!isAttempting()) clearDraft()
})

const isConnected = computed(() => connection.phase === 'connected')
const isWorking = computed(() => busy.value || connection.phase === 'restoring' || connection.phase === 'connecting')

/** Routine connected/not-connected states are not narrated; only working and outage states are. */
const statusText = computed(() => {
  switch (connection.phase) {
    case 'restoring':
      return 'Reconnecting with the key kept in this browser session…'
    case 'connecting':
      return 'Checking the key…'
    case 'unavailable':
      return 'The saved key could not be checked right now.'
    default:
      return ''
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
  <section class="connection-panel card" aria-labelledby="connection-heading" data-test="connection-panel">
    <h2 id="connection-heading" class="connection-panel__title">GW2 API key</h2>
    <p class="connection-panel__help-links">
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
    </p>
    <p v-if="statusText" class="connection-panel__status" role="status" data-test="connection-status">
      {{ statusText }}
    </p>

    <form class="connection-panel__form" @submit.prevent="submit">
      <label for="gw2-api-key">{{ isConnected ? 'Replace with another key' : 'API key' }}</label>
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
      <span class="connection-panel__actions" data-test="key-actions">
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
        <button
          v-if="isConnected && connection.accountId !== null"
          ref="deleteTrigger"
          type="button"
          class="connection-panel__delete-trigger"
          aria-haspopup="dialog"
          data-test="delete-cached-data"
          @click="openDelete"
        >
          Delete cached data
        </button>
      </span>
    </form>

    <dialog
      v-if="confirmingDelete"
      ref="deleteDialog"
      class="connection-panel__help connection-panel__delete"
      aria-labelledby="delete-cached-title"
      aria-describedby="delete-cached-body"
      data-test="delete-dialog"
      @close="onDeleteClosed"
      @cancel="onDeleteCancel"
      @click="onDeleteBackdrop"
      @keydown="onDeleteKeydown"
    >
      <div class="connection-panel__delete-content">
        <h3 id="delete-cached-title" ref="deleteHeading" tabindex="-1">Delete cached account data?</h3>
        <p id="delete-cached-body" class="connection-panel__delete-copy">
          This removes this app’s cached data and saved selections for the connected GW2 account. It does not
          affect your Guild Wars 2 account. Data can be downloaded again after reconnecting.
        </p>
        <p v-if="deleteError" class="connection-panel__error" role="alert" data-test="delete-error">{{ deleteError }}</p>
        <footer class="help-footer help-footer--safety">
          <button ref="deleteCancel" type="button" :disabled="deleting" data-test="delete-cancel" @click="cancelDelete">
            Cancel
          </button>
          <button
            ref="deleteConfirm"
            type="button"
            class="connection-panel__delete-confirm"
            :disabled="deleting"
            :aria-busy="deleting ? 'true' : 'false'"
            data-test="delete-confirm"
            @click="confirmDelete"
          >
            Delete cached data
          </button>
        </footer>
      </div>
    </dialog>

    <dialog
      v-if="help"
      id="api-key-help"
      ref="helpDialog"
      class="connection-panel__help"
      :class="{ 'connection-panel__help--safety': help === 'safety' }"
      aria-labelledby="api-key-help-title"
      data-test="help-dialog"
      @close="onHelpClosed"
    >
      <h3 id="api-key-help-title" ref="helpHeading" tabindex="-1">{{ help === 'create' ? 'Get your API key' : 'Your key is read-only' }}</h3>
      <template v-if="help === 'create'">
        <ol class="help-steps">
          <li>
            Go to <a href="https://account.arena.net/applications" target="_blank" rel="noopener noreferrer">your ArenaNet account</a> and sign in.
            <small class="help-note">Playing through Steam or Epic? Use that sign-in option.</small>
          </li>
          <li>
            <strong>Create a new key</strong> with <strong>all permissions</strong>.
          </li>
          <li><strong>Copy &amp; Paste</strong> your key into the field here.</li>
          <li>Click <strong>Connect</strong>.</li>
        </ol>
        <footer class="help-footer">
          <button type="button" class="link-button" data-test="help-create-safety" @click="openHelp('safety')">Is my API key safe here?</button>
          <button type="button" data-test="help-close" @click="closeHelp">Close</button>
        </footer>
      </template>
      <template v-else>
        <ul class="help-facts" data-test="safety-facts">
          <li><strong>Cannot change your GW2 account:</strong> your characters, items and gold stay untouched.</li>
          <li><strong>ArenaNet never shares</strong> your email, password or payment details through this API key.</li>
          <li><strong>You control access:</strong> choose permissions for each key and revoke it at any time in your ArenaNet account.</li>
          <li><strong>No separate sign-up:</strong> no email address or password needed here.</li>
          <li><strong>Disconnect</strong> removes the key from this browser session. <strong>Delete cached data</strong> removes your saved account data from our database.</li>
        </ul>
        <details ref="storageDetails" class="help-details" data-test="safety-details">
          <summary>What this app stores</summary>
          <dl class="help-storage">
            <div>
              <dt>Your API key</dt>
              <dd>
                <p>Saved only in this browser tab. Reloading keeps it; a fresh tab normally starts without it.</p>
                <p>Duplicated or restored tabs can keep the key. Use <strong>Disconnect</strong> to remove it from this tab.</p>
                <p>Our server receives it to read your GW2 data, but never saves it.</p>
              </dd>
            </div>
            <div>
              <dt>How we find your account</dt>
              <dd>
                <p>ArenaNet checks your key and tells us your <strong>GW2 account ID</strong>. We use that ID to find your saved game data.</p>
                <p>Another valid key for the same account retrieves the same data, within that key's permissions.</p>
                <p>A revoked key cannot reconnect. An existing session may keep access for up to 15 minutes.</p>
              </dd>
            </div>
            <div>
              <dt>Game data we cache</dt>
              <dd>
                Stored under your <strong>GW2 account ID</strong>, never your API key:
                <ul>
                  <li>Bank items, material quantities and unlocked recipes</li>
                  <li>Character names and profiles, crafting levels, playtime and deaths</li>
                  <li>Inventories, equipment and saved builds</li>
                  <li>Account Luck</li>
                </ul>
              </dd>
            </div>
            <div>
              <dt>Why we cache it</dt>
              <dd>
                GW2 limits API requests, and crafting calculations are demanding. Caching avoids repeated
                downloads and keeps calculations responsive.
              </dd>
            </div>
          </dl>
        </details>
        <footer class="help-footer help-footer--safety">
          <span class="help-links">
            <a href="https://account.arena.net/applications" target="_blank" rel="noopener noreferrer">Manage your key at ArenaNet</a>
            <a href="https://help.guildwars2.com/hc/en-us/articles/230899908-Managing-Your-ArenaNet-Account" target="_blank" rel="noopener noreferrer">What ArenaNet says about API keys</a>
          </span>
          <button type="button" data-test="help-close" @click="closeHelp">Close</button>
        </footer>
      </template>
    </dialog>

    <p v-if="connection.error" class="connection-panel__error" role="alert" data-test="connection-error">
      {{ connection.error }}
    </p>
    <p v-if="connection.deletionNotice" class="connection-panel__notice" role="status" data-test="deletion-notice">
      {{ connection.deletionNotice }}
    </p>
    <p v-if="connection.logoutNotice" class="connection-panel__notice" role="status" data-test="logout-notice">
      {{ connection.logoutNotice }}
    </p>
  </section>
</template>
