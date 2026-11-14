<script setup lang="ts">
import { computed } from 'vue'
import PageHeader from './PageHeader.vue'
import { missingOperatorFacts, OPERATOR_FIELDS, readOperatorFacts } from './operatorFacts'
import type { DestinationId } from './destinations'

/**
 * The public information pages. They read nothing from the account and make no request. Text states
 * only behavior verified in this implementation; operator facts come from configuration and, while
 * unset, are shown as "To be completed". This is a local preview, not legally ready content (STORY-LAUNCH-004).
 */
const props = defineProps<{ page: DestinationId }>()

const facts = readOperatorFacts()
const missing = computed(() => missingOperatorFacts(facts))
const heading = computed(() => ({
  legal: 'Legal notice',
  privacy: 'Privacy',
  storage: 'Cookies & browser storage'
}[props.page as 'legal' | 'privacy' | 'storage']))
</script>

<template>
  <section class="info-page" :data-test="`info-${page}`">
    <PageHeader :heading="heading" />

    <p v-if="missing.length > 0" class="info-page__preview" role="note" data-test="preview-incomplete">
      <strong>Local preview — not ready for publication.</strong>
      Some operator details are still to be completed.
    </p>

    <template v-if="page === 'legal'">
      <h2>Operator</h2>
      <dl class="info-page__facts">
        <template v-for="field in OPERATOR_FIELDS" :key="field.key">
          <dt>{{ field.label }}</dt>
          <dd :data-test="`fact-${field.key}`">
            <template v-if="facts[field.key] !== null">{{ facts[field.key] }}</template>
            <em v-else>To be completed</em>
          </dd>
        </template>
      </dl>
      <h2>About this application</h2>
      <p>An unofficial tool for Guild Wars 2 crafting and economy information, using data from the public Guild Wars 2 API.</p>
    </template>

    <template v-else-if="page === 'privacy'">
      <h2>Your API key</h2>
      <ul>
        <li>You can connect a read-only Guild Wars 2 API key. It is sent to this application's backend to read your account from the Guild Wars 2 API.</li>
        <li>The key itself is not saved in the database.</li>
      </ul>
      <h2>Saved game data</h2>
      <ul>
        <li>The backend caches account, character, inventory and recipe data, plus other Guild Wars 2 data, so it can be shown again.</li>
        <li>This data is linked to your account, so it should not be treated as anonymous.</li>
      </ul>
      <h2>Disconnect and delete</h2>
      <ul>
        <li><strong>Disconnect</strong> removes the key from this browser tab. Saved data stays.</li>
        <li><strong>Delete cached data</strong> removes the saved account data from the database. Both are in System Status.</li>
      </ul>
      <h2>Reading this page</h2>
      <p>Opening this page sends no account request and needs no API key.</p>
      <h2>Still to be completed</h2>
      <p>Legal bases, retention periods and hosting details are not stated yet.</p>
    </template>

    <template v-else>
      <h2>Session cookie</h2>
      <ul>
        <li>Connecting sets a <code>GW2_SESSION</code> cookie. It is HttpOnly, SameSite=Strict and not readable by page scripts.</li>
        <li>The server session behind it lasts 15 minutes after the key is checked.</li>
      </ul>
      <h2>Session storage</h2>
      <ul>
        <li>Your API key is kept in this tab's session storage so a reload does not ask for it again.</li>
        <li>A new tab normally starts without it; a duplicated or restored tab may keep it.</li>
        <li>Disconnect removes it from the current tab.</li>
      </ul>
      <h2>Local storage</h2>
      <ul>
        <li>Saved Material Protection choices (protected items and the Profit preference) are stored per account.</li>
        <li>A short-lived entry is written and removed when cached data is deleted, so other tabs of this browser can react.</li>
      </ul>
      <h2>Still to be completed</h2>
      <p>Cookies or logs added by the hosting environment are not described yet.</p>
    </template>
  </section>
</template>

<style scoped>
.info-page { display: flex; flex-direction: column; gap: var(--space-4); max-width: var(--prose-max); }
.info-page ul { margin: 0; padding-left: var(--space-5); display: flex; flex-direction: column; gap: var(--space-2); }
.info-page p { margin: 0; }
.info-page__preview { border: 1px solid var(--color-warning); border-radius: var(--radius-panel); padding: var(--space-3) var(--space-4); background: var(--color-surface); }
.info-page__facts { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--space-1) var(--space-4); margin: 0; }
.info-page__facts dt { font-weight: 600; margin-top: var(--space-2); }
.info-page__facts dd { margin: 0; color: var(--color-muted); overflow-wrap: anywhere; }
@media (min-width: 40rem) { .info-page__facts { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } .info-page__facts dt { margin-top: 0; } }
</style>
