<script setup lang="ts">
import { computed } from 'vue'
import { guideScreenshots, type GuideScreenshotId } from './guideScreenshots'

const props = defineProps<{ name: GuideScreenshotId; hideCaption?: boolean; sizes?: string }>()
const screenshot = computed(() => guideScreenshots[props.name])
</script>

<template>
  <figure v-if="screenshot" class="guide-screenshot" :style="{ maxWidth: `${screenshot.width}px` }">
    <a :href="screenshot.src" target="_blank" rel="noopener noreferrer"
      :aria-label="`Open larger image: ${screenshot.alt}`">
      <img :src="screenshot.src" :srcset="screenshot.srcset"
        :sizes="sizes ?? '(max-width: 48rem) calc(100vw - 96px), (max-width: 75rem) 75vw, 900px'"
        :width="screenshot.width" :height="screenshot.height" :alt="screenshot.alt"
        loading="lazy" decoding="async" />
    </a>
    <figcaption v-if="!hideCaption">{{ screenshot.caption }}</figcaption>
  </figure>
</template>

<style scoped>
.guide-screenshot { margin: var(--space-4) 0 0; max-width: 100%; }
.guide-screenshot a { display: block; border: 1px solid var(--color-border); border-radius: var(--radius-control); overflow: hidden; }
.guide-screenshot img { display: block; width: 100%; height: auto; }
.guide-screenshot figcaption { margin-top: var(--space-2); color: var(--color-muted); font-size: var(--text-sm); line-height: 1.5; }
</style>
