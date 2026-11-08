<script setup lang="ts">
/**
 * The heading block every page starts with: the page's own name, one optional introductory sentence
 * and the page's actions grouped together (`FRONTEND_UX_GUIDELINES.md` 2, 3).
 *
 * The heading is the page's only `h1` and is focusable programmatically (`tabindex="-1"`), which is
 * how the shell points assistive technology and the keyboard at the newly opened page.
 */
import { ref } from 'vue'

defineProps<{
  heading: string
  intro?: string
}>()

const headingEl = ref<HTMLElement | null>(null)
/** Lets a page move keyboard focus to its own heading without querying the DOM. */
defineExpose({ focusHeading: (options?: FocusOptions) => headingEl.value?.focus(options) })
</script>

<template>
  <div class="page-head">
    <div class="page-head__text">
      <div class="page-head__title">
        <slot name="icon" />
        <h1 ref="headingEl" tabindex="-1" data-page-heading data-test="page-heading">{{ heading }}</h1>
      </div>
      <p v-if="$slots.subtitle !== undefined" class="page-head__intro" data-test="page-subtitle"><slot name="subtitle" /></p>
      <p v-if="intro !== undefined" class="page-head__intro" data-test="page-intro">{{ intro }}</p>
    </div>

    <div v-if="$slots.actions !== undefined" class="page-actions" data-test="page-actions">
      <slot name="actions" />
    </div>
  </div>
</template>


<style scoped>
.page-head__title { display: flex; align-items: center; gap: 8px; }
.page-head__title h1 { margin: 0; }
</style>
