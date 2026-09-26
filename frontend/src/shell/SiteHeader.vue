<script setup lang="ts">
import type { Destination, DestinationId } from './destinations'

/**
 * The site header: the identity of the application and its navigation
 * (`FRONTEND_UX_GUIDELINES.md` 2).
 *
 * Destinations are real links carrying the `href` they navigate to, so a modified click, a bookmark
 * and the browser's Back/Forward all behave normally; an ordinary left click is handled in the page
 * instead of reloading it. A link never submits a calculation or a synchronization operation.
 *
 * `syncActivity` is the compact activity indication section 2 allows next to the synchronization
 * destination. It only reports how many tasks this client is tracking; it is part of the link and
 * never a control of its own.
 */
const props = defineProps<{
  destinations: readonly Destination[]
  currentId: DestinationId
  syncActivity: string | null
}>()

const emit = defineEmits<{ navigate: [id: DestinationId] }>()

/** A plain left click is handled here; anything the browser should own is left to the browser. */
function onLinkClick(event: MouseEvent, id: DestinationId): void {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  emit('navigate', id)
}

function activityOf(destination: Destination): string | null {
  return destination.id === 'synchronization' ? props.syncActivity : null
}
</script>

<template>
  <header class="site-header">
    <div class="site-header__inner">
      <p class="site-wordmark">
        <span class="site-wordmark__accent">GW2</span>
        <span>Crafting Tool</span>
        <span class="site-wordmark__scope">Trading Post &amp; crafting analysis</span>
      </p>

      <nav class="site-nav" aria-label="Application areas" data-test="screen-nav">
        <ul class="site-nav__list">
          <li v-for="destination in destinations" :key="destination.id">
            <a
              class="site-nav__link"
              :href="destination.path"
              :data-test="`nav-${destination.id}`"
              :aria-current="destination.id === currentId ? 'page' : undefined"
              @click="onLinkClick($event, destination.id)"
            >
              {{ destination.label }}
              <span
                v-if="activityOf(destination) !== null"
                class="activity"
                data-test="nav-sync-activity"
                >{{ activityOf(destination) }}</span
              >
            </a>
          </li>
        </ul>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.site-wordmark {
  margin-right: auto;
}

/* Reads as part of the link, not as a second control: no background fill and no pointer target. */
.activity {
  padding: 0 var(--space-2);
  border: 1px solid var(--color-info);
  border-radius: var(--radius-pill);
  color: var(--color-info);
  font-size: var(--text-sm);
  white-space: nowrap;
}
</style>
