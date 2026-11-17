<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { connection } from './api/accountSession'
import { useAccountBootstrap } from './account/useAccountBootstrap'
import BankScreen from './account/BankScreen.vue'
import CharactersScreen from './characters/CharactersScreen.vue'
import MaterialsScreen from './account/MaterialsScreen.vue'
import CraftingDiscoveryScreen from './crafting/CraftingDiscoveryScreen.vue'
import CraftingProfitScreen from './crafting/CraftingProfitScreen.vue'
import EctoSalvageScreen from './ecto/EctoSalvageScreen.vue'
import { DESTINATIONS, isInfoDestination, type DestinationId } from './shell/destinations'
import InfoPage from './shell/InfoPage.vue'
import HomeScreen from './home/HomeScreen.vue'
import SiteFooter from './shell/SiteFooter.vue'
import SiteHeader from './shell/SiteHeader.vue'
import PageHeader from './shell/PageHeader.vue'
import { useHashRoute } from './shell/useHashRoute'
import DesktopWidthNotice from './shell/DesktopWidthNotice.vue'
import { useDesktopTools } from './shell/useDesktopTools'
import { provideSyncOperations } from './sync/provideSyncOperations'
import SyncScreen from './sync/SyncScreen.vue'

/**
 * The application shell: one site header with the navigation, and the open application area below it
 * (`FRONTEND_UX_GUIDELINES.md` 2, 3).
 *
 * Below 1024 CSS pixels every tool destination shows a notice instead of its screen and starts no
 * account work. A screen already admitted on a supported width stays mounted (hidden, inert) when the window narrows, so pending work and inputs survive; widening shows it as it was.
 *
 * Each destination is a real URL (`useHashRoute`), so Back, Forward, a bookmark and a reload all work
 * normally and each screen has its own document title. Navigating issues no request of the shell's
 * own: no calculation and no synchronization operation can follow from opening a destination.
 *
 * This workspace is created per connected account (the app keys it by the connection generation), so
 * replacing or disconnecting the key discards every screen, selection, result and tracked task of the
 * previous account at once. Each account screen loads its own inputs after connecting.
 *
 * The synchronization tracking state is created here, not on the page that renders it, so an
 * unfinished task stays tracked while the user works elsewhere — with no duplicated trigger and no
 * second polling loop. Nothing is persisted: a reload starts with no tracked task.
 */
const route = useHashRoute()
const desktop = useDesktopTools()
const sync = provideSyncOperations()
const bootstrap = useAccountBootstrap()
const isConnected = computed(() => connection.phase === 'connected')
const isConnecting = computed(() => connection.phase === 'restoring' || connection.phase === 'connecting')
const onInfoPage = computed(() => isInfoDestination(route.current.value))
const onHome = computed(() => route.current.value.id === 'home')
const isTool = computed(() => !onHome.value && !onInfoPage.value)
/** Tool destinations are blocked below the supported width; the workspace itself stays mounted. */
const toolsBlocked = computed(() => isTool.value && !desktop.value)
const toolOpen = computed(() => isTool.value && desktop.value)
/**
 * The tool destination whose screen was admitted on a supported width. Narrowing the window keeps that
 * one instance mounted (hidden and inert) so its pending work and inputs survive a width-only change;
 * any navigation while narrow drops it, and a narrow viewport never admits a screen.
 */
const admittedId = ref<string | null>(null)
watch([() => route.current.value.id, desktop, isTool], ([id, wide, tool]) => {
  if (!tool) admittedId.value = null
  else if (wide) admittedId.value = id
  else if (admittedId.value !== id) admittedId.value = null
}, { immediate: true, flush: 'sync' })
const mountTool = computed(() => isTool.value && (desktop.value || admittedId.value === route.current.value.id))
const onSystemStatus = computed(() => route.current.value.id === 'synchronization')
const onCraftingPage = computed(() => ['crafting', 'discovery'].includes(route.current.value.id))
const craftingReady = computed(() => !['checking', 'syncing'].includes(bootstrap.phase.value))

// Only crafting needs these combined inputs on first use. Other pages recover their own sources.
// A blocked (narrow) viewport defers this first read; widening starts it once. Width changes alone
// never repeat a read that already happened.
let bootstrapDeferred = false
watch(() => isConnected.value && onCraftingPage.value, needed => {
  if (!needed) return
  if (desktop.value) void bootstrap.run()
  else bootstrapDeferred = true
}, { immediate: true })
watch(desktop, wide => {
  if (wide && bootstrapDeferred && isConnected.value && onCraftingPage.value) void bootstrap.run()
  if (wide) bootstrapDeferred = false
})

const main = ref<HTMLElement | null>(null)

/**
 * The compact activity indication next to the synchronization destination. It reports what this page
 * is tracking and nothing else — no progress, and no claim about work the backend may be doing that
 * this page is not following.
 */
