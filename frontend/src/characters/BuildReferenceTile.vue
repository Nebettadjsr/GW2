<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId } from 'vue'
import type { BuildIdRef } from '@/api/types'

defineProps<{ reference?: BuildIdRef | null; label: string; selected?: boolean }>()
const tooltipId = useId()
const opened = ref(false)
const tooltip = ref<HTMLElement | null>(null)
const position = ref({ left: '12px', top: '12px' })
let closing: ReturnType<typeof setTimeout> | undefined
function keepOpen(): void { clearTimeout(closing) }
function closeSoon(): void { keepOpen(); closing = setTimeout(() => { opened.value = false }, 120) }
onBeforeUnmount(keepOpen)

async function show(event: MouseEvent | FocusEvent): Promise<void> {
  keepOpen()
  const tile = (event.currentTarget as HTMLElement).getBoundingClientRect()
  opened.value = true
  await nextTick()
  if (!tooltip.value || !opened.value) return
  const box = tooltip.value.getBoundingClientRect()
  const left = tile.right + 12 + box.width <= window.innerWidth - 12
    ? tile.right + 12 : Math.max(12, tile.left - box.width - 12)
  position.value = { left: `${left}px`, top: `${Math.max(12, Math.min(tile.top, window.innerHeight - box.height - 12))}px` }
}
</script>

<template>
  <span class="build-tile" tabindex="0" :aria-label="label" :aria-current="selected ? 'true' : undefined"
    :aria-describedby="opened ? tooltipId : undefined" @mouseenter="show" @mouseleave="closeSoon"
    @focus="show" @blur="closeSoon" @keydown.esc="opened = false">
    <img v-if="reference?.iconUrl" :src="reference.iconUrl" alt="" class="build-tile__icon" />
    <span v-else class="build-tile__blank" aria-hidden="true">{{ reference ? '?' : '—' }}</span>
    <Teleport to="body">
      <div v-if="opened" :id="tooltipId" ref="tooltip" role="tooltip" class="build-tooltip" :style="position"
        @mouseenter="keepOpen" @mouseleave="closeSoon" @wheel.stop>
        <div class="build-tooltip__title">
          <img v-if="reference?.iconUrl" :src="reference.iconUrl" alt="" width="40" height="40" />
          <strong>{{ reference?.resolved && reference.name ? reference.name : label }}</strong>
        </div>
        <p v-if="reference?.description" class="build-tooltip__description">{{ reference.description }}</p>
        <div v-for="(fact, i) in reference?.facts" :key="i" class="build-tooltip__fact">
          <img v-if="fact.iconUrl" :src="fact.iconUrl" alt="" width="32" height="32" />
          <span>{{ fact.text }}<template v-if="fact.value !== undefined">: {{ fact.value }}</template></span>
        </div>
      </div>
    </Teleport>
  </span>
</template>

<style scoped>
.build-tile { display: inline-flex; position: relative; align-items: center; justify-content: center; box-sizing: border-box; cursor: default; }
.build-tile__icon { width: 100%; height: 100%; object-fit: contain; transform: scale(var(--build-icon-scale, 1)); }
.build-tile__blank { color: #bac2c6; font-size: 18px; }
.build-tile:focus-visible { outline: 2px solid var(--color-info); outline-offset: 3px; }
.build-tooltip { position: fixed; z-index: 1000; width: 360px; max-width: calc(100vw - 24px); max-height: calc(100vh - 24px); overflow-y: auto; box-sizing: border-box; padding: 14px; background: rgba(3, 6, 7, .96); border: 1px solid #48515a; box-shadow: 0 8px 24px #0007; color: #eee; font-size: 15px; line-height: 1.35; }
.build-tooltip__title { display: flex; align-items: center; gap: 10px; font-size: 18px; }
.build-tooltip__description { margin: 12px 0; white-space: pre-line; }
.build-tooltip__fact { display: flex; align-items: center; gap: 10px; margin-top: 8px; color: #c9cdce; }
.build-tooltip__fact img { flex: 0 0 32px; }
</style>
