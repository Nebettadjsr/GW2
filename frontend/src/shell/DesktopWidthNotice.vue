<script setup lang="ts">
import PageHeader from './PageHeader.vue'
import { MIN_TOOL_WIDTH_PX } from './useDesktopTools'

defineProps<{ heading: string }>()
</script>

<template>
  <section class="desktop-notice" data-test="desktop-width-notice" role="region" aria-labelledby="desktop-width-heading">
    <PageHeader :heading="heading" />
    <h2 id="desktop-width-heading">Please use a PC or a wider browser window</h2>
    <p>
      The account tools need a viewport at least {{ MIN_TOOL_WIDTH_PX }} CSS pixels wide. Widen this
      window to {{ MIN_TOOL_WIDTH_PX }}px or more and this page opens where you left it.
    </p>
    <p><a href="/welcome">Back to Home</a></p>
  </section>
</template>
