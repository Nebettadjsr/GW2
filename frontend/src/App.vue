<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import BankScreen from './account/BankScreen.vue'
import MaterialsScreen from './account/MaterialsScreen.vue'
import CraftingProfitScreen from './crafting/CraftingProfitScreen.vue'
import { DESTINATIONS, type DestinationId } from './shell/destinations'
import SiteHeader from './shell/SiteHeader.vue'
import { useHashRoute } from './shell/useHashRoute'
import { provideSyncOperations } from './sync/provideSyncOperations'
import SyncScreen from './sync/SyncScreen.vue'

/**
 * The application shell: one site header with the navigation, and the open application area below it
 * (`FRONTEND_UX_GUIDELINES.md` 2, 3).
 *
 * Each destination is a real URL (`useHashRoute`), so Back, Forward, a bookmark and a reload all work
 * normally and each screen has its own document title. Navigating issues no request of the shell's
 * own: no calculation and no synchronization operation can follow from opening a destination.
 *
 * The synchronization tracking state is created here, not on the page that renders it, so an
 * unfinished task stays tracked while the user works elsewhere — with no duplicated trigger and no
 * second polling loop. Nothing is persisted: a reload starts with no tracked task.
 */
const route = useHashRoute()
const sync = provideSyncOperations()

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
  <a class="skip-link" href="#main-content">Skip to page content</a>

  <SiteHeader
    :destinations="DESTINATIONS"
    :current-id="route.current.value.id"
    :sync-activity="syncActivity"
    @navigate="onNavigate"
  />

  <main id="main-content" ref="main" class="page">
    <!--
      Only the open screen is mounted. Crafting Profit is kept alive across navigation because its
      results belong to a scope and settings the user chose: re-mounting it would both discard that
      selection and post the calculation again, which navigation must never do. Bank and Materials
      have no such input, so each keeps reading its own route when opened (STORY-WEB-003).
    -->
    <KeepAlive>
      <CraftingProfitScreen v-if="route.current.value.id === 'crafting'" />
    </KeepAlive>

    <SyncScreen v-if="route.current.value.id === 'synchronization'" />
    <BankScreen v-else-if="route.current.value.id === 'bank'" />
    <MaterialsScreen v-else-if="route.current.value.id === 'materials'" />
  </main>
</template>