const syncActivity = computed(() => {
  const tracked = sync.trackedTaskCount.value
  if (tracked === 0) return null
  return tracked === 1 ? '1 task running' : `${tracked} tasks running`
})

function onNavigate(id: DestinationId): void {
  route.navigate(id)
}

/**
 * A page change moves focus to the new page's heading. That is what tells assistive technology and
 * the keyboard which destination is now open — the `aria-current` link and the document title say so
 * statically, but neither is announced on its own. The initial page load is deliberately left alone:
 * focus belongs at the start of the document there.
 */
watch(
  () => route.current.value.id,
  async () => {
    await nextTick()
    main.value?.querySelector<HTMLElement>('[data-page-heading]')?.focus()
  }
)
</script>

<template>
  <a class="skip-link" href="#main-content" @click.prevent="main?.focus()">Skip to page content</a>

  <SiteHeader
    :destinations="DESTINATIONS"
    :current-id="route.current.value.id"
    :sync-activity="syncActivity"
    @navigate="onNavigate"
  />

  <main id="main-content" ref="main" class="page" tabindex="-1">
    <!--
      The API-key controls live only on System Status (STORY-FIX-004), which therefore renders whether
      or not an account is connected — a direct URL or reload while disconnected must still reach it.
      Every other destination needs the account and points to System Status instead of repeating the form.
    -->
    <DesktopWidthNotice v-if="toolsBlocked" :heading="route.current.value.label" />

    <div class="tool-stage" :hidden="toolsBlocked" :inert="toolsBlocked">
    <SyncScreen v-if="onSystemStatus && mountTool" />
    </div>

    <HomeScreen v-if="onHome" @navigate="onNavigate" />

    <InfoPage v-if="onInfoPage" :page="route.current.value.id" />

    <section v-if="toolOpen && !onSystemStatus && !isConnected" class="account-needed">
    <PageHeader :heading="route.current.value.label" />
    <p role="status" data-test="connection-needed">
      <template v-if="isConnecting">Checking your GW2 API key…</template>
      <template v-else>
        This page needs your Guild Wars 2 account.
        <a href="#/synchronization" data-test="connection-needed-link">Connect an API key in System Status</a>.
      </template>
    </p>
    </section>

    <template v-if="isConnected">
    <p v-if="toolOpen && onCraftingPage && bootstrap.phase.value === 'syncing'" class="account-bootstrap" role="status" data-test="bootstrap-syncing">
      Loading crafting data…
    </p>
    <p v-else-if="toolOpen && onCraftingPage && bootstrap.phase.value === 'failed'" class="account-bootstrap account-bootstrap--failed" role="alert" data-test="bootstrap-failed">
      {{ bootstrap.error.value }}
      <button type="button" @click="bootstrap.run()">Try again</button>
    </p>
    <!--
      Only the open screen is mounted. The two crafting screens are kept alive across navigation
      because their results belong to a scope and settings the user chose: re-mounting one would both
      discard that selection and post the calculation again, which navigation must never do. They are
      two independent `KeepAlive` boundaries, so each keeps its own scope, settings, search, sort and
      selection and neither is re-created by opening the other. Opening a public information page only
      deactivates the open screen (its route id no longer matches); the boundaries stay mounted so
      returning neither resets the selection nor posts a calculation. Bank and Materials have no such
      input, so each keeps reading its own route when opened (STORY-WEB-003).
    -->
    <div class="tool-stage" :hidden="toolsBlocked" :inert="toolsBlocked">
    <KeepAlive>
      <CraftingProfitScreen v-if="mountTool && route.current.value.id === 'crafting' && craftingReady" />
    </KeepAlive>

    <KeepAlive>
      <CraftingDiscoveryScreen v-if="mountTool && route.current.value.id === 'discovery' && craftingReady" />
    </KeepAlive>

    <!-- Characters keeps its selection and tab while the user visits another page of this account. -->
    <KeepAlive>
      <CharactersScreen v-if="mountTool && route.current.value.id === 'characters'" />
    </KeepAlive>

    <!--
      Ectoplasm Salvage is deliberately not kept alive: it has no scope, settings, search or
      selection to preserve, and its result is a snapshot of live Trading Post prices — so opening it
      again should calculate again rather than re-present an older snapshot as current.
    -->
    <EctoSalvageScreen v-if="mountTool && route.current.value.id === 'ecto'" />

    <BankScreen v-else-if="mountTool && route.current.value.id === 'bank'" />
    <MaterialsScreen v-else-if="mountTool && route.current.value.id === 'materials'" />
    </div>
    </template>
  </main>

  <SiteFooter :current-id="route.current.value.id" @navigate="onNavigate" />
</template>
