<script setup lang="ts">
// `formatCount`/`NO_VALUE` are the presentation helpers the Crafting Profit table already uses;
// they are reused rather than copied so a missing value renders as "—" here too, never as 0.
import { formatCount, NO_VALUE } from '@/crafting/formatCopper'

/**
 * One inventory entry as the backend supplied it: its item identity, its count and its display
 * metadata.
 *
 * The backend supplies no item name (`CURRENT_ARCHITECTURE.md` 5.12), so an item is identified by
 * its id and no name is invented or looked up. `iconUrl` is this application's own image URL for the
 * item (`TARGET_ARCHITECTURE.md` 12.1) or null when the backend has none; it is never an upstream URL
 * and never a filesystem path.
 *
 * Rendering that image is `STORY-WEB-010`'s shared image component. Until it lands, every entry keeps
 * the same neutral placeholder and no image is requested, so the prop is used only to say whether the
 * backend offered one.
 */
const props = defineProps<{
  itemId: number | null
  count: number | null
  rarity: string | null
  iconUrl: string | null
}>()

const iconTitle = (): string =>
  props.iconUrl === null
    ? 'No icon metadata was supplied for this item.'
    : 'An image URL was supplied; shared image rendering arrives with STORY-WEB-010.'
</script>

<template>
  <span class="entry">
    <span
      class="icon"
      data-test="item-icon-fallback"
      :data-icon-supplied="iconUrl === null ? 'false' : 'true'"
      :title="iconTitle()"
      aria-hidden="true"
    />

    <span class="identity" data-test="item-identity">
      <template v-if="itemId !== null">#{{ itemId }}</template>
      <template v-else>{{ NO_VALUE }} (no item id supplied)</template>
    </span>

    <span class="count" data-test="item-count">× {{ formatCount(count) }}</span>

    <span v-if="rarity !== null" class="rarity" data-test="item-rarity">{{ rarity }}</span>
  </span>
</template>

<style scoped>
.entry {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

/* Deliberately a plain block: no icon is fetched, so this is the fallback for every entry. */
.icon {
  width: 1.25rem;
  height: 1.25rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-bg);
  flex: none;
}

.identity {
  font-variant-numeric: tabular-nums;
}

/* An item and its count belong together on one line; a wrapped "× 250" reads as two values. */
.count {
  white-space: nowrap;
}

.rarity {
  color: var(--color-muted);
  font-size: var(--text-sm);
}
</style>
