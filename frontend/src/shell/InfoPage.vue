<script setup lang="ts">
import { computed } from 'vue'
import PageHeader from './PageHeader.vue'
import { missingOperatorFacts, OPERATOR_FIELDS, readOperatorFacts } from './operatorFacts'
import type { DestinationId } from './destinations'

/**
 * Public information without account access or API requests. Operator details are build inputs;
 * missing details keep the preview explicitly incomplete.
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
      <section class="info-page__section" aria-labelledby="legal-operator">
        <h2 id="legal-operator">Operator &amp; contact · Impressum</h2>
        <dl class="info-page__facts">
          <template v-for="field in OPERATOR_FIELDS" :key="field.key">
            <dt>{{ field.label }}</dt>
            <dd :data-test="`fact-${field.key}`">
              <em v-if="facts[field.key] === null">To be completed</em>
              <a v-else-if="field.key === 'contactEmail'" :href="`mailto:${facts.contactEmail}`">{{ facts.contactEmail }}</a>
              <a v-else-if="field.key === 'publicDomain'" :href="`https://${facts.publicDomain}`">{{ facts.publicDomain }}</a>
              <template v-else>{{ facts[field.key] }}</template>
            </dd>
          </template>
        </dl>
        <p>This website is operated by a private individual in Germany.</p>
      </section>

      <section class="info-page__section" aria-labelledby="legal-independent">
        <h2 id="legal-independent">An independent fan project</h2>
        <p>
          GW2 Crafting Tool is an <strong>unofficial fan project</strong> for Guild Wars 2.
          It is not affiliated with, sponsored, endorsed or approved by ArenaNet or NCSOFT.
        </p>
        <p>
          Game data and images come from the Guild Wars 2 API. Guild Wars 2 and the associated
          artwork, names and trademarks belong to their respective owners. Our software licence
          does not grant rights to that content. See
          <a href="https://www.arena.net/en/legal/content-terms-of-use" target="_blank" rel="noopener noreferrer">ArenaNet's Content Terms of Use</a>.
        </p>
      </section>

      <section class="info-page__section" aria-labelledby="legal-source">
        <h2 id="legal-source">Open source · inspect, reuse, improve</h2>
        <p>
          You can inspect how this application works in our
          <a href="https://github.com/Nebettadjsr/GW2" target="_blank" rel="noopener noreferrer">public GitHub repository</a>.
          Our original application code uses the
          <a href="https://github.com/Nebettadjsr/GW2/blob/master/LICENSE" target="_blank" rel="noopener noreferrer">MIT licence</a>:
          you may use, copy, change and share it, including commercially, while keeping the required
          copyright and licence notice. Third-party code and game assets retain their own terms.
        </p>
        <p>
          Found a bug or have an improvement in mind? Open a
          <a href="https://github.com/Nebettadjsr/GW2/issues" target="_blank" rel="noopener noreferrer">GitHub issue</a>
          <template v-if="facts.contactEmail !== null"> or email <a :href="`mailto:${facts.contactEmail}`">{{ facts.contactEmail }}</a></template>.
          GitHub issues are public; please keep API keys and private account information out of them.
        </p>
      </section>
    </template>

    <template v-else-if="page === 'privacy'">
      <p class="info-page__updated">Last updated: 15 November 2026</p>
      <section class="info-page__section" aria-labelledby="privacy-summary">
        <h2 id="privacy-summary">The short version</h2>
        <ul>
          <li><strong>No separate sign-up.</strong> We do not ask for your real name, email address or a password to use the tool.</li>
          <li><strong>Your key is kept in your browser tab.</strong> The server uses it temporarily, but does not save it in the database or application logs.</li>
          <li><strong>Game data is account-linked.</strong> An account ID and character names are not a guarantee of anonymity; we treat this information as personal data.</li>
        </ul>
      </section>

      <section class="info-page__section" aria-labelledby="privacy-controller">
        <h2 id="privacy-controller">Who is responsible?</h2>
        <p>
          The controller is {{ facts.operatorName ?? 'the operator (details to be completed)' }},
          {{ facts.operatorAddress ?? 'address to be completed' }}, {{ facts.operatorCountry ?? 'country to be completed' }}.
          <template v-if="facts.contactEmail !== null">Contact: <a :href="`mailto:${facts.contactEmail}`">{{ facts.contactEmail }}</a>.</template>
          The same details appear in the <a href="#/legal">Legal notice</a>.
        </p>
      </section>

      <section class="info-page__section" aria-labelledby="privacy-data">
        <h2 id="privacy-data">What is processed?</h2>
        <ul>
          <li><strong>When you visit:</strong> your IP address and HTTP connection information are needed to deliver the site. Opening these information pages makes no account-data request and needs no API key.</li>
          <li><strong>When you connect:</strong> our backend sends your read-only key to ArenaNet to verify the account and fetch the game data its permissions allow. ArenaNet does not provide your email, password or payment details through this key.</li>
          <li><strong>In our database:</strong> your verified account ID; bank and material items; unlocked recipes; character names, profiles and crafting disciplines; inventory, equipment and builds; account luck; and sync/activity timestamps.</li>
          <li><strong>In your browser:</strong> the key, a session cookie, and account-specific saved material exclusions. The <a href="#/storage">browser storage page</a> explains each one.</li>
          <li><strong>If you email us:</strong> your address, message and any details you choose to include are processed to answer your request.</li>
        </ul>
        <p>
          Game data comes from ArenaNet and is fetched when you use the relevant features.
          Connecting is optional; account features need a valid key. Public item, recipe and price
          data are shared game information, separate from your account cache.
        </p>
      </section>

      <section class="info-page__section" aria-labelledby="privacy-purpose">
        <h2 id="privacy-purpose">Why we process it</h2>
        <p>
          We rely on <strong>legitimate interests under Article 6(1)(f) GDPR</strong> to provide the
          account features you request, verify access to your data, and operate a secure, reliable
          free tool. Caching avoids repeated ArenaNet requests and expensive recalculations.
        </p>
        <p>
          The same basis covers troubleshooting, limited service-performance monitoring and replying
          to enquiries. These interests do not include advertising or selling your account data.
          We do not use advertising trackers, third-party analytics or automated decisions with legal
          or similarly significant effects on you.
        </p>
      </section>

      <section class="info-page__section" aria-labelledby="privacy-recipients">
        <h2 id="privacy-recipients">Hosting &amp; other services</h2>
        <ul>
          <li>
            <strong>Oracle Cloud Infrastructure</strong> hosts the application and database in
            {{ facts.hostingRegion ?? 'the hosting region (to be completed)' }}.
            Oracle also processes infrastructure and service-operation data; its
            <a href="https://www.oracle.com/legal/privacy/services-privacy-policy/" target="_blank" rel="noopener noreferrer">Services Privacy Policy</a>
            describes this processing and international transfers.
          </li>
          <li>
            <strong>ArenaNet</strong> receives API requests and your key from our server when account
            data is needed. ArenaNet is a US provider; see
            <a href="https://www.arena.net/en/legal/privacy-notice" target="_blank" rel="noopener noreferrer">ArenaNet's Privacy Notice</a>.
          </li>
          <li>
            <strong>Email:</strong> messages to our contact address are forwarded through
            <a href="https://www.hostworld.de/datenschutz" target="_blank" rel="noopener noreferrer">Hostworld</a>
            and <a href="https://www.cloudns.net/privacy-policy/" target="_blank" rel="noopener noreferrer">ClouDNS</a>
            to a <a href="https://tuta.com/privacy-policy" target="_blank" rel="noopener noreferrer">Tuta</a> mailbox.
            Those providers process the message and delivery information.
          </li>
        </ul>
        <p>
          A German server location does not mean every provider operation takes place in Germany.
          Oracle's policy describes EU standard contractual clauses and other applicable safeguards
          for transfers outside the EEA. ArenaNet describes its own transfer safeguards in its notice.
        </p>
        <p>
          Our browser client loads application assets and cached game icons from this site.
          Links to ArenaNet, the wiki or GitHub open those services only when you follow them;
          their own privacy notices then apply. GitHub issues are public.
        </p>
      </section>

      <section class="info-page__section" aria-labelledby="privacy-deletion">
        <h2 id="privacy-deletion">Storage, Disconnect &amp; deletion</h2>
        <ul>
          <li><strong>Disconnect</strong> removes the API key from the current browser tab and ends its server session. It does not erase the database cache.</li>
          <li><strong>Delete cached data</strong>, in <a href="#/synchronization">System Status</a>, deletes this account's saved database data, ends access through the app's sessions and clears its saved preferences in this browser.</li>
          <li><strong>Account caches have no automatic inactivity expiry.</strong> They remain until you delete them or the disposable cache is cleared. A refresh timestamp is not a deletion deadline. Using the app again can fetch the data again.</li>
          <li><strong>Server access expires after 15 minutes</strong> from the key check. A retained valid key can reconnect. Revoking a key at ArenaNet prevents reconnection; an already authorised session may remain valid until it expires.</li>
          <li><strong>Contact messages</strong> are kept while needed to handle the enquiry and necessary follow-up, or for any applicable legal retention obligation.</li>
        </ul>
        <p>
          Deleting cached data does not revoke the key at ArenaNet, change your GW2 account, remove
          shared game data, or erase provider records and the aggregate operational history below.
          Browser preferences on other devices must be cleared there.
        </p>
      </section>

      <section class="info-page__section" aria-labelledby="privacy-operations">
        <h2 id="privacy-operations">Limited operational monitoring</h2>
        <p>
          The admin view shows request counts, errors, response times and an estimate of accounts
          active in the last five minutes. Account IDs are used in server memory to count distinct
          accounts. The saved minute summaries contain aggregate counts and timings, without account
          IDs, character names, IP addresses or API keys. No extra heartbeat requests are sent.
        </p>
        <details>
          <summary>Log contents &amp; retention limits</summary>
          <ul>
            <li><strong>Activity history:</strong> at most 30 days that contain activity, with a 2 MiB limit per day and 32 MiB overall. Older day files are removed as limits are reached. Idle days create no records and do not expire existing files.</li>
            <li><strong>Website access logs:</strong> time, method, path without query parameters, status, response size and duration. Our configured access log excludes IP addresses, keys, cookies, headers and request bodies.</li>
            <li><strong>Rotation:</strong> website access logs keep the current file and up to three rotated files, checked daily with a 10 MiB threshold. Each container keeps up to three log files of 10 MiB. These are rotation limits, not fixed deletion periods.</li>
            <li><strong>Host &amp; provider records:</strong> certificate maintenance, security and cloud-service operations can generate separate records under their own lifecycle. The website log settings do not control Oracle's records.</li>
          </ul>
        </details>
      </section>

      <section class="info-page__section" aria-labelledby="privacy-rights">
        <h2 id="privacy-rights">Your rights &amp; contact</h2>
        <p>
          Subject to the GDPR's conditions, you can request access, correction, deletion or restriction
          of your personal data, and data portability where it applies.
          <strong>You can object to processing based on legitimate interests</strong> for reasons
          relating to your particular situation.
        </p>
        <p>
          <template v-if="facts.contactEmail !== null">Write to <a :href="`mailto:${facts.contactEmail}`">{{ facts.contactEmail }}</a>.</template>
          We may need to verify that a request concerns your account. Please do not email your API key.
          You can also complain to a supervisory authority, including the
          <a href="https://www.datenschutz.sachsen.de/" target="_blank" rel="noopener noreferrer">Saxon Data Protection and Transparency Commissioner</a>.
        </p>
      </section>
    </template>

    <template v-else>
      <p class="info-page__updated">Last updated: 15 November 2026</p>
      <p>
        This site uses browser storage for the connection and settings you request.
        There are <strong>no advertising cookies or third-party analytics trackers</strong>.
        The current hosting proxy adds no separate visitor cookie.
      </p>

      <section class="info-page__section" aria-labelledby="storage-session">
        <h2 id="storage-session">Session cookie · your server connection</h2>
        <ul>
          <li><strong>What:</strong> <code>GW2_SESSION</code>, an opaque session identifier. Your API key is not inside it.</li>
          <li><strong>Why:</strong> it lets the backend recognise your authorised connection. On HTTPS it is Secure, HttpOnly and SameSite=Strict; application JavaScript cannot read it.</li>
          <li><strong>How long:</strong> it is a browser-session cookie without a fixed cookie expiry. The server access behind it expires 15 minutes after a key check, even if the browser retains the cookie. Disconnect ends the session and requests cookie removal.</li>
        </ul>
      </section>

      <section class="info-page__section" aria-labelledby="storage-key">
        <h2 id="storage-key">Tab storage · your API key</h2>
        <ul>
          <li><strong>What:</strong> <code>gw2.apiKey</code> in this tab's session storage. The backend receives the key temporarily to verify access and fetch game data; it does not persist it.</li>
          <li><strong>Why:</strong> navigation and reloads can reconnect without asking you to paste the key again.</li>
          <li><strong>How long:</strong> normally until the tab's session ends. A fresh tab normally has no key; duplicated or restored tabs may retain it. Disconnect removes it from this tab. Clearing cached images or files alone does not reliably remove it.</li>
        </ul>
      </section>

      <section class="info-page__section" aria-labelledby="storage-preferences">
        <h2 id="storage-preferences">Local storage · your saved selections</h2>
        <ul>
          <li><strong>What:</strong> <code>gw2.materialProtection.v1.&lt;account ID&gt;</code> stores saved Bank/Materials exclusions and their Crafting Profit setting, separately for each account.</li>
          <li><strong>Why:</strong> to remember the selections and settings you save in this browser.</li>
          <li><strong>How long:</strong> until changed or cleared; there is no automatic expiry. Disconnect keeps them. Delete cached data clears this account's saved selections in this browser. Other browsers and devices keep their own copies.</li>
        </ul>
      </section>

      <section class="info-page__section" aria-labelledby="storage-signal">
        <h2 id="storage-signal">A deletion message between tabs</h2>
        <p>
          When you delete account data, a browser message tells other tabs of this site to stop using
          it. If BroadcastChannel is unavailable, the fallback writes
          <code>gw2.accountDataDeleted.signal</code> to local storage and immediately removes it.
          It contains the account ID and a message timestamp, never your API key.
        </p>
      </section>

      <section class="info-page__section" aria-labelledby="storage-controls">
        <h2 id="storage-controls">Your controls</h2>
        <p>
          The connection and saved-setting functions use storage needed to provide those requested
          functions under <a href="https://www.gesetze-im-internet.de/ttdsg/__25.html" target="_blank" rel="noopener noreferrer">Section 25(2)(2) TDDDG</a>.
          We do not add a consent banner for advertising or analytics that the site does not use.
        </p>
        <p>
          You can clear this site's cookies and storage through your browser settings. Storage may
          also be unavailable in a restricted browser, in which case settings may last only for the
          open page. Ordinary browser caching of application files and public icons follows your
          browser's cache controls. For server data, logs and your rights, see <a href="#/privacy">Privacy</a>.
        </p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.info-page { display: flex; flex-direction: column; gap: var(--space-6); max-width: var(--prose-max); line-height: 1.65; overflow-wrap: anywhere; }
.info-page__section { display: flex; flex-direction: column; gap: var(--space-3); }
.info-page h2 { margin: 0; line-height: 1.3; }
.info-page ul { margin: 0; padding-left: var(--space-5); display: flex; flex-direction: column; gap: var(--space-2); }
.info-page p { margin: 0; }
.info-page__updated { color: var(--color-muted); font-size: var(--text-sm); }
.info-page details { border-top: 1px solid var(--color-border); padding-top: var(--space-3); }
.info-page summary { cursor: pointer; font-weight: 600; }
.info-page details[open] summary { margin-bottom: var(--space-3); }
.info-page__preview { border: 1px solid var(--color-warning); border-radius: var(--radius-panel); padding: var(--space-3) var(--space-4); background: var(--color-surface); }
.info-page__facts { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--space-1) var(--space-4); margin: 0; }
.info-page__facts dt { font-weight: 600; margin-top: var(--space-2); }
.info-page__facts dd { margin: 0; color: var(--color-muted); overflow-wrap: anywhere; }
@media (min-width: 40rem) { .info-page__facts { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } .info-page__facts dt { margin-top: 0; } }
</style>
