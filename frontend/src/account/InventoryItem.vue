<script setup lang="ts">
// `formatCount`/`NO_VALUE` are the presentation helpers the Crafting Profit table already uses;
// they are reused rather than copied so a missing value renders as "—" here too, never as 0.
import { formatCount, NO_VALUE } from '@/crafting/formatCopper'
import ItemIcon from '@/items/ItemIcon.vue'

/**
 * One inventory entry as the backend supplied it: its item identity, its count and its display
 * metadata.
 *
 * The backend supplies no item name (`CURRENT_ARCHITECTURE.md` 5.12), so an item is identified by
 * its id and no name is invented or looked up. `iconUrl` is this application's own image URL for the
 * item (`TARGET_ARCHITECTURE.md` 12.1) or null when the backend has none; it is never an upstream URL
 * and never a filesystem path.
 *
 * The image itself is the shared `ItemIcon`, which is the only place an item image is rendered
 * (`STORY-WEB-010`). Bank and material storage are long grids, so entries load lazily; the id, the
 * count and the rarity beside the icon are unaffected by whether the picture arrives.
 */
defineProps<{
  itemId: number | null
  count: number | null
  rarity: string | null
  iconUrl: string | null
}>()
</script>

<template>
  <span class="entry">
    <ItemIcon :icon-url="iconUrl" :item-id="itemId" loading="lazy" />

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
