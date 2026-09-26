<script setup lang="ts">
/**
 * The heading block every page starts with: the page's own name, one optional introductory sentence
 * and the page's actions grouped together (`FRONTEND_UX_GUIDELINES.md` 2, 3).
 *
 * The heading is the page's only `h1` and is focusable programmatically (`tabindex="-1"`), which is
 * how the shell points assistive technology and the keyboard at the newly opened page.
 */
defineProps<{
  heading: string
  intro?: string
}>()
</script>

<template>
  <div class="page-head">
    <div class="page-head__text">
      <h1 tabindex="-1" data-page-heading data-test="page-heading">{{ heading }}</h1>
      <p v-if="intro !== undefined" class="page-head__intro" data-test="page-intro">{{ intro }}</p>
    </div>

    <div v-if="$slots.actions !== undefined" class="page-actions" data-test="page-actions">
      <slot name="actions" />
    </div>
  </div>
</template>
