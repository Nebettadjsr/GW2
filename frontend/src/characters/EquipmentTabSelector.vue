<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { EquipmentTab } from '@/api/types'

const props = defineProps<{ tabs: EquipmentTab[]; selected?: number }>()
const emit = defineEmits<{ inspect: [tab: number] }>()
const buttons = ref<HTMLElement[]>([])
function name(tab: EquipmentTab): string { return tab.name?.trim() || `Equipment ${tab.tab}` }
function description(tab: EquipmentTab): string { return `${name(tab)}${tab.active ? ' · In use in game' : ''}` }
async function onKey(event: KeyboardEvent, index: number): Promise<void> {
  const last = props.tabs.length - 1
  let next = index
  if (event.key === 'ArrowRight') next = index === last ? 0 : index + 1
  else if (event.key === 'ArrowLeft') next = index === 0 ? last : index - 1
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = last
  else return
  event.preventDefault()
  const target = props.tabs[next]
  if (!target) return
  emit('inspect', target.tab)
  await nextTick()
  buttons.value[next]?.focus()
}
</script>

<template>
  <div class="equipment-selector">
    <span class="equipment-selector__label">Equipment:</span>
    <div class="equipment-selector__tabs" role="tablist" aria-label="Equipment tabs" data-test="equipment-tablist">
      <button v-for="(tab, index) in tabs" :key="tab.tab" :ref="el => { if (el) buttons[index] = el as HTMLElement }"
        type="button" role="tab" :id="`equipment-tab-${tab.tab}`" aria-controls="equipment-tabpanel"
        :aria-selected="selected === tab.tab" :tabindex="selected === tab.tab ? 0 : -1"
        :aria-label="description(tab)" :title="description(tab)"
        :class="{ 'equipment-selector__selected': selected === tab.tab }" data-test="equipment-tab"
        @click="emit('inspect', tab.tab)" @keydown="onKey($event, index)">
        {{ tab.tab }}<span v-if="tab.active" class="equipment-selector__active" aria-hidden="true" data-test="equipment-tab-active">●</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.equipment-selector { display: flex; align-items: center; gap: 10px; }
.equipment-selector__label { color: var(--color-text-muted, #a9bcc8); font-size: .9rem; }
.equipment-selector__tabs { display: flex; gap: 4px; }
.equipment-selector button { min-width: 36px; padding: 5px 10px; border: 1px solid var(--border, #687780); border-radius: 6px; background: transparent; cursor: pointer; }
.equipment-selector button.equipment-selector__selected { background: var(--color-raised); font-weight: 700; }
.equipment-selector button:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }
.equipment-selector__active { margin-left: 6px; color: #dfbc6c; font-size: .55em; vertical-align: middle; }
</style>
