<script setup lang="ts">
import ItemIcon from '@/items/ItemIcon.vue'
import type { EquipmentItemRef } from '@/api/types'
const props = defineProps<{ item: EquipmentItemRef; runeCount?: number; showDescription?: boolean }>()
const names: Record<string, string> = { CritDamage: 'Ferocity', ConditionDuration: 'Expertise', BoonDuration: 'Concentration', Healing: 'Healing Power', ConditionDamage: 'Condition Damage', AgonyResistance: 'Agony Resistance' }
function effects(): string[] {
  const displayed = new Set(Object.entries(props.item.details?.attributes ?? {}).map(([attribute, value]) => `+${value} ${names[attribute] ?? attribute}`))
  return (props.item.details?.effects ?? []).filter(effect => !displayed.has(effect.trim()))
}
</script>

<template>
  <div class="upgrade">
    <div class="upgrade__name"><ItemIcon :icon-url="item.icon ?? null" :item-id="item.itemId" :size="24" />{{ item.name ?? `Item #${item.itemId}` }}</div>
    <div v-for="(value, attribute) in item.details?.attributes" :key="attribute" class="upgrade__attribute">+{{ value }} {{ names[attribute] ?? attribute }}</div>
    <div v-for="(bonus, index) in item.details?.runeBonuses" :key="index" :class="{ 'upgrade__inactive': runeCount !== undefined && index >= runeCount }">({{ index + 1 }}) {{ bonus }}</div>
    <div v-for="(effect, index) in effects()" :key="`effect-${index}`">{{ effect }}</div>
    <div v-if="showDescription !== false && item.details?.description" class="upgrade__description">{{ item.details.description }}</div>
  </div>
</template>

<style scoped>
.upgrade { color: #69baff; margin: 14px 0; }
.upgrade__name { display: flex; align-items: center; gap: 8px; margin-bottom: 3px; }
.upgrade__attribute { color: #55ee66; }
.upgrade__inactive { color: #818a91; }
.upgrade__description { margin-top: 4px; font-size: 13px; }
</style>
