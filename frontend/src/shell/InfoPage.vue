<script setup lang="ts">
import { computed } from 'vue'
import PageHeader from './PageHeader.vue'
import { missingOperatorFacts, OPERATOR_FIELDS, readOperatorFacts } from './operatorFacts'
import type { DestinationId } from './destinations'

/**
 * The public information pages. They read nothing from the account and make no request. Text states
 * only behavior verified in this implementation; operator facts come from configuration and, while
 * unset, are shown as unresolved. This is a local preview, not legally ready content (STORY-LAUNCH-004).
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
      <strong>Preview — incomplete, not deployable.</strong>
      Operator details have not been supplied, so this page must not be treated as published legal information.
    </p>

    <template v-if="page === 'legal'">
      <h2>Operator details</h2>
      <p>Each detail below is a separate fact and is supplied by configuration.</p>
      <dl class="info-page__facts">
        <template v-for="field in OPERATOR_FIELDS" :key="field.key">
          <dt>{{ field.label }}</dt>
          <dd :data-test="`fact-${field.key}`">
            <template v-if="facts[field.key] !== null">{{ facts[field.key] }}</template>
            <em v-else>Unresolved draft field</em>
          </dd>
        </template>
      </dl>
      <h2>About this application</h2>
      <p>This is an unofficial tool for Guild Wars 2 crafting and economy information. It uses data from the public Guild Wars 2 API.</p>
    </template>

    <template v-else-if="page === 'privacy'">
      <h2>Current technical behavior</h2>
      <ul>
        <li>You can connect a read-only Guild Wars 2 API key. The application sends it to its own backend to read your account data from the Guild Wars 2 API.</li>
        <li>The backend keeps a cache of Guild Wars 2 data so it can be shown again; missing or inconsistent data is fetched again.</li>
        <li>You can delete the cached data of a connected account from System Status.</li>
        <li>Reading this page sends no account request and needs no API key.</li>
      </ul>
      <h2>Not yet stated</h2>
      <ul>
        <li>Legal bases, retention periods and jurisdiction are unresolved draft fields and are not stated here.</li>
        <li>Operator contact details are listed under Legal notice once supplied.</li>
      </ul>
    </template>

    <template v-else>
      <h2>Current technical behavior</h2>
      <ul>
        <li>A connected API key is kept in this browser's session storage so a reload can prove it again. Disconnecting removes it.</li>
        <li>The application does not show an advertising or tracking consent banner because it adds none.</li>
        <li>Navigation between pages is stored only in the address bar.</li>
      </ul>
      <h2>Not yet stated</h2>
      <ul>
        <li>Hosting-level cookies or logs of the deployment are an unresolved draft field.</li>
      </ul>
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
