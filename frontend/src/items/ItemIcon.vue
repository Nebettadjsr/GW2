<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * The one image component every item view uses (`TARGET_ARCHITECTURE.md` 12.1, AR-005).
 *
 * It renders an ordinary `<img>` pointing at the **application-relative URL the backend supplied**,
 * verbatim. It never builds a URL of its own: not from an item id, not from a recipe id, and never
 * from an upstream address. The browser therefore only ever asks this application's own API routing
 * for an image, which is what keeps ArenaNet out of the browser's network entirely — no request, no
 * fallback, and no image-origin exception needed in a configured CSP.
 *
 * What it does *not* do is as much of the contract as what it does:
 *
 * - **No cache busting.** The `src` is the supplied string unchanged, so the same item keeps the same
 *   URL across openings and the browser's own HTTP cache (and the backend's `max-age`/ETag
 *   revalidation) can work. A timestamp or version query would defeat both.
 * - **No retry.** A failed load sets a flag and stops. `src` is never reassigned, so one failing
 *   image is one request, not a loop against a backend that is already answering 503.
 * - **No second fallback path.** Null metadata, a URL the backend rejected (which reaches here as
 *   null too) and a load/decode failure all end in the same bundled neutral placeholder below. It is
 *   inline SVG rather than a second asset file on purpose: the fallback for a failed image request
 *   must not itself be an image request that can fail.
 * - **No accessible name.** Every consumer already states the item beside the icon — its name, or
 *   its id when the backend supplied no name — so an `alt` here would announce the same item twice.
 *   The image is decorative in the accessibility sense and marked as such; the item text and the
 *   row/detail keyboard interaction are unaffected by anything that happens to the picture.
 *
 * An icon is display metadata only. Its presence, absence or failure says nothing about craftability,
 * ownership, price or any other domain state, and nothing here reads one out of it.
 */
const props = withDefaults(
  defineProps<{
    /**
     * The backend's own `iconUrl` for this item, used exactly as received, or null when the backend
     * has no accepted source for it.
     */
    iconUrl: string | null
    /**
     * The item this icon belongs to — the actual item whose metadata `iconUrl` came from, never a
     * recipe id. Read only as identity: a change to it retires a previous failure.
     */
    itemId: number | null
    /**
     * `eager` for the handful of icons an opened page shows straight away, `lazy` for the entries of
     * a long list, where the native attribute lets the browser skip what is far offscreen. A `lazy`
     * image inside the viewport is still loaded promptly by the browser; this is not a placeholder
     * scheme.
     */
    loading?: 'eager' | 'lazy'
    /** The rendered box in CSS pixels, reserved before the image arrives so nothing shifts. */
    size?: number
  }>(),
  { loading: 'lazy', size: 20 }
)

/** Set once, by the browser's own `error` event; reset only when this becomes a different image. */
const failed = ref(false)

/**
 * Identity is the item *and* the URL: the same item can be given a new URL when its retained source
 * changes, and the same URL can be reused for a different item. Either makes this a different image,
 * whose chances are not the previous one's, so the failure is dropped.
 */
watch(
  () => [props.itemId, props.iconUrl] as const,
  () => {
    failed.value = false
  }
)

const showImage = computed(() => props.iconUrl !== null && !failed.value)

/** Which of the three cases is on screen — the hook the browser and unit checks assert against. */
const state = computed(() => {
  if (props.iconUrl === null) return 'no-url'
  return failed.value ? 'failed' : 'image'
})

const box = computed(() => `${props.size}px`)

function onError(): void {
  failed.value = true
}
</script>

<template>
  <span
    class="item-icon"
    data-test="item-icon"
    :data-icon-state="state"
    :style="{ width: box, height: box }"
  >
    <!--
      `key` is the URL so a changed URL mounts a fresh element rather than re-pointing a failed one,
      which some browsers keep in their broken state. It is not part of the address.
    -->
    <img
      v-if="showImage"
      :key="iconUrl ?? ''"
      class="item-icon__image"
      data-test="item-icon-image"
      :src="iconUrl ?? undefined"
      :width="size"
      :height="size"
      :loading="loading"
      alt=""
      aria-hidden="true"
      decoding="async"
      referrerpolicy="no-referrer"
      @error="onError"
    />

    <!-- The one bundled neutral placeholder: no network, nothing to retry, nothing to announce. -->
    <svg
      v-else
      class="item-icon__fallback"
      data-test="item-icon-fallback"
      :width="size"
      :height="size"
      viewBox="0 0 20 20"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="1.5" y="1.5" width="17" height="17" rx="3" />
      <circle cx="10" cy="10" r="3.25" />
    </svg>
  </span>
</template>

<style scoped>
/*
 * The box is reserved by the wrapper as well as by the image's own width/height attributes, so the
 * space is held before anything is decoded and a late, failed or lazy image never reflows the text
 * beside it.
 */
.item-icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
}

.item-icon__image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* Neutral on purpose: an outline and a dot, in the muted text color, carrying no status meaning. */
.item-icon__fallback {
  display: block;
  width: 100%;
  height: 100%;
  fill: none;
  stroke: var(--color-muted);
  stroke-width: 1.25;
  opacity: 0.55;
}
</style>
